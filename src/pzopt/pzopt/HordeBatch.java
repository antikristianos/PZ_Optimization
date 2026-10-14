package pzopt;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLCapabilities;
import zombie.characters.IsoZombie;
import zombie.core.Core;
import zombie.core.DefaultShader;
import zombie.core.SceneShaderStore;
import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.math.PZMath;
import zombie.core.opengl.CharacterModelCamera;
import zombie.core.opengl.GLState;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.PerformanceSettings;
import zombie.core.skinnedmodel.ModelCamera;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelInstanceRenderDataList;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.skinnedmodel.shader.ShaderManager;
import zombie.core.sprite.SpriteRenderState;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.debug.DebugOptions;
import zombie.iso.IsoMovingObject;
import zombie.iso.PlayerCamera;
import zombie.iso.Vector2;
import zombie.scripting.objects.ModelScript;
import zombie.vehicles.BaseVehicle;

/**
 * The horde's 3D zombie models drawn in batches ({@code hordeBatch}, 2026-10-13, Louisville 120 goal).
 *
 * <p>Stock draws every on-screen 3D zombie on the render thread as its own little scene: a full attribute push / pop, the
 * camera, and per model instance (body, hair, clothes; ~4 a zombie) a program switch, ~25 uniform calls, a 60-matrix bone
 * palette upload, the vertex pointers, the draw and a re-issue of thirteen cached GL states. With ~450 model zombies on
 * screen that is ~1,800 draws and 7.6 ms of GPU a frame on the desktop (devHordeModes: 3.7 ms of it without a single
 * draw call, i.e. state and uniform traffic). Here the render thread takes a zombie's DrawModel command apart instead:
 * it computes exactly what the stock path would hand the shader (the same Java math on the same values: camera
 * matrices, target depth, the five lights, tint, alpha, the palette floats) into a draw record and a palette buffer,
 * and draws runs of zombies with one program ({@code pzopt_horde}, build.sh's copy of the game's basicEffect with every
 * per-draw uniform read from the record), one vertex array object per mesh and one integer uniform per draw, inside a
 * single attribute push / pop. The fixed-function state each draw sees is the stock one draw for draw (the first
 * instance of a character with the depth mask on, the later ones with the cached state stock's per-draw restore puts
 * back).
 *
 * <p>{@code hordeBatch=exact}: a run ends at any command or sprite batch that is not another zombie draw or its camera
 * record, so the draw order and everything between stay the stock ones. {@code defer}: the zombies of the whole moving
 * pass are drawn at its end (the stock order among them kept): the same picture as a stock frame whose object set
 * happened to list the 3D zombies after the other moving objects (the stock order is the hash order of the cell's
 * object set, a different permutation every session); not pixel-identical to a given stock frame where a sprite and a
 * zombie overlap.
 *
 * <p>Stays stock: any non-zombie, debug builds, the outline / imposter / in-vehicle / card paths, an instance whose
 * program is not the plain skinned basicEffect, and frames where an Enhancement hooks the model draws (entity shadows,
 * occluded outlines, object motion, mirrors, sun shadow casters, foliage sway's MRT). Needs GL 4.3 (storage buffers);
 * macOS / GL 2.1 keep the stock path.
 */
public final class HordeBatch {
   public static final int OFF = 0;
   public static final int EXACT = 1;
   public static final int DEFER = 2;
   private static final int CONFIG_MODE = "exact".equalsIgnoreCase(Config.HORDE_BATCH) ? EXACT : "defer".equalsIgnoreCase(Config.HORDE_BATCH) ? DEFER : OFF;
   private static final boolean DEV_DIGITS = Config.DEV_HORDE_MODES.matches(".*[5-9].*");
   private static final int FLOATS = 76; // one draw record (std430 PzoptHordeDraw)
   private static final int RECORD_BYTES = FLOATS * 4;
   private static final int MAX_DRAWS = 8192; // per frame
   private static final int MAX_PALETTE_FLOATS = 4 << 20; // per frame (16 MB)

   private HordeBatch() {
   }

   /** Whether the pass markers are queued at all (game thread). */
   public static boolean wanted() {
      return (CONFIG_MODE != OFF || DEV_DIGITS) && Overrides.enabled() && !failed;
   }

   /** This frame's mode on the render thread (devHordeModes digits 5 / 6 force exact / defer, 0-4 force stock). */
   private static int mode() {
      if (!Config.DEV_HORDE_MODES.isEmpty()) {
         int d = HordeDraw.mode;
         return d == 5 ? EXACT : d >= 6 && d <= 9 ? DEFER : OFF;
      }
      return CONFIG_MODE;
   }

   // ---- pass markers (game thread queues, render thread runs) ----
   private static final class Marker extends TextureDraw.GenericDrawer {
      private static final ArrayList<Marker> POOL = new ArrayList<>();
      boolean end;

      static Marker get(boolean end) {
         Marker m;
         synchronized (POOL) {
            m = POOL.isEmpty() ? new Marker() : POOL.remove(POOL.size() - 1);
         }
         m.end = end;
         return m;
      }

      @Override
      public void render() {
         if (this.end) {
            endPassRender();
         } else {
            beginPassRender();
         }
      }

      @Override
      public void postRender() {
         synchronized (POOL) {
            POOL.add(this);
         }
      }
   }

   /** Game thread, before the moving objects pass. */
   public static void beginPass() {
      if (wanted()) {
         SpriteRenderer.instance.drawGeneric(Marker.get(false));
      }
   }

   /** Game thread, after the moving objects pass (before its GPU section ends). */
   public static void endPass() {
      if (wanted()) {
         SpriteRenderer.instance.drawGeneric(Marker.get(true));
      }
   }

   // ---- render thread state ----
   private static boolean inPass;
   private static boolean failed;
   private static boolean initTried;
   private static int program;
   private static int locDraw = -1;
   private static int drawIdBuffer;
   private static int programStatic;
   private static int locDrawStatic = -1;
   private static boolean staticFailed;
   private static final int SETS = 3;
   private static final int[] ssboDrawSets = new int[SETS];
   private static final int[] ssboPaletteSets = new int[SETS];
   private static int set;
   private static final int MSETS = 4;
   private static final int[] mapDrawIds = new int[MSETS];
   private static final int[] mapPalIds = new int[MSETS];
   private static final ByteBuffer[] mapDraw = new ByteBuffer[MSETS];
   private static final FloatBuffer[] mapPal = new FloatBuffer[MSETS];
   private static final long[] mapFence = new long[MSETS];
   private static boolean mappedOk;
   private static boolean useMapped;
   private static int mset;
   private static ByteBuffer stageBytes;
   private static FloatBuffer stagePalette;
   private static long fenceWaits;
   private static long skippedDraws;
   private static VertexBufferObject firstVb;
   private static ByteBuffer drawBytes;
   private static FloatBuffer drawFloats;
   private static IntBuffer drawInts;
   private static FloatBuffer palette;
   private static int frameDraws; // records written this frame (also the next record's index)
   private static int framePalette; // palette floats written this frame
   private static int runStart; // first record of the pending run
   private static int runPaletteStart;
   /** Draws waiting for the flush. */
   public static int pending;
   private static int pendingMode;
   // per pending draw
   private static final Texture[] drawTex = new Texture[MAX_DRAWS];
   private static final VertexBufferObject[] drawVb = new VertexBufferObject[MAX_DRAWS];
   private static final int[] drawCount = new int[MAX_DRAWS];
   private static final int[] drawCull = new int[MAX_DRAWS]; // 0 = off, else the face
   private static final byte[] drawState = new byte[MAX_DRAWS];
   private static final boolean[] drawStatic = new boolean[MAX_DRAWS];
   private static final boolean[] drawCharStart = new boolean[MAX_DRAWS]; // bit0 depth mask, bit1 blend, bit2 scissor
   // per pending character (dev check replay)
   private static final ArrayList<TextureDraw> runCmds = new ArrayList<>();
   private static final ArrayList<float[]> runCams = new ArrayList<>();
   private static final StringBuilder runInfo = new StringBuilder();
   // counters
   private static long statChars;
   private static long statDraws;
   private static long statFlushes;
   private static long statTexFlushes;
   private static long statStock;
   private static long statFrames;
   private static long lastLogNs;

