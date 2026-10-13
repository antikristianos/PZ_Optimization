package pzopt;

/**
 * Within-run A/B of the game-thread offload keys (2026-09-27, {@code devGtAlternate}): from the first frame the keys listed
 * in {@code devGtAlternateKeys} switch off and on every {@code devGtAlternate} ms, so both halves share one run (the
 * scene, the clocks and other load drift alike) and {@code harness/gtab.py} splits the frames and the game-thread profile
 * by half. Keys not listed, or no alternation, read their Config value unchanged. The phase flips only at a frame start
 * ({@link #frame}, from {@code Pacing.stepStart}), so a frame never mixes the two paths.
 */
public final class GtAb {
   private GtAb() {
   }

   public static final int RENDER_PREP = 1; // renderPrepParallel
   public static final int PPL_PACK = 2; // pplPackParallel
   public static final int SCHED = 4; // schedulerClassifyParallel
   public static final int ANIMAL_LOS = 8; // animalLosFast
   public static final int WEATHER = 16; // weatherParticlesParallel
   public static final int ZOMBIE_UPDATE = 32; // entityUpdateParallel (PR #35 + the calm-state whitelist)
   public static final int BAKE_PREP = 64; // bakePrepParallel
   public static final int TORCH_NEAR = 128; // pplTorchNearChunk
   public static final int ZOMBIE_STATS = 256; // zombieStatsFold
   public static final int LOS_PREFETCH = 512; // losLightPrefetch
   public static final int VIS_POLY = 1024; // visPolyAsync
   public static final int AO_CONTEXT = 2048; // aoContextParallel
   public static final int TL_ORDER = 4096; // translucentOrderCache
   public static final int TORCH_SOURCE = 8192; // torchSource (an Enhancements key: the cost of the lens solve and its visuals)
   public static final int PROFILER_IDLE = 16384; // profilerIdleFast
   public static final int SIM_LOD = 32768; // zombieSimLodTiles (the extra distance steps)
   public static final int ANIMAL_SNAP = 65536; // animalLosSnapshot
   public static final int LOOT_DEFER = 131072; // lootDefer
   public static final int SPAWN_SPREAD = 262144; // zombieSpawnSpread
   public static final int SLACK_WORK = 524288; // slackWork
   public static final int MODEL_ADDS = 1048576; // zombieModelAddBudgetUs
   public static final int BAKE_GUARD = 2097152; // bakeTimeGuardPct
   public static final int POSTUPDATE = 4194304; // postupdateParallel (Louisville 120 plan B)
   public static final int TILE_RECORD = 8388608; // tileRecordParallel (Louisville 120 plan A)
   public static final int RAGDOLL_GATE = 16777216; // ragdollGate
   public static final int BODY_MODEL_CACHE = 33554432; // bodyModelCache

   private static final String[] NAMES = {"renderPrepParallel", "pplPackParallel", "schedulerClassifyParallel", "animalLosFast",
      "weatherParticlesParallel", "entityUpdateParallel", "bakePrepParallel", "pplTorchNearChunk", "zombieStatsFold", "losLightPrefetch", "visPolyAsync", "aoContextParallel", "translucentOrderCache", "torchSource", "profilerIdleFast", "zombieSimLod", "animalLosSnapshot", "lootDefer", "zombieSpawnSpread", "slackWork", "zombieModelAddBudget", "bakeTimeGuard", "postupdateParallel", "tileRecordParallel", "ragdollGate", "bodyModelCache"};

   private static final int MASK = parse(Config.DEV_GT_ALTERNATE_KEYS);
   private static long t0;
   private static volatile boolean off; // this frame runs the listed keys' old path

