package pzopt;

import java.util.ArrayList;
import java.util.List;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.IOpenGLState;
import zombie.core.opengl.Shader;
import zombie.core.opengl.ShaderUniformSetter;
import zombie.core.sprite.GenericSpriteRenderState;
import zombie.core.textures.TextureDraw;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.fboRenderChunk.FBORenderCell;
import zombie.iso.sprite.IsoSprite;

/**
 * The per-frame translucent tile pass recorded per chunk (tileRecordParallel, docs/plan-louisville-120-structural.md
 * change A): each on-screen chunk's translucent objects of the level are one unit, recorded into its own
 * {@link DrawRecorder} and spliced into the frame's draw list in stock chunk order.
 *
 * <p>Phase A1 ({@code devTileRecordSerial}): every unit is recorded on the game thread, one after the other, so the
 * recorder / splice machinery can be checked against the stock pass ({@code devDrawListCheck}) and its overhead
 * measured before any worker records a unit. Phase A2 ({@code tileRecordParallel}): the units on the frame workers.
 */
public final class TileRecord {
   private TileRecord() {
   }

   public static final int DEFER_OBJECT = 0; // FBORenderCell.renderTranslucent(IsoObject) on the game thread
   public static final int DEFER_ITEMS = 1; // FBORenderCell.renderWorldInventoryObjects(square, renderSquare, false)

   private static final ArrayList<DrawRecorder> recorders = new ArrayList<>(); // every thread's recorder (reset after each pass)
   private static final ThreadLocal<DrawRecorder> threadRecorder = ThreadLocal.withInitial(() -> {
      DrawRecorder r = new DrawRecorder();
      synchronized (recorders) {
         recorders.add(r);
      }
      return r;
   });
   // per recorded unit of the pass: its recorder and its entry / event ranges there
   private static DrawRecorder[] unitRec = new DrawRecorder[64];
   private static int[] unitEntryFrom = new int[64], unitEntryTo = new int[64], unitEvFrom = new int[64], unitEvTo = new int[64];
   private static boolean failed;
   private static int[] work = new int[64];
   private static final int UNIFORM_POOL = 512; // ShaderUniformSetter entries each recorder holds at a pass start (a tile-depth draw takes four)

   // dev counters, logged with the periodic FBORenderCell line
   static long passes, units, drawn, defers, conds, condsKept, checks, checkEntries, checkMismatches, recordNanos, spliceNanos, stockNanos;
   private static int checkLogged;

   /** Whether this frame's translucent pass goes through the recorders. */
   public static boolean active() {
      return (Config.TILE_RECORD_PARALLEL || Config.DEV_TILE_RECORD_SERIAL) && !failed && Overrides.enabled() && GtAb.on(GtAb.TILE_RECORD)
         && (Config.TILE_RECORD_VISUALS || !Config.MIRRORS && !PixelLight.ACTIVE && !Sway.frameOn) && zombie.characters.IsoPlayer.numPlayers == 1;
   }

   /**
    * Whether a recording thread may draw {@code o} itself. Everything else is deferred to the game thread at its place
    * in the stream. A1 keeps the A2 rule so its counts and overhead are the real ones: plain tiles, windows, doors,
    * curtains and window frames, with no light-on overlay (the power check walks generators and rooms), no clock hands,
    * no roof seam joining (it draws the neighbour square's roof tile, often another chunk's unit) and no 3D model.
    */
   static boolean eligible(IsoObject o) {
      return deferReason(o) < 0;
   }

   static final String[] REASONS = {"deferAll", "class", "noSprite", "lightOn", "roof", "model", "clock", "floorPath", "wallPath", "fascia", "attached", "cold", "mirror", "seam"};

   static final java.util.concurrent.atomic.AtomicLongArray reasons = new java.util.concurrent.atomic.AtomicLongArray(REASONS.length);
   static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder> deferClasses = new java.util.concurrent.ConcurrentHashMap<>();

   /**
    * -1 when a recording thread may draw {@code o}, else the index of the first reason it may not (REASONS). The rule
    * keeps the workers on the path that was audited for them: a plain tile through renderMinusFloor_NotDoorOrWall ->
    * IsoObject.render -> IsoSprite's tile-depth draw, with its overlay and attached sprites. Off it: other classes
    * (windows / doors / light switches / appliances), the floor and the door / wall paths (IsoGridSquare's wall lighting,
    * not made thread-safe), light-on overlays (the power check walks generators and rooms), roof seam joins (they draw
    * the neighbour square's roof tile, often another unit's; a roof sprite's lazy init runs on the game thread first),
    * 3D sprite models, clocks (they swap their shared sprite's texture while drawing), fascia (cutaway building
    * queries) and children / wall blood splats.
    */
   static int deferReason(IsoObject o) {
      return deferReason(o, false);
   }