   private static void beginPassRender() {
      if (pending > 0) {
         flush();
      }
      inPass = true;
      set = (set + 1) % SETS;
      useMapped = mappedOk && HordeDraw.mode != 9;
      if (useMapped) {
         mset = (mset + 1) % MSETS;
         long f = mapFence[mset];
         if (f != 0L) {
            // four frames back: done long ago in practice; a status poll, and a real wait only if the GPU is that far behind
            int r = org.lwjgl.opengl.GL32.glClientWaitSync(f, 0, 0L);
            if (r != org.lwjgl.opengl.GL32.GL_ALREADY_SIGNALED && r != org.lwjgl.opengl.GL32.GL_CONDITION_SATISFIED) {
               fenceWaits++;
               org.lwjgl.opengl.GL32.glClientWaitSync(f, org.lwjgl.opengl.GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 200_000_000L);
            }
            org.lwjgl.opengl.GL32.glDeleteSync(f);
            mapFence[mset] = 0L;
         }
      }
      if (program != 0) {
         pointBuffers();
      }
      frameDraws = 0;
      framePalette = 0;
      runStart = 0;
      runPaletteStart = 0;
      statFrames++;
      checkFrame = Config.DEV_HORDE_CHECK > 0 && statFrames % Config.DEV_HORDE_CHECK == 0 && checkLastMs < 1000L && !checkDisabled; // a check slower than a second stops the rig
      runCmds.clear();
      runCams.clear();
      runInfo.setLength(0);
      if (checkFrame) {
         checkVariant = Config.DEV_HORDE_CHECK_VARIANTS ? VARIANTS[(int)(checkIndex++ % VARIANTS.length)] : 0;
      }
   }

   private static void endPassRender() {
      if (pending > 0) {
         flush();
      }
      if (useMapped && frameDraws > 0 && mapFence[mset] == 0L) {
         mapFence[mset] = org.lwjgl.opengl.GL32.glFenceSync(org.lwjgl.opengl.GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
      }
      inPass = false;
      long now = System.nanoTime();
      if (now - lastLogNs > 10_000_000_000L) {
         lastLogNs = now;
         Log.info("horde batch: mode=" + Config.HORDE_BATCH + " frames=" + statFrames + " chars=" + statChars + " draws=" + statDraws + " flushes=" + statFlushes + " (texture renders " + statTexFlushes + ")"
            + " stock=" + statStock + " mapped=" + mappedOk + " fenceWaits=" + fenceWaits + " skipped=" + skippedDraws + " rejects:" + rejects() + " instance:" + java.util.Arrays.toString(INSTANCE_REJECT) + (Config.DEV_HORDE_CHECK > 0 ? " check: runs=" + checkRuns + " vacuous=" + checkVacuous + " drawnPixels=" + checkDrawn + " pixels=" + checkPixels + " colourDiff=" + checkColour + " depthDiff=" + checkDepth + " maxDelta=" + checkMaxDelta + " lastMs=" + checkLastMs + (Config.DEV_HORDE_CHECK_VARIANTS ? " variants runs/diff " + java.util.Arrays.toString(VAR_RUNS) + "/" + java.util.Arrays.toString(VAR_DIFF) : "") : ""));
      }
   }

   /**
    * Render thread, TextureDraw.run before any command while draws are pending: an exact run ends at anything but another
    * character draw or a camera record; a deferred pass only at a framebuffer / viewport / stencil / colour-mask change.
    */
   public static void beforeOp(TextureDraw texd) {
      if (pending == 0) {
         return;
      }
      TextureDraw.Type t = texd.type;
      if (t == TextureDraw.Type.DrawModel) {
         Object d = texd.drawer;
         if (d instanceof ModelSlotRenderData || d instanceof zombie.core.skinnedmodel.ModelCameraRenderData || d instanceof Marker || isNeutralGeneric(d)) {
            return; // the offer decides for a model draw; camera records only set Java fields
         }
         if (pendingMode == EXACT) {
            flush();
         }
         return;
      }
      if (pendingMode == EXACT) {
         flush();
         return;
      }
      switch (t) {
         case glBuffer:
         case glBindFramebuffer:
         case glClear:
         case glViewport:
         case glStencilFunc:
         case glStencilOp:
         case glStencilMask:
         case glColorMask:
         case glDoStartFrame:
         case glDoStartFrameText:
         case glDoEndFrame:
         case glDoStartFrameFx:
         case glDoEndFrameFx:
         case glDoStartFrameNoZoom:
         case glDoStartFrameFlipY:
         case NewFrame:
         case FBORenderChunkEnd:
         case FBORenderChunkStart:
         case pushIsoView:
         case popIsoView:
         case RenderQueued:
         case DrawImGui:
         case glClearDepth:
            flush();
            break;
         default:
            break;
      }
   }

   private static boolean isNeutralGeneric(Object d) {
      return d instanceof HordeDraw.Neutral || GpuSections.isMarker(d);
   }

   /** Render thread, a sprite batch of the ring buffer is about to draw. */
   public static void beforeSprites() {
      if (pending > 0 && pendingMode == EXACT) {
         flush();
      }
   }

   /**
    * Render thread, TextureDraw's DrawModel with a model drawer, after its init future: true = taken (drawn in a batch, or
    * nothing to draw, as stock would), false = the stock path draws it (after any pending batch).
    */
   public static boolean offer(TextureDraw texd, int motionId) {
      int m = inPass ? mode() : OFF;
      if (m == OFF || failed) {
         if (pending > 0) {
            flush();
         }
         return false;
      }
      if (!(texd.drawer instanceof ModelSlotRenderData slot)) {
         return false; // a generic drawer: beforeOp has decided (camera records and markers stay inside a run)
      }
      if (!batchable(texd, slot, motionId) || !initGl() && reject(9)) {
         if (pending > 0 && (pendingMode == EXACT || slot.character != null)) {
            flush(); // a deferred pass lets vehicles through (drawn now, the zombies at the end: another object order)
         }
         statStock++;
         return false;
      }
      if (pending > 0 && pendingMode != m) {
         flush();
      }
      if (pending == 0) {
         pendingMode = m;
      }
      if (pending > 0 && slot.pzoptRendersTexture()) {
         flush(); // its texture render goes after the earlier characters' draws, as in the stock order
         statTexFlushes++;
      }
      ModelInstanceRenderDataList list = slot.pzoptBatchBegin();
      if (list == null) {
         return true; // stock: checkReady said not ready and nothing ready from before: nothing drawn
      }
      int n = list.size();
      if ((frameDraws + n > MAX_DRAWS || framePalette + n * 64 * 16 > MAX_PALETTE_FLOATS) && !reject(10)) {
         if (pending > 0) {
            flush();
         }
         statStock++;
         return false; // the stock path (its checkReady again is a no-op now)
      }
      record(slot, list);
      slot.pzoptBatchEnd();
      statChars++;
      if (checkFrame) {
         runCmds.add(texd);
         if ((checkVariant & 1) != 0) {
            flush(); // dev check variant: a run of one character
         }
      }
      return true;
   }

   private static final long[] REJECT = new long[12];
   private static final String[] REJECT_NAMES = {"notZombie", "debug", "slotState", "outlineOrCard", "texdHooks", "enhancement", "camera", "debugOption", "instance", "noGl", "overflow", "motion"};

   private static boolean reject(int why) {
      REJECT[why]++;
      return false;
   }

   private static String rejects() {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < REJECT.length; i++) {
         if (REJECT[i] != 0) {
            sb.append(' ').append(REJECT_NAMES[i]).append('=').append(REJECT[i]);
         }
      }
      return sb.toString();
   }