   private static int parse(String keys) {
      int m = 0;
      if (keys == null || keys.isEmpty()) {
         return 0;
      }
      for (String k : keys.split(",")) {
         k = k.trim();
         if (k.equals("all")) {
            return -1;
         }
         for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(k)) {
               m |= 1 << i;
            }
         }
      }
      return m;
   }

   private static final java.lang.management.ThreadMXBean THREADS = java.lang.management.ManagementFactory.getThreadMXBean();
   private static final java.lang.management.OperatingSystemMXBean OS = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
   private static long lastCpu, lastProc, lastWall;
   private static boolean lastOff;
   private static final StringBuilder LOG = new StringBuilder(1 << 16);
   private static java.io.Writer out;
   private static int rows;
   public static int zombieUpdates; // IsoZombie.update calls since the last frame start (game thread)

   /**
    * Game thread, every frame start: the phase of this frame, and one row of pzopt-gtab.out for the frame that just ended
    * (its phase, the game thread's CPU time, the process's CPU time and the wall time since the last frame start): what
    * a key moves off the game thread shows as game-thread CPU even when the frame time is bound elsewhere (the GPU, the
    * render thread), and the process column shows where the work went.
    */
   public static void frame() {
      if (Config.DEV_GT_ALTERNATE <= 0 || MASK == 0) {
         return;
      }
      long now = System.currentTimeMillis();
      if (t0 == 0L) {
         if (zombie.iso.IsoWorld.instance == null || zombie.iso.IsoWorld.instance.currentCell == null) {
            return; // from the first world frame
         }
         t0 = now;
         Log.info("gt ab: alternating every " + Config.DEV_GT_ALTERNATE + " ms from epoch_ms " + now + " (on first), keys " + Config.DEV_GT_ALTERNATE_KEYS);
      }
      long cpu = THREADS.getCurrentThreadCpuTime();
      long proc = OS instanceof com.sun.management.OperatingSystemMXBean sun ? sun.getProcessCpuTime() : 0L;
      long wall = System.nanoTime();
      if (lastWall != 0L) {
         LOG.append(now).append(' ').append(lastOff ? 0 : 1).append(' ').append(cpu - lastCpu).append(' ').append(proc - lastProc).append(' ').append(wall - lastWall).append(' ').append(zombieUpdates);
         for (int i = 0; i < SECTIONS; i++) {
            LOG.append(' ').append(SECTION_NS[i]);
         }
         LOG.append('\n');
         if (++rows % 250 == 0) {
            flush();
         }
      }
      zombieUpdates = 0;
      java.util.Arrays.fill(SECTION_NS, 0L);
      lastCpu = cpu;
      lastProc = proc;
      lastWall = wall;
      long period = (now - t0) / Config.DEV_GT_ALTERNATE;
      // ABBA (devGtAbba, default on): on, off, off, on, on, off, ... so a scene that grows heavier along the route weighs
      // both halves alike; plain alternation put every off half after its on half (an A/A placebo read 0.6 ms / 7 points
      // of misses "better" on, 2026-10-05)
      off = Config.DEV_GT_ABBA ? ((period + 1L) >> 1 & 1L) == 1L : (period & 1L) == 1L;
      lastOff = off;
   }

   private static void flush() {
      try {
         if (out == null) {
            java.io.File f = new java.io.File(zombie.ZomboidFileSystem.instance.getCacheDir(), "pzopt-gtab.out");
            out = new java.io.BufferedWriter(new java.io.FileWriter(f));
            out.write("# epoch_ms on(1)/off(0) game_thread_cpu_ns process_cpu_ns wall_ns zombie_updates, then ns per section: startFrame schedUpdate animalLos playerLos pplBeforeComposite renderMovingObjects performRenderTiles postupdate visPolyRenderMain aoFlush chunkMapUpdate popmanUpdate lightingUpdate logic finishAnimation renderInternal sceneCull atlases cellRender pu_loop pu_move pu_flush pu_zombies pu_moved pu_collided vzmUpdate chunkPos nativeUnload bake_t0 bake_t1 bake_t2 bake_t3 bake_offered tl_pass tl_record tl_splice  (per frame, devGtAlternate " + Config.DEV_GT_ALTERNATE + " ms, keys "
               + Config.DEV_GT_ALTERNATE_KEYS + ")\n");
         }
         out.write(LOG.toString());
         out.flush();
      } catch (java.io.IOException e) {
         Log.warn("gt ab: " + e);
      }
      LOG.setLength(0);
   }

   // ---- per-frame section timers (dev, only while an alternation runs): the ns each section took this frame, one column each ----
   public static final int S_START_FRAME = 0, S_SCHED_UPDATE = 1, S_ANIMAL_LOS = 2, S_PLAYER_LOS = 3, S_PPL = 4, S_MOVING = 5, S_TILES = 6, S_POSTUPDATE = 7;
   public static final int S_VISPOLY = 8, S_AO_FLUSH = 9, S_CHUNKMAP = 10, S_POPMAN = 11, S_LIGHTING = 12, S_LOGIC = 13, S_FINISH_ANIM = 14, S_RENDER = 15, S_CULL = 16, S_ATLAS = 17, S_CELL = 18;
   // Louisville 120 plan B: inside postupdate, the bucket loop, the zombies' IsoMovingObject.postupdate (timed on one zombie in
   // PU_SAMPLE, scaled), the ActionEval / AnimBatch flush; then counts, not ns: zombies through the movement, zombies whose
   // square changed, zombies that collided
   public static final int S_PU_LOOP = 19, S_PU_MOVE = 20, S_PU_FLUSH = 21, C_PU_ZOMBIES = 22, C_PU_MOVED = 23, C_PU_COLLIDED = 24;
   public static final int PU_SAMPLE = 8;
   // Louisville 120 item 3: VirtualZombieManager.update (zombie removal / reuse), IsoChunkMap.ProcessChunkPos (the grid shift and chunk unloads)
   public static final int S_VZM = 25, S_CHUNK_POS = 26;
   // Louisville 120 item 3: the popman native's chunk unload call; bakes granted this frame by tier (counts: must / level-change
   // burst, arrival quota, overdue, normal) and offered
   public static final int S_NATIVE_UNLOAD = 27, C_BAKE_T0 = 28, C_BAKE_T1 = 29, C_BAKE_T2 = 30, C_BAKE_T3 = 31, C_BAKE_OFFERED = 32;
   public static final int S_TL_PASS = 33, S_TL_RECORD = 34, S_TL_SPLICE = 35; // tileRecordParallel: the translucent passes (either path), recording, splicing
   private static final int SECTIONS = 36;
   private static final long[] SECTION_NS = new long[SECTIONS];
   public static final boolean TIMING = Config.DEV_GT_ALTERNATE > 0 && MASK != 0;

   /** Game thread: a section starts (0 when no alternation runs). */
   public static long begin() {
      return TIMING ? System.nanoTime() : 0L;
   }

   /** Game thread: the section that began at {@code t} ends. */
   public static void end(int section, long t) {
      if (t != 0L) {
         SECTION_NS[section] += System.nanoTime() - t;
      }
   }

   /** Game thread: add a count (or scaled ns) to a column of this frame. */
   public static void add(int section, long v) {
      SECTION_NS[section] += v;
   }

   /** False in the off half of an alternation for a listed key: the caller takes its old path this frame. */
   public static boolean on(int bit) {
      return !off || (MASK & bit) == 0;
   }
}