   /**
    * {@code bake}: bakeRecordParallel's object pass. A bake draws a roof tile's joined neighbour (FBORenderCell's
    * renderJoinedRoofTile: another chunk's or level's object, its render info recomputed) only where the tile can have a
    * seam (a chunk edge, a level-below join): only those roof tiles stay on the game thread there.
    */
   static int deferReason(IsoObject o, boolean bake) {
      if (Config.DEV_TILE_RECORD_DEFER_ALL) {
         return 0;
      }
      if (Config.MIRRORS && Mirrors.capturable(o)) {
         return 12; // mirrors capture a window / mirror / reflective prop's quad as it draws (game-thread state)
      }
      Class<?> c = o.getClass();
      boolean window = c == zombie.iso.objects.IsoWindow.class;
      boolean door = c == zombie.iso.objects.IsoDoor.class;
      if (c != IsoObject.class && c != zombie.iso.objects.IsoLightSwitch.class && !window && !door) {
         return 1;
      }
      if (door && (((zombie.iso.objects.IsoDoor)o).HasCurtains() != null || o.getProperties() != null && o.getProperties().has(zombie.core.properties.IsoPropertyType.DOUBLE_DOOR))) {
         return 1; // a curtained door draws through IsoDoor's static colour scratch; a double door asks its master half (maybe another unit's) for its model
      }
      IsoSprite s = o.sprite;
      if (s == null || o.square == null) {
         return 2;
      }
      if (bake && seamWall(o)) {
         return 13; // FBORenderCell.renderSeamFix2_Wall's seam tile: TileSeamModifier.setupWallDepth reads IsoSprite.seamFix2, the game thread's
      }
      zombie.core.properties.PropertyContainer props = s.getProperties();
      if (props.has(IsoFlagType.HasLightOnSprite)
         && (c != zombie.iso.objects.IsoLightSwitch.class || o.getOnOverlay() == null && o.shouldShowOnOverlay())) {
         // a plain object's power check walks containers and fuel pipes; a light switch's own check reads its memo and
         // the squares around (IsoLightSwitch override), but its "on" overlay instance comes from the shared sprite pool,
         // made on the game thread first (once)
         return 3;
      }
      if (!s.pzoptRoofKnown() || s.getRoofProperties() != null && (!bake || zombie.iso.fboRenderChunk.FBORenderCell.pzoptRoofJoinPossible(o.square, s)
         || BakeRecord.roofJoinTarget(o.square))) {
         return 4; // (bake: a tile that may draw a neighbour's joined, or be drawn joined by one, on the game thread at its splice)
      }
      if (o.getSpriteModel() != null) {
         return 5;
      }
      if (s.name != null && zombie.scripting.ScriptManager.instance.getClockScript(s.name) != null) {
         return 6;
      }
      if (props.has(IsoFlagType.transparentFloor) || s.solidfloor) {
         return 7;
      }
      zombie.iso.SpriteDetails.IsoObjectType t = s.getTileType();
      boolean wallPath = s.cutN || s.cutW || t == zombie.iso.SpriteDetails.IsoObjectType.doorFrW || t == zombie.iso.SpriteDetails.IsoObjectType.doorFrN
         || t == zombie.iso.SpriteDetails.IsoObjectType.doorW || t == zombie.iso.SpriteDetails.IsoObjectType.doorN || o.getType() == zombie.iso.SpriteDetails.IsoObjectType.doorFrW
         || o.getType() == zombie.iso.SpriteDetails.IsoObjectType.doorFrN;
      if (wallPath && !Config.TILE_RECORD_WALLS) {
         return 8;
      }
      if (!door && (t == zombie.iso.SpriteDetails.IsoObjectType.doorW || t == zombie.iso.SpriteDetails.IsoObjectType.doorN)) {
         return 8; // a door tile that is not an IsoDoor (IsoThumpable / plain): keep it off the workers
      }
      if (o.isFascia()) {
         return 9;
      }
      if (o.pzoptHasChildrenOrSplats()) {
         return 10;
      }
      if (!o.pzoptSpritesWarm()) {
         return 11;
      }
      return -1;
   }

   /**
    * bakeRecordParallel: an object whose wall draw may add a seam tile (FBORenderCell.renderSeamFix2_Wall's conditions: a
    * north / west wall or window on the chunk's east / south edge with a wall, window frame or door wall on the next square;
    * the tileset exclusions left out, so a few more stay than need to).
    */
   static boolean seamWall(IsoObject o) {
      zombie.iso.IsoGridSquare sq = o.square;
      boolean ex = Math.floorMod(sq.x, 8) == 7, ey = Math.floorMod(sq.y, 8) == 7;
      if (!ex && !ey) {
         return false;
      }
      zombie.core.properties.PropertyContainer p = o.sprite.getProperties();
      if (p.has(IsoFlagType.HoppableN) || p.has(IsoFlagType.HoppableW)) {
         return false;
      }
      boolean nw = p.has(IsoFlagType.WallNW);
      if (ey && (nw || p.has(IsoFlagType.WallW) || p.has(IsoFlagType.WindowW))) {
         zombie.iso.IsoGridSquare s = sq.getAdjacentSquare(zombie.iso.IsoDirections.S);
         if (s != null && ((s.getWallType() & 4) != 0 || s.getWindowFrame(zombie.iso.objects.GridSquareEdgeFacingDirection.EAST_WEST) != null
            || s.has(IsoFlagType.DoorWallW))) {
            return true;
         }
      }
      if (ex && (nw || p.has(IsoFlagType.WallN) || p.has(IsoFlagType.WindowN))) {
         zombie.iso.IsoGridSquare e = sq.getAdjacentSquare(zombie.iso.IsoDirections.E);
         if (e != null && ((e.getWallType() & 1) != 0 || e.getWindowFrame(zombie.iso.objects.GridSquareEdgeFacingDirection.NORTH_SOUTH) != null
            || e.has(IsoFlagType.DoorWallN))) {
            return true;
         }
      }
      return false;
   }