   private static boolean batchable(TextureDraw texd, ModelSlotRenderData slot, int motionId) {
      if (!(slot.character instanceof IsoZombie)) {
         return reject(0);
      }
      if (Core.debug) {
         return reject(1);
      }
      if (motionId != 0) {
         return reject(11);
      }
      if (slot.inVehicle || slot.debugChunkState || slot.renderToTexture || slot.animPlayer == null || slot.modelSlot == null || slot.modelSlot.model == null) {
         return reject(2);
      }
      if (slot.IsRenderingToCard() || slot.pzoptHasOutline() || slot.pzoptHasDebugDraw()) {
         return reject(3);
      }
      if (texd.pzoptShadowTile >= 0 || texd.pzoptLampN > 0 || texd.pzoptMirrorPlanes != 0 || texd.pzoptOutline != 0) {
         return reject(4);
      }
      if (OccludedOutline.isActive() || EntityShadow.modelShaderPatched() || Sway.mrt || Ssr.stencilMoving()) {
         return reject(5);
      }
      ModelCamera cam = ModelCamera.instance;
      if (cam != CharacterModelCamera.instance || !cam.useWorldIso || cam.inVehicle) {
         return reject(6);
      }
      DebugOptions dbg = DebugOptions.instance;
      if (dbg.character.debug.render.skipCharacters.getValue() || dbg.debugDrawSkipDrawNonSkinnedModel.getValue() || dbg.debugDrawSkipVboDraw.getValue()
         || dbg.isoSprite.characterMipmapColors.getValue() || dbg.fboRenderChunk.nolighting.getValue() || dbg.model.render.wireframe.getValue()
         || dbg.checks.boundTextures.getValue()) {
         return reject(7);
      }
      ModelInstanceRenderDataList all = slot.modelData;
      for (int i = 0; i < all.size(); i++) {
         int why = instanceWhy((ModelInstanceRenderData)all.get(i));
         if (why != 0) {
            INSTANCE_REJECT[why]++;
            return reject(8);
         }
      }
      return true;
   }

   private static final long[] INSTANCE_REJECT = new long[8];

   /** 0 = batchable; else why not (1 no instance / model, 2 static, 3 program, 4 mesh, 5 palette). */
   private static int instanceWhy(ModelInstanceRenderData d) {
      ModelInstance mi = d.modelInstance;
      Model model = d.model;
      if (mi == null || model == null || mi.model == null) {
         return 1;
      }
      Shader e = model.effect;
      if (e == null || e.isStatic() != model.isStatic || e.isInstanced() || !"basicEffect".equals(e.getName())) {
         return 3;
      }
      if (model.mesh == null || model.mesh.vb == null || !Model.pzoptMeshDrawable(model.mesh.vb)) {
         return 4;
      }
      if (model.isStatic) {
         return programStatic == 0 && staticFailed ? 2 : 0; // an attached item: its transform goes where a palette would
      }
      FloatBuffer p = d.matrixPalette;
      if (p == null || (p.limit() - p.position()) % 16 != 0 || p.limit() - p.position() > 64 * 16) {
         return 5;
      }
      return 0;
   }

   // ---- the stock math, per character ----
   private static final Matrix4f P = new Matrix4f();
   private static final Matrix4f MV = new Matrix4f();
   private static final Matrix4f MVP = new Matrix4f();
   private static final Matrix4f DEPTH_MVP = new Matrix4f();
   private static final Vector3f POS = new Vector3f();
   private static final org.lwjgl.util.vector.Vector3f LVEC = new org.lwjgl.util.vector.Vector3f();
   private static final Vector2 UV = new Vector2();
   private static final float[] LIGHT = new float[6];
   private static final float[] REC = new float[FLOATS];

   /** Core.DoPushIsoStuff's projection and model-view, without the matrix stacks or the depth range call. */
   private static void camera(float ox, float oy, float oz, float useangle, boolean vehicle) {
      Core core = Core.getInstance();
      float cx = core.floatParamMap.get(0);
      float cy = core.floatParamMap.get(1);
      float cz = core.floatParamMap.get(2);
      double x = cx;
      double y = cy;
      double z = cz;
      SpriteRenderState renderState = SpriteRenderer.instance.getRenderingState();
      int playerIndex = renderState.playerIndex;
      PlayerCamera cam = renderState.playerCamera[playerIndex];
      float rcx = cam.rightClickX;
      float rcy = cam.rightClickY;
      float tox = cam.getTOffX();
      float toy = cam.getTOffY();
      float defx = cam.deferedX;
      float defy = cam.deferedY;
      x -= cam.XToIso(-tox - rcx, -toy - rcy, 0.0F);
      y -= cam.YToIso(-tox - rcx, -toy - rcy, 0.0F);
      x += defx;
      y += defy;
      double screenWidth = cam.offscreenWidth / 1920.0F;
      double screenHeight = cam.offscreenHeight / 1920.0F;
      P.setOrtho(-((float)screenWidth) / 2.0F, (float)screenWidth / 2.0F, -((float)screenHeight) / 2.0F, (float)screenHeight / 2.0F, -10.0F, 10.0F);
      MV.scaling(Core.scale);
      MV.scale(Core.tileScale / 2.0F);
      MV.rotate((float) (Math.PI / 6), 1.0F, 0.0F, 0.0F);
      MV.rotate((float) (Math.PI * 3.0 / 4.0), 0.0F, 1.0F, 0.0F);
      double difX = ox - x;
      double difY = oy - y;
      MV.translate(-((float)difX), (float)(oz - z) * 2.44949F, -((float)difY));
      if (vehicle) {
         MV.scale(-1.0F, 1.0F, 1.0F);
      } else {
         MV.scale(-1.5F, 1.5F, 1.5F);
      }
      MV.rotate(useangle + (float)Math.PI, 0.0F, 1.0F, 0.0F);
      if (!vehicle) {
         MV.translate(0.0F, -0.48F, 0.0F);
      }
   }

   /** Shader.setLight's direction and colour (dir xyz, col rgb) for one effect light. */
   private static void light(ModelInstance.EffectLight el, float animPlayerAngle, float offsetX, float offsetY, float offsetZ, IsoMovingObject instObject) {
      float x = el.x, y = el.y, z = el.z, r = el.r, g = el.g, b = el.b;
      float rad = el.radius;
      if (r + g + b != 0.0F && !(rad <= 0.0F)) {
         org.lwjgl.util.vector.Vector3f vec = LVEC;
         if (!Float.isNaN(animPlayerAngle)) {
            vec.set(x, y, z);
            vec.x -= offsetX;
            vec.y -= offsetY;
            vec.z -= offsetZ;
         } else {
            vec.set(x, y, z);
         }
         float dist = vec.length();
         if (dist < 1.0E-4F) {
            vec.set(0.0F, 0.0F, 1.0F);
         } else {
            vec.normalise();
         }
         if (!Float.isNaN(animPlayerAngle)) {
            float radians = -animPlayerAngle;
            float vx = vec.x;
            float vy = vec.y;
            vec.x = vx * org.joml.Math.cos(radians) - vy * org.joml.Math.sin(radians);
            vec.y = vx * org.joml.Math.sin(radians) + vy * org.joml.Math.cos(radians);
         }
         float temp = vec.y;
         vec.y = vec.z;
         vec.z = temp;
         if (vec.length() < 1.0E-4F) {
            vec.set(0.0F, 1.0F, 0.0F);
         }
         vec.normalise();
         float del = 1.0F - dist / rad;
         if (del < 0.0F) {
            del = 0.0F;
         }
         if (del > 1.0F) {
            del = 1.0F;
         }
         r *= del;
         g *= del;
         b *= del;
         r = PZMath.clamp(r, 0.0F, 1.0F);
         g = PZMath.clamp(g, 0.0F, 1.0F);
         b = PZMath.clamp(b, 0.0F, 1.0F);
         LIGHT[0] = -vec.x;
         LIGHT[1] = vec.y;
         LIGHT[2] = vec.z;
         LIGHT[3] = r;
         LIGHT[4] = g;
         LIGHT[5] = b;
      } else {
         LIGHT[0] = 0.0F;
         LIGHT[1] = 1.0F;
         LIGHT[2] = 0.0F;
         LIGHT[3] = 0.0F;
         LIGHT[4] = 0.0F;
         LIGHT[5] = 0.0F;
      }
   }