   /** FBORenderCell.renderTranslucent(IsoObject): true when the calling thread records and {@code o} is deferred instead. */
   public static boolean deferObject(IsoObject o) {
      DrawRecorder r = DrawRecorder.current();
      if (r == null) {
         return false;
      }
      int why = deferReason(o);
      if (why < 0) {
         r.drawn++;
         return false;
      }
      if (Config.INSTRUMENT) { // dev census
         reasons.incrementAndGet(why);
         deferClasses.computeIfAbsent(o.getClass().getSimpleName() + ":" + REASONS[why], k -> new java.util.concurrent.atomic.LongAdder()).increment();
      }
      r.defer(DEFER_OBJECT, o, r.windowFrameOutline ? Boolean.TRUE : Boolean.FALSE); // drawn with the flag stock had set around it
      return true;
   }

   /** FBORenderCell.renderOneLevel_Translucent's renderWindowFrameOutline flag: true when the calling thread records (its recorder keeps it). */
   public static boolean setWindowFrameOutline(boolean v) {
      DrawRecorder r = DrawRecorder.current();
      if (r == null) {
         return false;
      }
      r.windowFrameOutline = v;
      return true;
   }

   /** FBORenderCell.pzoptWindowFrameOutline: the recorder's flag. */
   public static boolean windowFrameOutline(DrawRecorder r) {
      return r.windowFrameOutline;
   }

   /** FBORenderCell.renderOneLevel_Translucent's items: true when the calling thread records (the items are deferred). */
   public static boolean deferItems(IsoGridSquare square, IsoGridSquare renderSquare) {
      DrawRecorder r = DrawRecorder.current();
      if (r == null) {
         return false;
      }
      r.defer(DEFER_ITEMS, square, renderSquare);
      return true;
   }

   /**
    * FBORenderCell.renderTranslucent(square, layer), before a non-tree object: true when the calling thread records
    * (the tree flush happens at splice time on the game thread), false for the stock check.
    */
   public static boolean treeFlushPoint() {
      DrawRecorder r = DrawRecorder.current();
      if (r == null) {
         return false;
      }
      r.flushPoint();
      return true;
   }

   /** FBORenderCell.renderTranslucentObjects: the chunk loop, recorded per chunk and spliced in order. */
   public static void translucentPass(FBORenderCell cell, List<IsoChunk> chunks, int playerIndex, int z, Shader floorShader, Shader wallShader, long ms) {
      if (spliceFrameLevel(cell, chunks, playerIndex, z, floorShader, wallShader, ms)) {
         return;
      }
      int n = chunks.size();
      GenericSpriteRenderState real = DrawRecorder.real();
      passes++;
      boolean check = Config.DEV_DRAW_LIST_CHECK > 0 && zombie.iso.IsoCamera.frameState.frameCount % Config.DEV_DRAW_LIST_CHECK == 0;
      Check snap = null;
      if (check) {
         snap = Check.stock(cell, chunks, playerIndex, z, floorShader, wallShader, ms, real);
      }
      long t0 = System.nanoTime();
      // units with nothing to draw at this level (most on-screen chunks) are not recorded: their stock call, a few
      // state entries, runs at its place in the splice
      int w = 0;
      grow(n);
      for (int i = 0; i < n; i++) {
         if (cell.pzoptTranslucentWork(chunks.get(i), playerIndex, z)) {
            work[w++] = i;
         }
      }
      final int nw = w;
      final int[] wk = work;
      prepareDispatch(nw);
      DrawRecorder.recording = true;
      Throwable failure = null;
      try {
         if (Config.TILE_RECORD_PARALLEL && nw > 1) {
            failure = FrameBatch.run(nw, k -> record(cell, k, chunks.get(wk[k]), playerIndex, z, floorShader, wallShader, ms, real));
         } else {
            for (int k = 0; k < nw; k++) {
               record(cell, k, chunks.get(wk[k]), playerIndex, z, floorShader, wallShader, ms, real);
            }
         }
      } catch (Throwable t) {
         failure = t;
      } finally {
         DrawRecorder.recording = false;
      }
      long t1 = System.nanoTime();
      Deferred deferred = new Deferred(cell);
      DrawRecorder.carryZ = TextureDraw.nextZ; // the stream's carried next-draw depth
      DrawRecorder.carryCd = TextureDraw.nextChunkDepth;
      int k = 0;
      for (int i = 0; i < n; i++) {
         if (k < nw && wk[k] == i) {
            DrawRecorder r = unitRec[k];
            LightingDefer.applyOne(k); // the unit's lazy-lighting side effects (level invalidations, puddle / cutaway / room hooks), as stock ran them during its draws
            if (r != null) {
               r.splice(real, deferred, unitEntryFrom[k], unitEntryTo[k], unitEvFrom[k], unitEvTo[k]);
            }
            unitRec[k] = null;
            k++;
         } else {
            TextureDraw.nextZ = DrawRecorder.carryZ;
            TextureDraw.nextChunkDepth = DrawRecorder.carryCd;
            cell.pzoptRecordChunkTranslucent(chunks.get(i), playerIndex, z, floorShader, wallShader, ms);
            DrawRecorder.carryZ = TextureDraw.nextZ;
            DrawRecorder.carryCd = TextureDraw.nextChunkDepth;
         }
      }
      TextureDraw.nextZ = DrawRecorder.carryZ;
      TextureDraw.nextChunkDepth = DrawRecorder.carryCd;
      collectCounters();
      long t2 = System.nanoTime();
      recordNanos += t1 - t0;
      spliceNanos += t2 - t1;
      if (GtAb.TIMING) {
         GtAb.add(GtAb.S_TL_RECORD, t1 - t0);
         GtAb.add(GtAb.S_TL_SPLICE, t2 - t1);
      }
      if (failure != null) {
         failed = true;
         Log.warn("tileRecordParallel: a unit failed, the stock pass from now on: " + failure);
         failure.printStackTrace();
      }
      if (snap != null) {
         snap.compare(real);
      }
   }

   /**
    * Game thread, before units record: what the recording threads read or take. IsoSprite's screen offset (lazily set
    * by the first draw of a frame, the same value from any thread), each recorder's uniform pool, the lazy-lighting
    * effect lists of {@code n} units.
    */
   private static void prepareDispatch(int n) {
      zombie.iso.IsoGridSquare.pzoptWarmCutawayTextures();
      if (zombie.iso.sprite.IsoSprite.globalOffsetX == -1.0F) {
         zombie.iso.sprite.IsoSprite.globalOffsetX = -zombie.iso.IsoCamera.frameState.offX;
         zombie.iso.sprite.IsoSprite.globalOffsetY = -zombie.iso.IsoCamera.frameState.offY;
      }
      synchronized (recorders) {
         for (int q = 0; q < recorders.size(); q++) {
            ShaderUniformSetter.pzoptRefill(recorders.get(q), UNIFORM_POOL);
         }
      }
      LightingDefer.prepare(n);
   }

   private static void grow(int n) {
      if (work.length < n) {
         int m = Math.max(n, work.length * 2);
         work = new int[m];
         unitRec = new DrawRecorder[m];
         unitEntryFrom = new int[m];
         unitEntryTo = new int[m];
         unitEvFrom = new int[m];
         unitEvTo = new int[m];
         unitZ = new int[m];
      }
   }

   private static void collectCounters() {
      synchronized (recorders) {
         for (int q = 0; q < recorders.size(); q++) {
            DrawRecorder r = recorders.get(q);
            r.reset();
            units += r.units;
            defers += r.defers;
            conds += r.conds;
            condsKept += r.condsKept;
            drawn += r.drawn;
            r.units = r.defers = r.conds = r.condsKept = r.drawn = 0;
         }
      }
   }

   // ---- tileRecordAsync: every level's units recorded in one batch while the game thread draws the rest of the frame ----

   private static boolean frameOn; // this frame's units were dispatched by startFrame
   private static boolean framePending; // their batch has not been joined yet
   private static int frameUnits, frameMinZ, frameMaxZ, frameLevelsSpliced;
   private static int[] levelFrom = new int[64], levelTo = new int[64]; // per level (z - frameMinZ), its units [from, to) (chunk order)
   private static int[] unitZ = new int[64];
   private static List<IsoChunk> frameChunks;
   private static volatile Throwable frameFailure;
   private static AlphaSnap asyncSnap; // devDrawListCheck, async: the objects' alpha at dispatch

   /** The alpha / target alpha of every object on the given levels of the chunks (devDrawListCheck). */
   static final class AlphaSnap {
      final ArrayList<IsoObject> objects = new ArrayList<>();
      final ArrayList<float[]> alphas = new ArrayList<>();

      static AlphaSnap capture(List<IsoChunk> chunks, int minZ, int maxZ, int playerIndex) {
         AlphaSnap a = new AlphaSnap();
         for (IsoChunk chunk : chunks) {
            for (int z = minZ; z <= maxZ; z++) {
               for (int y = 0; y < 8; y++) {
                  for (int x = 0; x < 8; x++) {
                     IsoGridSquare sq = chunk.getGridSquare(x, y, z);
                     if (sq == null) {
                        continue;
                     }
                     for (int k = 0; k < sq.getObjects().size(); k++) {
                        IsoObject o = sq.getObjects().get(k);
                        a.objects.add(o);
                        a.alphas.add(new float[]{o.getAlpha(playerIndex), o.getTargetAlpha(playerIndex)});
                     }
                  }
               }
            }
         }
         return a;
      }

      void restore(int playerIndex) {
         for (int i = 0; i < this.objects.size(); i++) {
            IsoObject o = this.objects.get(i);
            float[] v = this.alphas.get(i);
            o.setAlpha(playerIndex, v[0]);
            o.setTargetAlpha(playerIndex, v[1]);
         }
      }
   }
   static long asyncFrames, asyncJoinNanos, asyncDispatchNanos;

   /**
    * FBORenderCell.performRenderTiles, after the chunk composite (the per-level square lists of the frame are final):
    * every level's units of the translucent pass are handed to the frame workers at once and recorded while the game
    * thread draws the players, corpses, moving objects, water and attachments. The first translucent pass joins the
    * batch; each level is spliced at its own pass, in stock order.
    */
   public static void startFrame(FBORenderCell cell, List<IsoChunk> chunks, int playerIndex, int minZ, int maxZ, Shader floorShader, Shader wallShader, long ms) {
      frameOn = false;
      asyncSnap = null;
      if (!Config.TILE_RECORD_PARALLEL || !Config.TILE_RECORD_ASYNC || !active()) {
         return;
      }
      long t0 = System.nanoTime();
      int levels = maxZ - minZ + 1;
      if (levels <= 0 || levels > 64) {
         return;
      }
      int n = chunks.size();
      grow(n * levels);
      int u = 0;
      for (int z = minZ; z <= maxZ; z++) {
         levelFrom[z - minZ] = u;
         for (int i = 0; i < n; i++) {
            if (cell.pzoptTranslucentWork(chunks.get(i), playerIndex, z)) {
               work[u] = i;
               unitZ[u] = z;
               u++;
            }
         }
         levelTo[z - minZ] = u;
      }
      if (u == 0) {
         return;
      }
      frameUnits = u;
      frameMinZ = minZ;
      frameMaxZ = maxZ;
      frameLevelsSpliced = 0;
      frameChunks = chunks;
      frameFailure = null;
      prepareDispatch(u);
      if (Config.DEV_DRAW_LIST_CHECK > 0 && zombie.iso.IsoCamera.frameState.frameCount % Config.DEV_DRAW_LIST_CHECK == 0) {
         asyncSnap = AlphaSnap.capture(chunks, minZ, maxZ, playerIndex); // the objects' alpha before the units record (the check re-runs stock from it)
      }
      final GenericSpriteRenderState real = DrawRecorder.real();
      final int[] wk = work;
      final int[] uz = unitZ;
      DrawRecorder.recording = true;
      frameOn = true;
      framePending = true;
      asyncFrames++;
      FrameBatch.runAsync(u, k -> record(cell, k, chunks.get(wk[k]), playerIndex, uz[k], floorShader, wallShader, ms, real), failure -> {
         DrawRecorder.recording = false;
         framePending = false;
         frameFailure = failure;
      });
      asyncDispatchNanos += System.nanoTime() - t0;
   }