   private static boolean stateTrue(zombie.core.opengl.IOpenGLState<?> s) {
      return s.pzoptCurrent().equals(GLState.CBooleanValue.TRUE);
   }

   /** One character: performRenderCharacter's per-character values and DrawChar / startCharacter's per-instance ones. */
   private static void record(ModelSlotRenderData slot, ModelInstanceRenderDataList modelData) {
      ModelCamera c = ModelCamera.instance;
      if (checkFrame) {
         runCams.add(new float[]{c.x, c.y, c.z, c.useAngle, c.useWorldIso ? 1.0F : 0.0F, c.inVehicle ? 1.0F : 0.0F});
      }
      camera(c.x, c.y, c.z, c.useAngle, c.inVehicle);
      // VertexBufferObject.getDepthValueAt(0, 0, 0)
      P.mul(MV, DEPTH_MVP);
      POS.set(0.0F, 0.0F, 0.0F);
      DEPTH_MVP.transformPosition(POS);
      float depthBufferValue = POS.z;
      slot.modelSlot.model.targetDepth = slot.squareDepth - (depthBufferValue + 1.0F) / 2.0F + 0.5F - 1.0E-4F;
      // VertexBufferObject.setModelViewProjection: projection.mul(modelView)
      MVP.set(P).mul(MV);
      // the cached states stock's per-draw GLStateRenderThread.restore() puts back before the second and later instances
      byte later = (byte)((stateTrue(GLStateRenderThread.DepthMask) ? 1 : 0) | (stateTrue(GLStateRenderThread.Blend) ? 2 : 0)
         | (stateTrue(GLStateRenderThread.ScissorTest) ? 4 : 0));
      boolean drawable = !(slot.alpha < 0.01F);
      boolean charPartsDone = false;
      int drawsAtStart = frameDraws;
      boolean first = true;
      for (int i = 0; i < modelData.size(); i++) {
         ModelInstanceRenderData data = (ModelInstanceRenderData)modelData.get(i);
         ModelInstance mi = data.modelInstance;
         if (mi != null) {
            mi.targetDepth = slot.modelSlot.model.targetDepth;
         }
         // RenderCharacter
         data.tintR = mi.tintR;
         data.tintG = mi.tintG;
         data.tintB = mi.tintB;
         data.tex = mi.tex;
         if (data.tex == null && mi.model.tex == null) {
            continue;
         }
         if (!drawable) {
            continue; // DrawChar: alpha < 0.01 draws nothing
         }
         Model model = data.model;
         int k = frameDraws;
         int f = k * FLOATS;
         // palette: the instance's floats, shared with an identical earlier palette of the same character
         int base;
         if (model.isStatic) {
            base = framePalette;
            data.xfrm.get(base, palette); // Shader.setTransformMatrix(xfrm, true): column-major floats, read row-major
            framePalette += 16;
         } else {
         FloatBuffer pal = data.matrixPalette;
         int p0 = pal.position();
         int len = pal.limit() - p0;
         base = checkFrame && (checkVariant & 8) != 0 ? -1 : findPalette(pal, p0, len); // (dev check variant 8: no palette sharing)
         if (base < 0) {
            base = framePalette;
            palette.put(base, pal, p0, len);
            framePalette += len;
            if (palCount < palStarts.length) {
               palStarts[palCount] = base;
               palLens[palCount] = len;
               palSrc[palCount] = pal;
               palSrcPos[palCount] = p0;
               palCount++;
            }
         }
         }
         // the record, built in a Java array (the per-character parts once) and sent in one bulk copy: the mapped
         // buffer is write-combined memory, where many small writes cost more than one
         float[] r = REC;
         if (!charPartsDone) {
            MVP.get(r, 0);
            for (int li = 0; li < 5; li++) {
               // Shader.setLights' values depend on the character only: computed once, written for each instance
               light(slot.effectLights[li], slot.animPlayerAngle, slot.x, slot.y, slot.z, slot.object);
               int o = 36 + li * 4;
               r[o] = LIGHT[0];
               r[o + 1] = LIGHT[1];
               r[o + 2] = LIGHT[2];
               r[o + 3] = 0.0F;
               int q = 56 + li * 4;
               r[q] = LIGHT[3];
               r[q + 1] = LIGHT[4];
               r[q + 2] = LIGHT[5];
               r[q + 3] = 0.0F;
            }
            r[19] = slot.alpha;
            r[27] = slot.finalScale;
            r[29] = 0.0F; // HighResDepthMultiplier: every other user of the program puts it back to 0
            r[30] = 1.0F; // LightingAmount
            r[31] = 0.0F;
            r[33] = 0.0F;
            r[34] = 0.0F;
            r[35] = 0.0F;
            charPartsDone = true;
         }
         r[16] = data.tintR;
         r[17] = data.tintG;
         r[18] = data.tintB;
         r[20] = data.ignoreLighting ? 1.0F : slot.ambientR * 0.45F;
         r[21] = data.ignoreLighting ? 1.0F : slot.ambientG * 0.45F;
         r[22] = data.ignoreLighting ? 1.0F : slot.ambientB * 0.45F;
         r[23] = data.hue;
         Texture tex = data.tex != null ? data.tex : model.tex;
         Vector2 uv = tex.getUVScale(UV);
         r[24] = uv.x;
         r[25] = uv.y;
         float targetDepth = mi.parent != null ? mi.parent.targetDepth : mi.targetDepth;
         if (!PerformanceSettings.fboRenderChunk) {
            targetDepth = 0.5F;
         }
         r[26] = targetDepth;
         r[28] = data.depthBias / 50.0F / 300.0F;
         r[32] = Float.intBitsToFloat(base / 16); // in matrices from the frame's palette start (palettes are whole matrices)
         drawFloats.put(f, r);
         ModelScript ms = mi.modelScript;
         drawCull[k] = ms == null || ms.cullFace == -1 ? GL11.GL_FRONT : ms.cullFace;
         drawTex[k] = tex;
         drawStatic[k] = model.isStatic;
         drawCharStart[k] = first;
         drawVb[k] = model.mesh.vb; // its vertex array object is made inside the flush's attribute bracket
         drawCount[k] = Model.pzoptMeshElements(model.mesh.vb);
         drawState[k] = first || checkFrame && (checkVariant & 2) != 0 ? (byte)3 : later; // (dev check variant 2: every instance as the first) // the first: depth mask from the camera (on), blend on, scissor off
         first = false;
         frameDraws++;
         pending++;
         statDraws++;
      }
      palCount = 0;
      java.util.Arrays.fill(palSrc, null);
      if (checkFrame) {
         runInfo.append('[').append(slot.character.getID()).append(" a=").append(slot.alpha).append(" n=").append(modelData.size())
            .append(" ready=").append(modelData == slot.modelData).append(" later=").append(later).append(" draws=").append(frameDraws - drawsAtStart)
            .append(" xyz=").append(slot.x).append(',').append(slot.y).append(',').append(slot.z).append(" mode=").append(pendingMode).append("] ");
      }
   }

   private static final int[] palStarts = new int[64];
   private static final int[] palLens = new int[64];
   private static int palCount;