   /** The pass of level z: true when startFrame recorded it (it was spliced here), false = the caller's own path. */
   static boolean spliceFrameLevel(FBORenderCell cell, List<IsoChunk> chunks, int playerIndex, int z, Shader floorShader, Shader wallShader, long ms) {
      if (!frameOn || chunks != frameChunks || z < frameMinZ || z > frameMaxZ) {
         return false;
      }
      long t0 = System.nanoTime();
      if (framePending) {
         FrameBatch.join();
         if (framePending) { // joined through another batch user's join: the completion ran there
            DrawRecorder.recording = false;
            framePending = false;
         }
      }
      long t1 = System.nanoTime();
      asyncJoinNanos += t1 - t0;
      GenericSpriteRenderState real = DrawRecorder.real();
      passes++;
      Check snap = null;
      if (asyncSnap != null) {
         // devDrawListCheck in async mode: the stock pass of this level from the objects' state before the units
         // recorded, rolled back, then the splice; the objects get their after-recording state back in between
         AlphaSnap post = AlphaSnap.capture(chunks, z, z, playerIndex);
         asyncSnap.restore(playerIndex);
         snap = Check.stock(cell, chunks, playerIndex, z, floorShader, wallShader, ms, real);
         post.restore(playerIndex);
         t1 = System.nanoTime();
      }
      Deferred deferred = new Deferred(cell);
      int from = levelFrom[z - frameMinZ];
      int to = levelTo[z - frameMinZ];
      int k = from;
      int n = chunks.size();
      DrawRecorder.carryZ = TextureDraw.nextZ; // the stream's carried next-draw depth
      DrawRecorder.carryCd = TextureDraw.nextChunkDepth;
      for (int i = 0; i < n; i++) {
         if (k < to && work[k] == i) {
            DrawRecorder r = unitRec[k];
            LightingDefer.applyOne(k);
            if (r != null) {
               r.splice(real, deferred, unitEntryFrom[k], unitEntryTo[k], unitEvFrom[k], unitEvTo[k]);
            }
            unitRec[k] = null;
            k++;
         } else {
            TextureDraw.nextZ = DrawRecorder.carryZ;
            TextureDraw.nextChunkDepth = DrawRecorder.carryCd;
            cell.pzoptRecordChunkTranslucent(chunks.get(i), playerIndex, z, floorShader, wallShader, ms);
            DrawRecorder.carryZ = TextureDraw.nextZ;
            DrawRecorder.carryCd = TextureDraw.nextChunkDepth;
         }
      }
      TextureDraw.nextZ = DrawRecorder.carryZ;
      TextureDraw.nextChunkDepth = DrawRecorder.carryCd;
      long t2 = System.nanoTime();
      recordNanos += t1 - t0;
      spliceNanos += t2 - t1;
      if (GtAb.TIMING) {
         GtAb.add(GtAb.S_TL_RECORD, t1 - t0);
         GtAb.add(GtAb.S_TL_SPLICE, t2 - t1);
      }
      if (snap != null) {
         snap.compare(real);
      }
      if (++frameLevelsSpliced == frameMaxZ - frameMinZ + 1) {
         endFrame();
      }
      return true;
   }

   /** FBORenderCell.performRenderTiles after the level loop (and spliceFrameLevel after the last level): the frame's units are done. */
   public static void endFrame() {
      if (!frameOn) {
         return;
      }
      if (framePending) {
         FrameBatch.join();
         DrawRecorder.recording = false;
         framePending = false;
      }
      frameOn = false;
      frameChunks = null;
      for (int k = 0; k < frameUnits; k++) {
         unitRec[k] = null;
      }
      collectCounters();
      Throwable f = frameFailure;
      if (f != null) {
         failed = true;
         Log.warn("tileRecordParallel: a unit failed, the stock pass from now on: " + f);
         f.printStackTrace();
      }
   }

   private static void record(FBORenderCell cell, int k, IsoChunk chunk, int playerIndex, int z, Shader floorShader, Shader wallShader, long ms,
         GenericSpriteRenderState real) {
      DrawRecorder r = threadRecorder.get();
      LightingDefer.begin(k);
      r.beginUnit(real);
      unitRec[k] = r;
      unitEntryFrom[k] = r.unitEntry;
      unitEvFrom[k] = r.unitEvent;
      try {
         cell.pzoptRecordChunkTranslucent(chunk, playerIndex, z, floorShader, wallShader, ms);
      } finally {
         r.endUnit();
         LightingDefer.end();
         unitEntryTo[k] = r.state.numSprites;
         unitEvTo[k] = r.eventCount();
      }
   }

   /** Game thread, at splice time: the deferred draws, through the stock path. */
   private static final class Deferred implements DrawRecorder.Deferred {
      final FBORenderCell cell;

      Deferred(FBORenderCell cell) {
         this.cell = cell;
      }

      @Override
      public void run(int kind, Object a, Object b) {
         if (kind == DEFER_OBJECT) {
            IsoObject o = (IsoObject)a;
            if (o.sprite != null && !o.sprite.pzoptRoofKnown()) {
               o.sprite.getRoofProperties(); // the sprite's lazy roof init, here on the game thread (a window or wall never reaches it on its own path), so the next frame can record it
            }
            boolean outline = b == Boolean.TRUE;
            if (outline) {
               this.cell.renderWindowFrameOutline = true;
            }
            this.cell.renderTranslucent((IsoObject)a);
            if (outline) {
               this.cell.renderWindowFrameOutline = false;
            }
         } else {
            this.cell.pzoptRenderItemsTranslucent((IsoGridSquare)a, (IsoGridSquare)b);
         }
      }

      @Override
      public void flushTrees() {
         this.cell.pzoptFlushTrees();
      }
   }

   static long stockPasses, stockPassNanos;

   /** FBORenderCell.renderTranslucentObjects: one stock pass took {@code ns} (the A/B's other half). */
   public static void stockPass(long ns) {
      stockPasses++;
      stockPassNanos += ns;
   }