   /** An earlier palette of this character with the same floats (clothes skinned to the body share it). */
   private static int findPalette(FloatBuffer pal, int p0, int len) {
      for (int j = 0; j < palCount; j++) {
         if (palLens[j] != len) {
            continue;
         }
         FloatBuffer o = palSrc[j];
         int op = palSrcPos[j];
         boolean same = true;
         for (int t = 0; t < len; t++) {
            if (Float.floatToRawIntBits(o.get(op + t)) != Float.floatToRawIntBits(pal.get(p0 + t))) {
               same = false;
               break;
            }
         }
         if (same) {
            return palStarts[j];
         }
      }
      return -1;
   }

   private static final FloatBuffer[] palSrc = new FloatBuffer[64];
   private static final int[] palSrcPos = new int[64];

   // ---- GL ----
   private static final IdentityHashMap<Object, int[]> VAOS = new IdentityHashMap<>();

   private static int vao(VertexBufferObject vb) {
      Object h = Model.pzoptMeshHandle(vb);
      int[] e = VAOS.get(h);
      int vbo = Model.pzoptMeshVbo(vb);
      int ebo = Model.pzoptMeshEbo(vb);
      if (e != null && e[1] == vbo && e[2] == ebo) {
         return e[0];
      }
      if (VAOS.size() > 4096) {
         for (int[] old : VAOS.values()) {
            GL30.glDeleteVertexArrays(old[0]);
         }
         VAOS.clear();
      }
      int id = vaoName();
      GL30.glBindVertexArray(id);
      Model.pzoptMeshPointers(vb); // VertexBufferObject.BeginDraw's pointers and enables, into this array object
      // the draw index: attribute 15 over a buffer of 0, 1, 2... advanced once per instance, so a draw's base instance is
      // its record (no uniform per draw)
      GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, drawIdBuffer);
      GL30.glVertexAttribIPointer(15, 1, GL11.GL_INT, 4, 0L);
      GL20.glEnableVertexAttribArray(15);
      org.lwjgl.opengl.GL33.glVertexAttribDivisor(15, 1);
      GL30.glBindVertexArray(0);
      GL13.glActiveTexture(GL13.GL_TEXTURE0); // BeginDraw selects a unit per texture-coordinate set; the binds stay on unit 0
      VAOS.put(h, new int[]{id, vbo, ebo});
      return id;
   }

   // vertex array names come from a pool filled a thousand at a time: glGenVertexArrays returns a name, which on a
   // threaded driver waits for the driver thread (a new mesh in a crowd coming into view was a 10-15 ms wait)
   private static final int[] VAO_POOL = new int[1024];
   private static int vaoPoolLeft;

   private static int vaoName() {
      if (vaoPoolLeft == 0) {
         GL30.glGenVertexArrays(VAO_POOL);
         vaoPoolLeft = VAO_POOL.length;
      }
      return VAO_POOL[--vaoPoolLeft];
   }

   private static boolean initGl() {
      if (program != 0) {
         return true;
      }
      if (initTried) {
         return false;
      }
      initTried = true;
      try {
         GLCapabilities caps = GL.getCapabilities();
         if (!caps.OpenGL43 || Core.getInstance().getUseOpenGL21() || CoreGl.legacyMac() || CoreGl.active) {
            Log.info("horde batch: needs GL 4.3 storage buffers; the stock model draws stay");
            failed = true;
            return false;
         }
         Shader sh = ShaderManager.instance.getOrCreateShader("pzopt_horde", false, false);
         if (sh == null || sh.getShaderProgram() == null || !sh.getShaderProgram().isCompiled()) {
            Log.warn("horde batch: pzopt_horde did not compile; the stock model draws stay");
            failed = true;
            return false;
         }
         vaoName(); // the name pool's first fill here, not in a frame
         vaoPoolLeft++;
         program = sh.getShaderProgram().getShaderID();
         locDraw = GL20.glGetUniformLocation(program, "pzoptDrawIndex");
         Shader st = ShaderManager.instance.getOrCreateShader("pzopt_horde", true, false);
         if (st != null && st.getShaderProgram() != null && st.getShaderProgram().isCompiled()) {
            programStatic = st.getShaderProgram().getShaderID();
            locDrawStatic = GL20.glGetUniformLocation(programStatic, "pzoptDrawIndex");
            int lt = GL20.glGetUniformLocation(programStatic, "Texture");
            ShaderHelper.glUseProgramObjectARB(programStatic);
            if (lt >= 0) {
               GL20.glUniform1i(lt, 0);
            }
            ShaderHelper.glUseProgramObjectARB(SceneShaderStore.defaultShaderId);
         }
         if (programStatic == 0) {
            programStatic = 0;
            staticFailed = true;
            Log.warn("horde batch: pzopt_horde_static did not compile; zombies with attached items stay stock");
         }
         int locTex = GL20.glGetUniformLocation(program, "Texture");
         for (int b = 0; b < SETS; b++) {
            ssboDrawSets[b] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssboDrawSets[b]);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long)MAX_DRAWS * RECORD_BYTES, GL15.GL_STREAM_DRAW);
            ssboPaletteSets[b] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssboPaletteSets[b]);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, (long)MAX_PALETTE_FLOATS * 4L, GL15.GL_STREAM_DRAW);
         }
         GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
         stageBytes = ByteBuffer.allocateDirect(MAX_DRAWS * RECORD_BYTES).order(ByteOrder.nativeOrder());
         stagePalette = ByteBuffer.allocateDirect(MAX_PALETTE_FLOATS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
         if (caps.OpenGL44 && !"subdata".equalsIgnoreCase(Config.HORDE_BATCH_UPLOAD)) {
            // persistent, coherent, write-only mappings: the records go straight where the shaders read them, no copy
            int flags = GL30.GL_MAP_WRITE_BIT | org.lwjgl.opengl.GL44.GL_MAP_PERSISTENT_BIT | org.lwjgl.opengl.GL44.GL_MAP_COHERENT_BIT;
            for (int b = 0; b < MSETS; b++) {
               mapDrawIds[b] = GL15.glGenBuffers();
               GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, mapDrawIds[b]);
               org.lwjgl.opengl.GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER, (long)MAX_DRAWS * RECORD_BYTES, flags);
               mapDraw[b] = GL30.glMapBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 0L, (long)MAX_DRAWS * RECORD_BYTES, flags).order(ByteOrder.nativeOrder());
               mapPalIds[b] = GL15.glGenBuffers();
               GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, mapPalIds[b]);
               org.lwjgl.opengl.GL44.glBufferStorage(GL43.GL_SHADER_STORAGE_BUFFER, (long)MAX_PALETTE_FLOATS * 4L, flags);
               mapPal[b] = GL30.glMapBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, 0L, (long)MAX_PALETTE_FLOATS * 4L, flags).order(ByteOrder.nativeOrder()).asFloatBuffer();
            }
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
            mappedOk = true;
         }
         pointBuffers();
         IntBuffer ids = ByteBuffer.allocateDirect(MAX_DRAWS * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
         for (int i = 0; i < MAX_DRAWS; i++) {
            ids.put(i, i);
         }
         drawIdBuffer = GL15.glGenBuffers();
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, drawIdBuffer);
         GL15.glBufferData(GL15.GL_ARRAY_BUFFER, ids, GL15.GL_STATIC_DRAW);
         GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
         SpriteRenderer.ringBuffer.restoreVbos = true; // the sprite ring buffer binds its own buffers again
         ShaderHelper.glUseProgramObjectARB(program);
         if (locTex >= 0) {
            GL20.glUniform1i(locTex, 0);
         }
         ShaderHelper.glUseProgramObjectARB(SceneShaderStore.defaultShaderId);
         Log.info("horde batch: on, mode " + Config.HORDE_BATCH + ", program " + program);
         return true;
      } catch (RuntimeException e) {
         Log.warn("horde batch: init failed (" + e + "); the stock model draws stay");
         failed = true;
         program = 0;
         return false;
      }
   }

   /** The record / palette views this frame writes: the mapped set, or the staging copies glBufferSubData sends. */
   private static void pointBuffers() {
      if (useMapped) {
         drawBytes = mapDraw[mset];
         palette = mapPal[mset];
      } else {
         drawBytes = stageBytes;
         palette = stagePalette;
      }
      drawFloats = drawBytes.asFloatBuffer();
      drawInts = drawBytes.asIntBuffer();
   }

   /** Render thread: draw the pending run. */
   public static void flush() {
      int n = pending;
      if (n == 0) {
         return;
      }
      pending = 0;
      int first = runStart;
      int palFirst = runPaletteStart;
      // the run's records and palettes into this frame's buffer pair (three pairs in turn, one a frame), indexed from the
      // frame's start: a record's index is its draw index, a palette base counts matrices from the frame's palette start
      int ssboDraws = useMapped ? mapDrawIds[mset] : ssboDrawSets[set];
      int ssboPalette = useMapped ? mapPalIds[mset] : ssboPaletteSets[set];
      if (!useMapped) {
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssboDraws);
      drawBytes.limit((first + n) * RECORD_BYTES).position(first * RECORD_BYTES);
      GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, (long)first * RECORD_BYTES, drawBytes);
      drawBytes.clear();
      if (framePalette > palFirst) {
         GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, ssboPalette);
         palette.limit(framePalette).position(palFirst);
         GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, (long)palFirst * 4L, palette);
         palette.clear();
      }
      GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
      }
      boolean check = checkFrame && !runCmds.isEmpty();
      if (check) {
         checkSave();
         checkFirst = first;
         checkCount = n;
         checkDrawSet = ssboDraws;
         checkPalSet = ssboPalette;
      }
      GpuSections.markNow("horde.batch", false); // (gpuSections: the batch's own GPU time, nested in moving)
      GL11.glPushClientAttrib(-1);
      GL11.glPushAttrib(1048575);
      GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 12, ssboDraws);
      GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 13, ssboPalette);
      // performRenderCharacter + DrawChar's states
      GL11.glEnable(GL11.GL_BLEND);
      GL11.glBlendFunc(770, 771);
      GL11.glEnable(GL11.GL_ALPHA_TEST);
      GL11.glAlphaFunc(516, 0.01F);
      GL11.glEnable(GL11.GL_DEPTH_TEST);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      GL11.glDepthFunc(513);
      GL11.glDepthRange(-10.0, 10.0);
      GL11.glDepthMask(true);
      boolean dm = true, blend = true, scissor = false;
      int cull = -2;
      DefaultShader.isActive = false;
      int progBound = -1;
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
      GL11.glEnable(GL11.GL_TEXTURE_2D);
      Texture.lastTextureID = -1; // what unit 0 holds is not known here: the first bind is a real one (stock: the previous character's tail did this)
      int vaoBound = -1;
      for (int k = first; k < first + n; k++) {
         byte st = drawState[k];
         boolean wdm = (st & 1) != 0, wbl = (st & 2) != 0, wsc = (st & 4) != 0;
         if (wdm != dm) {
            GL11.glDepthMask(wdm);
            dm = wdm;
         }
         if (wbl != blend) {
            if (wbl) {
               GL11.glEnable(GL11.GL_BLEND);
            } else {
               GL11.glDisable(GL11.GL_BLEND);
            }
            blend = wbl;
         }
         if (wsc != scissor) {
            if (wsc) {
               GL11.glEnable(GL11.GL_SCISSOR_TEST);
            } else {
               GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
            scissor = wsc;
         }
         if (drawCharStart[k] && k != first && (checkVariant & 4) != 0 && checkFrame) {
            // dev check variant 4: every character starts from the batch's opening state, as after a flush
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(770, 771);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(516, 0.01F);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDepthFunc(513);
            GL11.glDepthMask(true);
            dm = true;
            blend = true;
            scissor = false;
            cull = -2;
            progBound = -1;
            vaoBound = -1;
            Texture.lastTextureID = -1;
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
         }
         int cf = drawCull[k];
         if (cf != cull) {
            if (cf == 0) {
               GL11.glDisable(GL11.GL_CULL_FACE);
            } else {
               GL11.glEnable(GL11.GL_CULL_FACE);
               GL11.glCullFace(cf);
            }
            cull = cf;
         }
         if (HordeDraw.mode != 8 || k == first) {
            drawTex[k].bind(); // (dev mode 8: one texture for the whole run, the cost of the binds)
         }
         if (!checkFrame) {
            drawTex[k] = null;
         }
         VertexBufferObject vbk = HordeDraw.mode == 8 ? firstVb : drawVb[k]; // (dev mode 8: one mesh for the run as well)
         if (k == first) {
            firstVb = vbk;
         }
         if (!checkFrame) {
            drawVb[k] = null;
         }
         if (vbk == null || !Model.pzoptMeshDrawable(vbk)) {
            skippedDraws++; // its buffers went away since the offer (the stock draw would have skipped it too)
            continue;
         }
         int v = vao(vbk);
         if (v != vaoBound) {
            GL30.glBindVertexArray(v);
            vaoBound = v;
         }
         int prog = drawStatic[k] ? programStatic : program;
         if (prog != progBound) {
            ShaderHelper.glUseProgramObjectARB(prog);
            progBound = prog;
         }
         if (HordeDraw.mode != 7) {
            org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseInstance(GL11.GL_TRIANGLES, Model.pzoptMeshElements(vbk), GL11.GL_UNSIGNED_INT, 0L, 1, k); // (dev mode 7: no draw call, the cost of the rest)
         }
      }
      GL30.glBindVertexArray(0);
      ShaderHelper.glUseProgramObjectARB(SceneShaderStore.defaultShaderId);
      GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 12, 0);
      GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 13, 0);
      GL11.glDepthRange(0.0, 1.0);
      GL11.glPopAttrib();
      GL11.glPopClientAttrib();
      // performRenderCharacter's tail
      Texture.lastTextureID = -1;
      GL11.glEnable(3553);
      SpriteRenderer.ringBuffer.restoreVbos = true;
      GL11.glDisable(2929);
      GL11.glEnable(3042);
      GL11.glEnable(3008);
      GL11.glAlphaFunc(516, 0.0F);
      GLStateRenderThread.restore();
      GpuSections.markNow("horde.batch", true);
      statFlushes++;
      runStart = frameDraws;
      runPaletteStart = framePalette;
      if (check) {
         long t0 = System.nanoTime();
         checkCompare();
         checkFrame = false; // one checked run a check frame (a full-frame readback twice: ~0.1-0.2 s)
         checkLastMs = (System.nanoTime() - t0) / 1_000_000L;
      }
   }

   // ---- dev check (devHordeCheck=N): every N-th frame each run is drawn both ways and the framebuffers compared ----
   private static boolean checkFrame;
   private static int checkFirst;
   private static int checkCount;
   private static int checkDrawSet;
   private static int checkPalSet;
   private static int checkVariant;
   private static long checkIndex;
   private static final int[] VARIANTS = {0, 1, 4, 8};
   private static final long[] VAR_RUNS = new long[16];
   private static final long[] VAR_DIFF = new long[16];
   private static long checkLastMs;
   private static boolean checkDisabled;
   private static long checkRuns;
   private static long checkDrawn; // pixels the checked runs changed (the restore took them away again)
   private static long checkVacuous; // checked runs that changed nothing
   private static long checkPixels;
   private static long checkColour;
   private static long checkDepth;
   private static float checkMaxDelta;
   private static int chkFbo;
   private static int chkColor;
   private static int chkDepth;
   private static int chkW;
   private static int chkH;
   private static int chkColorFmt;
   private static int chkDepthFmt;
   private static int srcFbo;
   private static ByteBuffer readA;
   private static ByteBuffer readB;
   private static FloatBuffer depthA;
   private static FloatBuffer depthB;

   private static void checkSave() {
      srcFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
      if (srcFbo == 0) {
         runCmds.clear();
         runCams.clear();
      runInfo.setLength(0);
         return;
      }
      int cType = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
      int cName = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
      int dName = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
      int dType = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
      if (cType != GL11.GL_TEXTURE || dType != GL11.GL_TEXTURE) {
         Log.info("horde batch check: framebuffer attachments are not textures (" + cType + "/" + dType + "); no check");
         runCmds.clear();
         runCams.clear();
      runInfo.setLength(0);
         return;
      }
      int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, cName);
      int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
      int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
      int cf = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, dName);
      int df = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
      if (cf == GL11.GL_RGBA) {
         cf = GL11.GL_RGBA8; // an unsized internal format reads back as such; glTexStorage needs the sized one
      } else if (cf == GL11.GL_RGB) {
         cf = GL11.GL_RGB8;
      }
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
      if (chkFbo == 0 || w != chkW || h != chkH || cf != chkColorFmt || df != chkDepthFmt) {
         if (chkFbo != 0) {
            GL30.glDeleteFramebuffers(chkFbo);
            GL11.glDeleteTextures(chkColor);
            GL11.glDeleteTextures(chkDepth);
         }
         chkW = w;
         chkH = h;
         chkColorFmt = cf;
         chkDepthFmt = df;
         chkColor = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, chkColor);
         org.lwjgl.opengl.GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, cf, w, h);
         chkDepth = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, chkDepth);
         org.lwjgl.opengl.GL42.glTexStorage2D(GL11.GL_TEXTURE_2D, 1, df, w, h);
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
         int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
         chkFbo = GL30.glGenFramebuffers();
         GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, chkFbo);
         GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, chkColor, 0);
         boolean ds = df == GL30.GL_DEPTH24_STENCIL8 || df == GL30.GL_DEPTH32F_STENCIL8;
         GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, ds ? GL30.GL_DEPTH_STENCIL_ATTACHMENT : GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, chkDepth, 0);
         int status = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);
         GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
         if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            Log.warn("horde batch check: scratch framebuffer incomplete (0x" + Integer.toHexString(status) + "); the check is off");
            checkDisabled = true;
            chkW = -1;
            runCmds.clear();
            runCams.clear();
      runInfo.setLength(0);
            srcFbo = 0;
            return;
         }
         readA = ByteBuffer.allocateDirect(w * h * 4);
         readB = ByteBuffer.allocateDirect(w * h * 4);
         depthA = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
         depthB = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
         Log.info("horde batch check: " + w + "x" + h + " colour 0x" + Integer.toHexString(cf) + " depth 0x" + Integer.toHexString(df));
      }
      blit(srcFbo, chkFbo);
   }

   private static void blit(int from, int to) {
      int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
      int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
      boolean sc = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
      GL11.glDisable(GL11.GL_SCISSOR_TEST);
      boolean dsMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
      GL11.glDepthMask(true);
      int stencilMask = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
      GL11.glStencilMask(0xFF);
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from);
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to);
      GL30.glBlitFramebuffer(0, 0, chkW, chkH, 0, 0, chkW, chkH, GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT, GL11.GL_NEAREST);
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
      GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
      GL11.glStencilMask(stencilMask);
      GL11.glDepthMask(dsMask);
      if (sc) {
         GL11.glEnable(GL11.GL_SCISSOR_TEST);
      }
   }

   private static void read(ByteBuffer colour, FloatBuffer depth) {
      int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
      int prevPack = GL11.glGetInteger(GL21_PIXEL_PACK_BUFFER_BINDING);
      GL15.glBindBuffer(GL21_PIXEL_PACK_BUFFER, 0);
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, srcFbo);
      GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
      colour.clear();
      GL11.glReadPixels(0, 0, chkW, chkH, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, colour);
      depth.clear();
      GL11.glReadPixels(0, 0, chkW, chkH, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, depth);
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
      GL15.glBindBuffer(GL21_PIXEL_PACK_BUFFER, prevPack);
   }

   /** Dev check: which stock character draw changes the first mismatching pixel's neighbourhood (replayed one by one). */
   private static void localize(int px, int py) {
      int x0 = Math.max(0, px - 8), y0 = Math.max(0, py - 8);
      int w = Math.min(17, chkW - x0), h = Math.min(17, chkH - y0);
      blit(chkFbo, srcFbo);
      ByteBuffer prev = ByteBuffer.allocateDirect(w * h * 8);
      ByteBuffer cur = ByteBuffer.allocateDirect(w * h * 8);
      readBox(prev, x0, y0, w, h);
      ModelCamera prevCamera = ModelCamera.instance;
      ModelCamera cam = CharacterModelCamera.instance;
      float sx = cam.x, sy = cam.y, sz = cam.z, sa = cam.useAngle;
      boolean sw = cam.useWorldIso, sv = cam.inVehicle;
      StringBuilder sb = new StringBuilder("horde batch check: stock draws touching ").append(px).append(',').append(py).append(':');
      for (int i = 0; i < runCmds.size(); i++) {
         float[] c = runCams.get(i);
         ModelCamera.instance = cam; // the camera object of the character's own record (a later record may have switched it)
         cam.x = c[0];
         cam.y = c[1];
         cam.z = c[2];
         cam.useAngle = c[3];
         cam.useWorldIso = c[4] != 0.0F;
         cam.inVehicle = c[5] != 0.0F;
         TextureDraw t = runCmds.get(i);
         synchronized (t.drawer) {
            t.drawer.render(t);
         }
         readBox(cur, x0, y0, w, h);
         if (!cur.equals(prev)) {
            ModelSlotRenderData slot = (ModelSlotRenderData)t.drawer;
            sb.append(" #").append(i).append(" id=").append(slot.character.getID()).append(" a=").append(slot.alpha).append(" {");
            ModelInstanceRenderDataList l = slot.modelData;
            for (int k = 0; k < l.size(); k++) {
               ModelInstanceRenderData d = (ModelInstanceRenderData)l.get(k);
               Texture tex = d.tex != null ? d.tex : d.model.tex;
               ModelScript ms = d.modelInstance.modelScript;
               sb.append(d.model.isStatic ? "S:" : "K:").append(d.model.name).append('/').append(tex == null ? "null" : tex.getName())
                  .append(" cull=").append(ms == null ? "def" : String.valueOf(ms.cullFace)).append(" parent=").append(d.modelInstance.parent != null)
                  .append(" ign=").append(d.ignoreLighting).append("; ");
            }
            sb.append('}');
            ByteBuffer tmp = prev;
            prev = cur;
            cur = tmp;
         }
      }
      cam.x = sx;
      cam.y = sy;
      cam.z = sz;
      cam.useAngle = sa;
      // the batch side: the run's draws one by one on the pre-run picture, each with the batch's full state
      blit(chkFbo, srcFbo);
      readBox(prev, x0, y0, w, h);
      sb.append(" | batch draws touching it:");
      for (int k = checkFirst; k < checkFirst + checkCount; k++) {
         GL11.glPushClientAttrib(-1);
         GL11.glPushAttrib(1048575);
         GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 12, checkDrawSet);
         GL43.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 13, checkPalSet);
         GL11.glEnable(GL11.GL_BLEND);
         GL11.glBlendFunc(770, 771);
         GL11.glEnable(GL11.GL_ALPHA_TEST);
         GL11.glAlphaFunc(516, 0.01F);
         GL11.glEnable(GL11.GL_DEPTH_TEST);
         GL11.glDepthFunc(513);
         GL11.glDepthRange(-10.0, 10.0);
         byte st = drawState[k];
         GL11.glDepthMask((st & 1) != 0);
         if ((st & 2) != 0) {
            GL11.glEnable(GL11.GL_BLEND);
         } else {
            GL11.glDisable(GL11.GL_BLEND);
         }
         if ((st & 4) != 0) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
         } else {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
         }
         if (drawCull[k] == 0) {
            GL11.glDisable(GL11.GL_CULL_FACE);
         } else {
            GL11.glEnable(GL11.GL_CULL_FACE);
            GL11.glCullFace(drawCull[k]);
         }
         ShaderHelper.glUseProgramObjectARB(drawStatic[k] ? programStatic : program);
         GL13.glActiveTexture(GL13.GL_TEXTURE0);
         Texture.lastTextureID = -1;
         drawTex[k].bind();
         GL30.glBindVertexArray(vao(drawVb[k]));
         org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseInstance(GL11.GL_TRIANGLES, drawCount[k], GL11.GL_UNSIGNED_INT, 0L, 1, k);
         GL30.glBindVertexArray(0);
         ShaderHelper.glUseProgramObjectARB(SceneShaderStore.defaultShaderId);
         GL11.glPopAttrib();
         GL11.glPopClientAttrib();
         Texture.lastTextureID = -1;
         readBox(cur, x0, y0, w, h);
         if (!cur.equals(prev)) {
            sb.append(" k=").append(k - checkFirst).append(drawStatic[k] ? " S " : " K ").append(drawTex[k].getName()).append(" cull=").append(drawCull[k])
               .append(" st=").append(drawState[k]).append(" pal=").append(Float.floatToRawIntBits(drawFloatsAt(k * FLOATS + 32))).append(" tgtDepth=").append(drawFloatsAt(k * FLOATS + 26))
               .append(" uv=").append(drawFloatsAt(k * FLOATS + 24)).append(',').append(drawFloatsAt(k * FLOATS + 25)).append(';');
            ByteBuffer tmp = prev;
            prev = cur;
            cur = tmp;
         }
      }
      SpriteRenderer.ringBuffer.restoreVbos = true;
      // the stock picture again for the rest of the frame
      blit(chkFbo, srcFbo);
      for (int i = 0; i < runCmds.size(); i++) {
         float[] c = runCams.get(i);
         ModelCamera.instance = cam; // the camera object of the character's own record (a later record may have switched it)
         cam.x = c[0];
         cam.y = c[1];
         cam.z = c[2];
         cam.useAngle = c[3];
         cam.useWorldIso = c[4] != 0.0F;
         cam.inVehicle = c[5] != 0.0F;
         TextureDraw t = runCmds.get(i);
         synchronized (t.drawer) {
            t.drawer.render(t);
         }
      }
      cam.x = sx;
      cam.y = sy;
      cam.z = sz;
      cam.useAngle = sa;
      cam.useWorldIso = sw;
      cam.inVehicle = sv;
      ModelCamera.instance = prevCamera;
      Log.info(sb.toString());
   }

   private static float drawFloatsAt(int i) {
      return drawFloats.get(i);
   }

   private static void readBox(ByteBuffer out, int x0, int y0, int w, int h) {
      int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, srcFbo);
      GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
      out.clear();
      out.limit(w * h * 4);
      GL11.glReadPixels(x0, y0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
      out.limit(w * h * 8).position(w * h * 4);
      GL11.glReadPixels(x0, y0, w, h, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, out.slice());
      out.clear();
      GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
   }

   private static final int GL21_PIXEL_PACK_BUFFER = 0x88EB;
   private static final int GL21_PIXEL_PACK_BUFFER_BINDING = 0x88ED;

   /** After the batched run: read it, put the saved framebuffer back, draw the same characters the stock way, compare. */
   private static void checkCompare() {
      if (srcFbo == 0 || runCmds.isEmpty()) {
         runCmds.clear();
         runCams.clear();
      runInfo.setLength(0);
         return;
      }
      read(readB, depthB);
      blit(chkFbo, srcFbo);
      // the restore must have taken the batch's draws away again, or the comparison below proves nothing
      read(readA, depthA);
      long restored = 0L;
      int pxAll = chkW * chkH;
      for (int i = 0; i < pxAll; i++) {
         if (readA.getInt(i * 4) != readB.getInt(i * 4) || Float.floatToRawIntBits(depthA.get(i)) != Float.floatToRawIntBits(depthB.get(i))) {
            restored++;
         }
      }
      checkDrawn += restored;
      if (restored == 0L) {
         checkVacuous++;
      }
      ModelCamera prevCamera = ModelCamera.instance;
      ModelCamera cam = CharacterModelCamera.instance; // every batched character's camera (batchable() checks it)
      float sx = cam.x, sy = cam.y, sz = cam.z, sa = cam.useAngle;
      boolean sw = cam.useWorldIso, sv = cam.inVehicle;
      for (int i = 0; i < runCmds.size(); i++) {
         float[] c = runCams.get(i);
         ModelCamera.instance = cam; // the camera object of the character's own record (a later record may have switched it)
         cam.x = c[0];
         cam.y = c[1];
         cam.z = c[2];
         cam.useAngle = c[3];
         cam.useWorldIso = c[4] != 0.0F;
         cam.inVehicle = c[5] != 0.0F;
         TextureDraw t = runCmds.get(i);
         synchronized (t.drawer) {
            t.drawer.render(t);
         }
      }
      cam.x = sx;
      cam.y = sy;
      cam.z = sz;
      cam.useAngle = sa;
      cam.useWorldIso = sw;
      cam.inVehicle = sv;
      ModelCamera.instance = prevCamera;
      read(readA, depthA);
      long colourDiff = 0L;
      long depthDiff = 0L;
      int px = chkW * chkH;
      int bx0 = Integer.MAX_VALUE, by0 = Integer.MAX_VALUE, bx1 = -1, by1 = -1, sample = -1;
      for (int i = 0; i < px; i++) {
         if (readA.getInt(i * 4) != readB.getInt(i * 4) || Float.floatToRawIntBits(depthA.get(i)) != Float.floatToRawIntBits(depthB.get(i))) {
            int xx = i % chkW, yy = i / chkW;
            bx0 = Math.min(bx0, xx);
            by0 = Math.min(by0, yy);
            bx1 = Math.max(bx1, xx);
            by1 = Math.max(by1, yy);
            if (sample < 0) {
               sample = i;
            }
         }
         if (readA.getInt(i * 4) != readB.getInt(i * 4)) {
            colourDiff++;
            for (int ch = 0; ch < 4; ch++) {
               float d = Math.abs((readA.get(i * 4 + ch) & 0xFF) - (readB.get(i * 4 + ch) & 0xFF));
               if (d > checkMaxDelta) {
                  checkMaxDelta = d;
               }
            }
         }
         if (Float.floatToRawIntBits(depthA.get(i)) != Float.floatToRawIntBits(depthB.get(i))) {
            depthDiff++;
         }
      }
      checkRuns++;
      VAR_RUNS[checkVariant]++;
      VAR_DIFF[checkVariant] += colourDiff + depthDiff;
      checkPixels += px;
      checkColour += colourDiff;
      checkDepth += depthDiff;
      if (colourDiff != 0 || depthDiff != 0) {
         Log.info("horde batch check (variant " + checkVariant + "): run of " + runCmds.size() + " characters: " + colourDiff + " colour / " + depthDiff + " depth pixels differ"
            + " box " + bx0 + "," + by0 + "-" + bx1 + "," + by1 + " sample stock rgba=" + Integer.toHexString(readA.getInt(sample * 4)) + " depth=" + depthA.get(sample)
            + " batch rgba=" + Integer.toHexString(readB.getInt(sample * 4)) + " depth=" + depthB.get(sample) + " chars: " + runInfo);
         if (runCmds.size() <= 80) {
            localize(sample % chkW, sample / chkW);
         }
      }
      runCmds.clear();
      runCams.clear();
      runInfo.setLength(0);
   }
}