   public static String describe() {
      String s = "tile record: async frames=" + asyncFrames + " dispatch ms=" + asyncDispatchNanos / 1_000_000L + " join ms=" + asyncJoinNanos / 1_000_000L + " stock passes=" + stockPasses + " stock us/pass=" + (stockPasses == 0 ? 0 : stockPassNanos / 1000L / stockPasses)
         + " recorded us/pass=" + (passes == 0 ? 0 : (recordNanos + spliceNanos) / 1000L / passes) + " passes=" + passes + " units=" + units + " drawn=" + drawn + " defers=" + defers + " conds=" + conds + " kept=" + condsKept
         + " record ms=" + recordNanos / 1_000_000L + " splice ms=" + spliceNanos / 1_000_000L
         + (checks > 0 ? " check passes=" + checks + " entries=" + checkEntries + " mismatches=" + checkMismatches + " stock ms=" + stockNanos / 1_000_000L : "");
      if (Config.INSTRUMENT) {
         s += " defer reasons:";
         for (int i = 0; i < REASONS.length; i++) {
            s += " " + REASONS[i] + "=" + reasons.get(i);
         }
         s += " classes:" + deferClasses.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum())).limit(12)
            .map(e -> " " + e.getKey() + "=" + e.getValue().sum()).collect(java.util.stream.Collectors.joining());
      }
      return s;
   }

   /** The game thread's GL state caches (GLState's), for the check's snapshot and roll-back. */
   static final class GlStates {
      private static IOpenGLState<?>[] all;

      static IOpenGLState<?>[] all() {
         if (all == null) {
            all = new IOpenGLState<?>[]{zombie.core.opengl.GLState.AlphaFunc, zombie.core.opengl.GLState.AlphaTest, zombie.core.opengl.GLState.Blend,
               zombie.core.opengl.GLState.BlendFunc, zombie.core.opengl.GLState.BlendFuncSeparate, zombie.core.opengl.GLState.ColorMask,
               zombie.core.opengl.GLState.DepthFunc, zombie.core.opengl.GLState.DepthMask, zombie.core.opengl.GLState.DepthTest,
               zombie.core.opengl.GLState.ScissorTest, zombie.core.opengl.GLState.StencilFunc, zombie.core.opengl.GLState.StencilMask,
               zombie.core.opengl.GLState.StencilOp, zombie.core.opengl.GLState.StencilTest};
         }
         return all;
      }
   }

   // ---- devDrawListCheck: the stock pass and the recorded one back to back in one frame, compared entry by entry ----

   static final class Check {
      int n0;
      int postRender0;
      final ArrayList<Entry> stock = new ArrayList<>();
      IOpenGLState<?>[] glStates;
      IOpenGLState.Value[] glValues;
      boolean[] glDirty;
      final ArrayList<IsoObject> objects = new ArrayList<>();
      final ArrayList<float[]> alphas = new ArrayList<>();

      static Check stock(FBORenderCell cell, List<IsoChunk> chunks, int playerIndex, int z, Shader floorShader, Shader wallShader, long ms, GenericSpriteRenderState real) {
         Check c = new Check();
         c.n0 = real.numSprites;
         c.postRender0 = real.postRender.size();
         c.glStates = GlStates.all();
         c.glValues = new IOpenGLState.Value[c.glStates.length];
         c.glDirty = new boolean[c.glStates.length];
         for (int i = 0; i < c.glStates.length; i++) {
            c.glValues[i] = c.glStates[i].pzoptNewValue();
            c.glDirty[i] = c.glStates[i].pzoptSnapshot(c.glValues[i]);
         }
         for (IsoChunk chunk : chunks) {
            for (int y = 0; y < 8; y++) {
               for (int x = 0; x < 8; x++) {
                  IsoGridSquare sq = chunk.getGridSquare(x, y, z);
                  if (sq == null) {
                     continue;
                  }
                  for (int k = 0; k < sq.getObjects().size(); k++) {
                     IsoObject o = sq.getObjects().get(k);
                     c.objects.add(o);
                     c.alphas.add(new float[]{o.getAlpha(playerIndex), o.getTargetAlpha(playerIndex)});
                  }
               }
            }
         }
         float nz = TextureDraw.nextZ;
         float ncd = TextureDraw.nextChunkDepth;
         zombie.iso.fboRenderChunk.FBORenderTrees trees = cell.pzoptTreesSnapshot();
         int treeCount = cell.pzoptTreesCount();
         long t0 = System.nanoTime();
         for (IsoChunk chunk : chunks) {
            cell.pzoptRecordChunkTranslucent(chunk, playerIndex, z, floorShader, wallShader, ms);
         }
         stockNanos += System.nanoTime() - t0;
         for (int i = c.n0; i < real.numSprites; i++) {
            c.stock.add(new Entry(real.sprite[i], real.style[i]));
         }
         // roll the stock pass back: the frame keeps the recorded one
         real.numSprites = c.n0;
         while (real.postRender.size() > c.postRender0) {
            real.postRender.remove(real.postRender.size() - 1);
         }
         for (int i = 0; i < c.glStates.length; i++) {
            c.glStates[i].pzoptRestore(c.glValues[i], c.glDirty[i]);
         }
         cell.pzoptTreesRestore(trees, treeCount);
         TextureDraw.nextZ = nz;
         TextureDraw.nextChunkDepth = ncd;
         for (int i = 0; i < c.objects.size(); i++) {
            IsoObject o = c.objects.get(i);
            float[] a = c.alphas.get(i);
            o.setAlpha(playerIndex, a[0]);
            o.setTargetAlpha(playerIndex, a[1]);
         }
         return c;
      }

      void compare(GenericSpriteRenderState real) {
         checks++;
         int n = real.numSprites - this.n0;
         checkEntries += this.stock.size();
         int bad = 0;
         String first = null;
         if (n != this.stock.size()) {
            bad++;
            first = "entry count stock=" + this.stock.size() + " recorded=" + n;
         }
         int m = Math.min(n, this.stock.size());
         for (int i = 0; i < m; i++) {
            String d = this.stock.get(i).diff(real.sprite[this.n0 + i], real.style[this.n0 + i]);
            if (d != null) {
               bad++;
               if (first == null) {
                  first = "entry " + i + "/" + m + ": " + d;
               }
            }
         }
         if (bad > 0) {
            checkMismatches += bad;
            if (checkLogged++ < 20) {
               Log.info("tile record check: " + bad + " differences in a pass of " + this.stock.size() + " entries, first: " + first);
            }
         }
      }
   }

   /** A copy of a TextureDraw's fields (the stock pass's entries are reused by the recorded one). */
   static final class Entry {
      final TextureDraw.Type type;
      final Object style;
      final boolean flipped, singleCol;
      final int a, b, c, d, col0, col1, col2, col3, tex1Col0, tex1Col1, tex1Col2, tex1Col3;
      final float f1, z, chunkDepth;
      final float[] xy, uv, t1, t2, vars;
      final Object tex, tex1, tex2;
      final byte useAttribArray;
      final Class<?> drawerClass;
      final ShaderUniformSetter uniforms;
      final String uniformsText;

      Entry(TextureDraw t, Object style) {
         this.type = t.type;
         this.style = style;
         this.flipped = t.flipped;
         this.singleCol = t.singleCol;
         this.a = t.a;
         this.b = t.b;
         this.c = t.c;
         this.d = t.d;
         this.col0 = t.col0;
         this.col1 = t.col1;
         this.col2 = t.col2;
         this.col3 = t.col3;
         this.tex1Col0 = t.tex1Col0;
         this.tex1Col1 = t.tex1Col1;
         this.tex1Col2 = t.tex1Col2;
         this.tex1Col3 = t.tex1Col3;
         this.f1 = t.f1;
         this.z = t.z;
         this.chunkDepth = t.chunkDepth;
         this.xy = new float[]{t.x0, t.x1, t.x2, t.x3, t.y0, t.y1, t.y2, t.y3};
         this.uv = new float[]{t.u0, t.u1, t.u2, t.u3, t.v0, t.v1, t.v2, t.v3};
         this.t1 = new float[]{t.tex1U0, t.tex1U1, t.tex1U2, t.tex1U3, t.tex1V0, t.tex1V1, t.tex1V2, t.tex1V3};
         this.t2 = new float[]{t.tex2U0, t.tex2U1, t.tex2U2, t.tex2U3, t.tex2V0, t.tex2V1, t.tex2V2, t.tex2V3};
         this.vars = t.vars == null ? null : t.vars.clone();
         this.tex = t.tex;
         this.tex1 = t.tex1;
         this.tex2 = t.tex2;
         this.useAttribArray = t.useAttribArray;
         this.drawerClass = t.drawer == null ? null : t.drawer.getClass();
         // a uniform chain is pooled: copy it so the stock entry's chain can be released and reused
         this.uniforms = t.drawer instanceof ShaderUniformSetter u ? u : null;
         this.uniformsText = this.uniforms == null ? null : this.uniforms.pzoptDescribe();
      }

      String diff(TextureDraw t, Object style) {
         if (t.type != this.type) {
            return "type " + this.type + " vs " + t.type;
         }
         if (style != this.style) {
            return this.type + " style";
         }
         // a TextureDraw is reused: each entry type sets only its own fields, the rest are stale from an earlier use
         switch (this.type) {
            case glDraw:
               return this.diffDraw(t);
            case glDepthFunc: case glEnable: case glDisable: case glStencilMask: case glBlendEquation: case glDepthMask: case glBind:
            case glGenerateMipMaps: case glIgnoreStyles: case glClear:
               return t.a != this.a ? this.type + " a=" + this.a + "/" + t.a : null;
            case glBlendFunc: case glBuffer: case glBindFramebuffer: case FBORenderChunkStart:
               return t.a != this.a || t.b != this.b ? this.type + " args" : null;
            case glStencilFunc: case glStencilOp: case glTexParameteri: case glDoStartFrameFx:
               return t.a != this.a || t.b != this.b || t.c != this.c ? this.type + " args" : null;
            case glBlendFuncSeparate: case glViewport:
               return t.a != this.a || t.b != this.b || t.c != this.c || t.d != this.d ? this.type + " args" : null;
            case glAlphaFunc: case doCoreIntParam:
               return t.a != this.a || Float.floatToIntBits(t.f1) != Float.floatToIntBits(this.f1) ? this.type + " args" : null;
            case glColorMask:
               return t.a != this.a || t.b != this.b || t.c != this.c || Float.floatToIntBits(t.x0) != Float.floatToIntBits(this.xy[0]) ? this.type + " args" : null;
            case StartShader:
               if (t.a != this.a || t.c != this.c) {
                  return "StartShader id=" + this.a + "/" + t.a + " c=" + this.c + "/" + t.c;
               }
               return this.diffDrawer(t);
            case ShaderUpdate:
               if (t.a != this.a || t.b != this.b || t.c != this.c) {
                  return "ShaderUpdate args";
               }
               if (this.c == -1) {
                  return t.d != this.d ? "ShaderUpdate int" : null;
               }
               for (int k = 0; k < Math.min(4, Math.max(0, this.c)); k++) {
                  float a = k == 0 ? t.u0 : k == 1 ? t.u1 : k == 2 ? t.u2 : t.u3;
                  if (Float.floatToIntBits(a) != Float.floatToIntBits(this.uv[k])) {
                     return "ShaderUpdate float " + k;
                  }
               }
               return null;
            default:
               return this.diffDrawer(t);
         }
      }

      private String diffDrawer(TextureDraw t) {
         Class<?> dc = t.drawer == null ? null : t.drawer.getClass();
         if (dc != this.drawerClass) {
            return this.type + " drawer " + this.drawerClass + " vs " + dc;
         }
         if (this.uniforms != null && !this.uniforms.pzoptSameChain((ShaderUniformSetter)t.drawer)) {
            return this.type + " uniforms stock [" + this.uniformsText + "] recorded [" + ((ShaderUniformSetter)t.drawer).pzoptDescribe() + "]";
         }
         return null;
      }

      private String diffDraw(TextureDraw t) {
         if (t.flipped != this.flipped || t.singleCol != this.singleCol || t.useAttribArray != this.useAttribArray) {
            return "draw flags flipped " + this.flipped + "/" + t.flipped + " singleCol " + this.singleCol + "/" + t.singleCol + " attrib " + this.useAttribArray + "/"
               + t.useAttribArray + " tex " + texName(t.tex);
         }
         if (t.col0 != this.col0 || t.col1 != this.col1 || t.col2 != this.col2 || t.col3 != this.col3) {
            return "draw colours " + Integer.toHexString(this.col0) + "/" + Integer.toHexString(t.col0) + " tex " + texName(t.tex);
         }
         if (Float.floatToIntBits(t.z) != Float.floatToIntBits(this.z) || Float.floatToIntBits(t.chunkDepth) != Float.floatToIntBits(this.chunkDepth)) {
            return "draw depth z=" + this.z + "/" + t.z + " chunkDepth=" + this.chunkDepth + "/" + t.chunkDepth + " tex " + texName(t.tex) + " tex1 " + texName(t.tex1);
         }
         if (!same(this.xy, t.x0, t.x1, t.x2, t.x3, t.y0, t.y1, t.y2, t.y3)) {
            return "draw position " + this.xy[0] + "," + this.xy[4] + " vs " + t.x0 + "," + t.y0;
         }
         if (!same(this.uv, t.u0, t.u1, t.u2, t.u3, t.v0, t.v1, t.v2, t.v3)) {
            return "draw uv";
         }
         if (t.tex != this.tex || t.tex1 != this.tex1 || t.tex2 != this.tex2) {
            return "draw texture";
         }
         if (this.tex1 != null && (!same(this.t1, t.tex1U0, t.tex1U1, t.tex1U2, t.tex1U3, t.tex1V0, t.tex1V1, t.tex1V2, t.tex1V3)
            || t.tex1Col0 != this.tex1Col0 || t.tex1Col1 != this.tex1Col1 || t.tex1Col2 != this.tex1Col2 || t.tex1Col3 != this.tex1Col3)) {
            return "draw tex1";
         }
         if (this.tex2 != null && !same(this.t2, t.tex2U0, t.tex2U1, t.tex2U2, t.tex2U3, t.tex2V0, t.tex2V1, t.tex2V2, t.tex2V3)) {
            return "draw tex2";
         }
         return this.diffDrawer(t);
      }

      private static String texName(zombie.core.textures.Texture t) {
         return t == null ? "null" : t.getName();
      }

      private static boolean same(float[] a, float... b) {
         for (int i = 0; i < a.length; i++) {
            if (Float.floatToIntBits(a[i]) != Float.floatToIntBits(b[i])) {
               return false;
            }
         }
         return true;
      }
   }
}
