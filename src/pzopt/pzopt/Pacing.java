package pzopt;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLCapabilities;
import zombie.ZomboidFileSystem;

/**
 * Frame pacing for variable refresh rate displays.
 *
 * On a VRR display every frame is shown the moment it is ready, so the present cadence is the display cadence.
 * The frame limiter (GameWindow.mainThreadStep) paces the start of the game-thread step, i.e. the game time each
 * frame shows; the frame then reaches the screen after its own update + hand-off + render + GPU time, which varies
 * frame to frame. Motion on screen is smooth when the gap between two flips equals the game-time step between the
 * two frames. On a fixed-refresh display that variation hides behind the vsync grid; a VRR display shows it as is
 * (desktop, 100 fps cap, VRR on: steps 10.00 ms apart with 0.26 ms jitter, flips 3.1 ms off their step on average,
 * 54 % of frames off by more than 2 ms; harness/pacing.py "ON-SCREEN JUDDER").
 *
 * {@link #beforeSwap()} holds the swap until {@code step start + L}, L being a high percentile
 * ({@code presentPacingPct}) of how long recent frames took from step start to "ready":
 * <ul>
 * <li>{@code cpu}: ready = the render thread reached the swap. The GPU may still be drawing, so the flip then waits
 * for it: the flip lands 0.5-8 ms after the swap.</li>
 * <li>{@code gpu}: ready = the GPU finished the frame, read back a few frames later from a GL_TIMESTAMP query mapped
 * onto System.nanoTime; the render thread never blocks on the GPU. An on-time frame is complete when its swap is
 * issued, so it flips a fixed ~0.45 ms later.</li>
 * <li>{@code gpufinish}: the render thread waits for the frame's fence, then holds. Exact, but the render thread no
 * longer overlaps the GPU: at 100 fps it fell to 84 fps and the queue fed back into L (34 ms step-to-screen).</li>
 * </ul>
 * Frames later than L go as soon as ready. The hold never makes the render thread spend more than one cap interval
 * on a frame (it would fall behind the game thread and frames would queue), and there is no hold when uncapped.
 *
 * The same hooks log one row per presented frame to Zomboid/pzopt-pacing.out ({@code pacingLog}, on in instrumented
 * runs; harness/pacing.py): epoch_ms, step start, render-thread acquire, swap call, swap return, the hold and the
 * frame's GPU completion (System.nanoTime; rows are written {@link #LAG_ROWS} frames late so the GPU time is known).
 *
 * Threads: {@link #stepStart} and {@link #onPush} on the game thread, the rest on the render thread. Every pushed
 * frame is rendered (pushFrameDown waits for the ready slot), so a single-producer single-consumer ring of step
 * starts pairs each rendered frame with the game time it was built from.
 */
public final class Pacing {
   public static final boolean LOG = Config.PACING_LOG && Overrides.enabled();
   static final int OFF = 0, CPU = 1, GPU = 2, GPU_FINISH = 3, AUTO = 4;
   public static final int MODE = !Overrides.enabled() ? OFF : switch (Config.PRESENT_PACING.toLowerCase(Locale.ROOT)) {
      case "cpu" -> CPU;
      case "gpu", "true", "on" -> GPU;
      case "gpufinish" -> GPU_FINISH;
      case "auto" -> AUTO;
      default -> OFF;
   };
   private static final String[] NAMES = {"off", "cpu", "gpu", "gpufinish", "auto"};
   /** frameQueue: the render thread presents each capped frame at its limiter slot + D (see {@link #queueHold}). */
   public static final boolean QUEUE = Config.FRAME_QUEUE && Overrides.enabled();
   /** limiterCatchUp: missed cap slots the limiter makes up (0 = stock modulo). */
   public static final int CATCH_UP = !Overrides.enabled() ? 0 : Math.max(0, Config.LIMITER_CATCH_UP);
   private static final boolean ACTIVE = LOG || MODE != OFF || QUEUE;

   private static final int RING = 64;
   private static final long[] ring = new long[RING];
   private static final long[] ringSlot = new long[RING]; // frameQueue: the limiter slot each pushed frame serves
   private static final long[] ringPush = new long[RING]; // frameQueue: when the game thread arrived at the hand-off
   private static volatile int head; // written by the game thread
   private static volatile int tail; // written by the render thread

   private static volatile long stepStartNs;
   private static volatile long stepSlotNs;
   /** Game thread: the limiter's frame interval in ns, 0 when uncapped. */
   private static volatile long capIntervalNs;

   // --- render thread -------------------------------------------------------------------------
   /** The configured mode after the GL checks (AUTO stays AUTO); {@link #effectiveMode()} resolves AUTO per frame. */
   private static int mode = MODE;
   /** What AUTO means on this GL: gpu with timestamp queries, else cpu. */
   private static int autoMode = GPU;
   private static long frameSimNs;
   private static long frameSlotNs;
   private static long framePushNs;
   private static long acquireNs;
   private static long swapCallNs;
   private static long waitNs;
   private static long frameNo;

   private static final int WINDOW = 120;
   private static final long[] lagWin = new long[WINDOW];
   private static final long[] sorted = new long[WINDOW];
   private static int lagCount;
   private static int lagPos;
   private static long targetLagNs;
   private static int framesSinceTarget;

   // GPU completion: one GL_TIMESTAMP query per frame, read back when available
   private static final int QUERIES = 8;
   private static int[] queryIds;
   private static final long[] queryFrame = new long[QUERIES];
   private static final long[] querySim = new long[QUERIES];
   private static boolean timestamps;
   // pacingQueryBuffer (tail2 pass 2026-10-13): the timestamps land in a persistently mapped buffer (GL_QUERY_BUFFER,
   // ARB_query_buffer_object) and are read from client memory, so the per-frame result polling no longer asks the driver
   // (each glGetQueryObject waited for NVIDIA's threaded driver queue: up to 9 ms render-thread stalls in texture-heavy frames)
   private static QueryBuf queryBuf;
   private static boolean glChecked;
   private static long gpuToCpuNs;
   private static long lastCalibrationNs;

   // log rows wait LAG_ROWS frames for their GPU completion time
   static final int LAG_ROWS = 10;
   private static final long[][] rows = new long[16][9];
   private static BufferedWriter log;
   private static boolean logFailed;
   private static long logStartNs;
   private static long logStartEpochMs;
   private static long lastFlushNs;
   private static final StringBuilder line = new StringBuilder(128);

   private Pacing() {
   }

   /** Game thread, at the start of a frame step (the time the frame's game state is built for). */
   public static void stepStart(long nowNs, long intervalNs) {
      stepStart(nowNs, intervalNs, nowNs);
   }

   /**
    * Game thread, at the start of a frame step; {@code slotNs} = the cap grid slot the step serves (the limiter's
    * deadline: nowNs when on time, earlier when the step starts late or catches up).
    */
   public static void stepStart(long nowNs, long intervalNs, long slotNs) {
      stepSlotNs = slotNs;
      GtAb.frame(); // devGtAlternate: this frame's A/B phase
      TorchSource.frameStart(); // devTorchSourceCycle: this frame's variant
      VisBlink.frame(); // devVisBlinkTrace: the last frame's visibility blinks
      AnimalPreload.drain(); // animalPreload (tail pass): preloaded action groups into the game's map
      stepStartNs = nowNs;
      capIntervalNs = intervalNs;
      if (Config.GC_SLACK_FIT && Config.GC_SLACK && intervalNs > 0L) {
         GcSlack.stepStart(); // gcSlackFit: a due collection at the step's start when the render thread's and this frame's room allow it
      }
   }

   /** Game thread: System.nanoTime() at the start of the current step (bakeDeadlinePct). */
   public static long stepStartNs() {
      return stepStartNs;
   }

   /** Game thread, right before SpriteRenderer.pushFrameDown: the frame being pushed shows this step's time. */
   public static void onPush() {
      if (!ACTIVE) {
         return;
      }
      lastWorkNs = System.nanoTime() - stepStartNs; // gcSlackFit: the step's own work before the hand-off
      int h = head;
      if (h - tail >= RING) {
         return; // render thread not consuming (no render state yet): drop
      }
      ring[h & (RING - 1)] = stepStartNs;
      ringSlot[h & (RING - 1)] = stepSlotNs;
      ringPush[h & (RING - 1)] = System.nanoTime();
      head = h + 1;
   }

   /** Render thread, after acquireStateForRendering returned a frame. */
   public static void onAcquire() {
      if (!ACTIVE) {
         return;
      }
      acquireNs = System.nanoTime();
      int t = tail;
      if (t != head) {
         frameSimNs = ring[t & (RING - 1)];
         frameSlotNs = ringSlot[t & (RING - 1)];
         framePushNs = ringPush[t & (RING - 1)];
         tail = t + 1;
      } else {
         frameSimNs = stepStartNs;
         frameSlotNs = stepSlotNs;
         framePushNs = acquireNs;
      }
      // gcSlackFit: the render thread's schedule for the game thread (when its next swap goes, how much render work is left)
      rtTargetNs = QUEUE && capIntervalNs > 0L && frameSlotNs != 0L && qTargetNs > 0L ? frameSlotNs + qTargetNs : 0L;
      rtAcquireNs = acquireNs;
   }

   // --- gcSlackFit (tail6 2026-10-14): the render thread's schedule, read by the game thread -----------------------------
   /** Render thread: when it took its current frame. */
   private static volatile long rtAcquireNs;
   /** Render thread: its current frame's swap target under frameQueue (slot + D), 0 = none (not held). */
   private static volatile long rtTargetNs;
   /** Render thread: when its current frame's submission ended (beforeSwap entered). */
   private static volatile long rtSubmitDoneNs;
   /** Render thread: when its last swap returned. */
   private static volatile long rtSwapRetNs;
   /** Render thread: acquire -> swap call, max(EWMA, 0.75 x decaying peak) (ns). */
   private static volatile long rtSubmitEstNs = 3_000_000L;
   private static double rtSubmitMean = 3_000_000.0, rtSubmitPeak = 3_000_000.0;

   // gcSlackFit's pause planner: the pause it chose (game thread)
   private static long planS, planE;

   /**
    * gcSlackFit, game thread: when a stop-the-world pause of {@code p} ns, preceded by {@code fill} ns of game-thread filler,
    * may start without delaying any swap of the frame queue or dropping a limiter slot; -1 when no start in reach does.
    * Candidates: right away (the pause ends before the render thread's next swap with its remaining submission done), or just
    * after that swap (the render thread then has a whole frame's room), if the caller can wait that long ({@code maxWait}: the
    * filler must start by now + maxWait). Each candidate is checked against: the frame the render thread holds (its swap at
    * slot + D), every frame already pushed (rendered one after the other from that swap, each done by its slot + D), and the step
    * the pause delays (at a step start: this one, {@code deadline} 0; in the slack: the next one, starting at max(deadline, pause
    * end)): its frame done by its slot + D after {@code work} ns of step and the render submission, and its end inside two cap
    * intervals of its slot (a longer step drops a limiter slot).
    */
   public static long planPause(long now, long fill, long p, long margin, long work, long deadline, long maxWait) {
      long d = qTargetNs;
      long interval = capIntervalNs;
      if (!QUEUE || d <= 0L || interval <= 0L) {
         return -1L;
      }
      planAfterAcquire = 0L;
      long s1 = now + fill;
      if (pauseOk(now, s1, s1 + p, margin, work, deadline, d, interval)) {
         return s1;
      }
      long a = rtAcquireNs;
      long tF = rtTargetNs;
      if (a != 0L && rtSwapRetNs < a && tF > now && tF - now <= maxWait) {
         // after the swap: the filler starts once the render thread's swap has returned (waitSwap), the pause after it
         long s2 = tF + 150_000L + fill;
         if (pauseOk(now, s2, s2 + p, margin, work, deadline, d, interval)) {
            planAfterAcquire = a;
            planSwapAt = tF;
            return s2;
         }
      }
      return -1L;
   }

   private static long planAfterAcquire, planSwapAt;

   static long submitEstUs() {
      return rtSubmitEstNs / 1000L;
   }

   static long queueTargetUs() {
      return qTargetNs / 1000L;
   }
   /** gcSlackFit: failed checks of the pause planner (no frame target, the held frame's swap, a pushed frame, the delayed step's two intervals, its frame). */
   public static final long[] planFail = new long[5];

   /**
    * gcSlackFit, game thread: when the planned pause comes after a swap, wait for it: park until just before the swap's target,
    * then until the render thread's swap has returned (at most 3 ms past the target). True when the swap was seen.
    */
   public static boolean waitPlannedSwap() {
      long a = planAfterAcquire;
      if (a == 0L) {
         return true;
      }
      planAfterAcquire = 0L;
      long t = planSwapAt;
      long w = t - 100_000L - System.nanoTime();
      if (w > 20_000L) {
         LockSupport.parkNanos(w);
      }
      long limit = t + 3_000_000L;
      while (rtSwapRetNs < a) {
         long now = System.nanoTime();
         if (now > limit) {
            return false;
         }
         Thread.onSpinWait();
      }
      return true;
   }

   /** The render thread's finish of a submission started at x, with the pause [s, e]. */
   private static long renderDone(long x, long sub, long s, long e) {
      if (e <= x || s >= x + sub) {
         return x + sub;
      }
      return s <= x ? e + sub : x + sub + (e - s);
   }

   private static boolean pauseOk(long now, long s, long e, long margin, long work, long deadline, long d, long interval) {
      long sub = rtSubmitEstNs;
      long a = rtAcquireNs;
      boolean hasF = a != 0L && rtSwapRetNs < a;
      long rt = now; // when the render thread is free for the next frame
      if (hasF) {
         long tF = rtTargetNs;
         if (tF == 0L) {
            planFail[0]++;
            return false; // presented as soon as it is ready: any pause delays it
         }
         if (s < tF) {
            long left = rtSubmitDoneNs >= a ? 0L : Math.max(300_000L, a + sub - s); // its submission still to do at the pause
            if (e + left > tF - margin) {
               planFail[1]++;
               return false;
            }
         }
         rt = Math.max(now, tF);
      }
      // the frames already pushed, rendered in order after that
      int h = head;
      for (int t = tail; t != h; t++) {
         long slot = ringSlot[t & (RING - 1)];
         if (slot == 0L) {
            planFail[0]++;
            return false;
         }
         long done = renderDone(rt, sub, s, e);
         if (done > slot + d - margin) {
            planFail[2]++;
            return false;
         }
         rt = Math.max(done, slot + d);
      }
      // the step the pause delays, and its frame
      long slot = deadline == 0L ? stepSlotNs : deadline;
      long start = deadline == 0L ? e : Math.max(deadline, e);
      long push = start + work;
      if (push > slot + 2L * interval - margin) {
         planFail[3]++;
         return false;
      }
      long done = renderDone(Math.max(rt, push), sub, s, e);
      if (done > slot + d - margin) {
         planFail[4]++;
         return false;
      }
      planS = s;
      planE = e;
      return true;
   }

   /** Game thread: the last step's time from its start to the frame hand-off (gcSlackFit). */
   static long lastWorkNs;

   /** gcSlackFit, game thread: the current step's cap slot (the limiter's deadline it serves). */
   static long stepSlotNs() {
      return stepSlotNs;
   }


   /** Render thread: the last frame's acquire -> swap-call time before any hold (pzopt.BakeScheduler's adaptive budget). */
   public static volatile long lastSubmitNs;

   /** Render thread, right before Display.update (the swap): optionally hold the present, then stamp the call. */
   public static void beforeSwap() {
      if (!ACTIVE) {
         return;
      }
      lastSubmitNs = System.nanoTime() - acquireNs;
      if (Config.GC_SLACK_FIT) { // gcSlackFit: the render thread's submission is done, its cost learned
         rtSubmitDoneNs = acquireNs + lastSubmitNs;
         double s = lastSubmitNs;
         rtSubmitMean += 0.1 * (s - rtSubmitMean);
         rtSubmitPeak = Math.max(rtSubmitPeak * 0.98, s);
         rtSubmitEstNs = (long)Math.max(rtSubmitMean, 0.75 * rtSubmitPeak);
      }
      if (!glChecked) {
         initGl();
      }
      long now = System.nanoTime();
      waitNs = 0L;
      long frame = frameNo++;
      long interval = capIntervalNs;
      int m = effectiveMode();
      if (m != lastMode) {
         lagCount = 0;
         targetLagNs = 0L;
         lastMode = m;
      }
      if (timestamps) {
         collectQueries(now);
         issueQuery(frame);
      }
      if (QUEUE && interval > 0L && frameSlotNs != 0L && GtAb.on(GtAb.FRAME_QUEUE)) { // (GtAb: devGtAlternateKeys=frameQueue alternates it within a run; the render thread reads the phase the game thread set for its current step)
         swapCallNs = queueHold(now, interval);
         return;
      }
      if (m == GPU_FINISH && interval > 0L) {
         long sync = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
         GL32.glClientWaitSync(sync, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 100_000_000L);
         GL32.glDeleteSync(sync);
         now = System.nanoTime();
      } else if (m != OFF && interval > 0L) {
         GL11.glFlush(); // the frame's commands reach the GPU now, not at the swap after the hold
      }
      if (m != OFF && frameSimNs != 0L && interval > 0L) {
         if (m == CPU || m == GPU_FINISH) {
            addLag(now - frameSimNs);
         }
         if (lagCount > 0) {
            if (++framesSinceTarget >= 15 || targetLagNs == 0L) {
               framesSinceTarget = 0;
               System.arraycopy(lagWin, 0, sorted, 0, lagCount);
               Arrays.sort(sorted, 0, lagCount);
               int idx = Math.min(lagCount - 1, lagCount * Config.PRESENT_PACING_PCT / 100);
               targetLagNs = sorted[idx] + Config.PRESENT_PACING_MARGIN_US * 1000L;
            }
            long target = frameSimNs + targetLagNs;
            // the render thread must be ready for the next frame within one interval of taking this one
            long guard = acquireNs + interval - 300_000L;
            if (target > guard) {
               target = guard;
            }
            if (target > now) {
               waitUntil(target);
               long after = System.nanoTime();
               waitNs = after - now;
               now = after;
            }
         }
      } else if (interval == 0L) {
         lagCount = 0;
         targetLagNs = 0L;
      }
      swapCallNs = now;
   }

   private static int lastMode = -1;

   // --- frameQueue (render thread) -------------------------------------------------------------
   private static final int Q_WINDOW = 240;
   private static final long[] qWin = new long[Q_WINDOW];
   private static final long[] qSorted = new long[Q_WINDOW];
   private static int qCount, qPos, qSince;
   private static long qTargetNs;
   private static long qFrames, qHeld, qLate, qHoldNsSum, qTargetNsSum, qLastLogNs;
   private static BufferedWriter qLog;
   private static long qFlushNs;

   /**
    * frameQueue: holds the swap of the frame acquired last until its limiter slot + D and returns the swap-call time.
    *
    * D is the {@code frameQueuePct} percentile (+ margin) of "slot -> ready" over the last {@link #Q_WINDOW} frames, where
    * ready = the game thread's arrival at the hand-off + this frame's render submit (acquire -> swap call). That is the time
    * the frame would have been ready had the render thread been free when it was handed over, so the hold itself (which
    * keeps the render thread busy and delays later acquires) never feeds back into D (presentPacing=gpu measured step start
    * -> GPU done: its own queueing raised its target, p90 27 ms at the Louisville cap, and its one-interval guard made the
    * render thread a stage of >= one interval per frame that could never catch up). On-time frames therefore reach the swap
    * exactly one cap interval apart, whatever their own game step took; a frame later than its slot + D goes at once.
    */
   private static long queueHold(long now, long interval) {
      GL11.glFlush(); // the frame's commands reach the GPU now: it is drawn during the hold
      long ready = framePushNs + (now - acquireNs);
      long lag = ready - frameSlotNs;
      if (lag > 0L && lag < 250_000_000L) {
         qWin[qPos] = lag;
         qPos = (qPos + 1) % Q_WINDOW;
         qCount = Math.min(Q_WINDOW, qCount + 1);
      }
      if (qCount > 0 && (++qSince >= 15 || qTargetNs == 0L)) {
         qSince = 0;
         System.arraycopy(qWin, 0, qSorted, 0, qCount);
         Arrays.sort(qSorted, 0, qCount);
         long d = qSorted[Math.min(qCount - 1, qCount * Config.FRAME_QUEUE_PCT / 100)] + Config.FRAME_QUEUE_MARGIN_US * 1000L;
         long lo = interval * Config.FRAME_QUEUE_MIN_PCT / 100L;
         long hi = interval * Config.FRAME_QUEUE_MAX_PCT / 100L;
         qTargetNs = Math.max(lo, Math.min(hi, d));
      }
      long target = frameSlotNs + qTargetNs;
      long latest = now + 3L * interval; // a bogus slot never parks the render thread for long
      if (target > latest) {
         target = latest;
      }
      waitNs = 0L;
      qFrames++;
      qTargetNsSum += qTargetNs;
      if (target > now) {
         waitUntil(target);
         long after = System.nanoTime();
         waitNs = after - now;
         now = after;
         qHeld++;
         qHoldNsSum += waitNs;
      } else if (now - target > 500_000L) {
         qLate++;
      }
      if (LOG) {
         queueLog(lag, target);
      }
      if (now - qLastLogNs > 10_000_000_000L) {
         if (qLastLogNs != 0L && qFrames > 0) {
            Log.info(String.format(Locale.ROOT, "frame queue: %d frames, D %.2f ms (p%d of slot -> ready, last %.2f), held %d (%.2f ms mean), late %d, catch-up slots %d, dropped slots %d",
                  qFrames, qTargetNsSum / 1e6 / qFrames, Config.FRAME_QUEUE_PCT, qTargetNs / 1e6, qHeld, qHeld > 0 ? qHoldNsSum / 1e6 / qHeld : 0.0, qLate,
                  catchUpSlots, droppedSlots));
         }
         qLastLogNs = now;
         qFrames = qHeld = qLate = qHoldNsSum = qTargetNsSum = 0L;
         catchUpSlots = droppedSlots = 0L;
      }
      return now;
   }

   /** pzopt-framequeue.out: sim_ns slot_ns push_ns lag_ns target_ns per frame (join with pzopt-pacing.out on sim_ns). */
   private static void queueLog(long lag, long target) {
      if (logFailed) {
         return;
      }
      try {
         if (qLog == null) {
            String dir = ZomboidFileSystem.instance.getCacheDir();
            if (dir == null) {
               return;
            }
            qLog = new BufferedWriter(new FileWriter(new File(dir, "pzopt-framequeue.out"), false), 1 << 16);
            qLog.write("# sim_ns slot_ns push_ns lag_ns target_ns (frameQueue, System.nanoTime; join with pzopt-pacing.out on sim_ns)\n");
         }
         StringBuilder b = line;
         b.setLength(0);
         b.append(frameSimNs).append(' ').append(frameSlotNs).append(' ').append(framePushNs).append(' ').append(lag).append(' ').append(target).append('\n');
         qLog.write(b.toString());
         long t = System.nanoTime();
         if (t - qFlushNs > 1_000_000_000L) {
            qLog.flush();
            qFlushNs = t;
         }
      } catch (IOException | RuntimeException e) {
         logFailed = true;
         Log.warn("pacing: frame queue log stopped: " + e);
      }
   }

   // limiter catch-up counters (game thread writes, the render thread's log line reads; approximate is fine)
   private static volatile long catchUpSlots, droppedSlots;

   /**
    * GameWindow.mainThreadStep after a capped step: the limiter's accumulator for the next step. Stock keeps
    * {@code acc % interval} (whole overrun intervals are dropped: a 20 ms step at a 120 cap gives one frame for two slots);
    * with {@link #CATCH_UP} = c the next steps make up to c missed slots back to back.
    */
   public static long limiterRemainder(long acc, long interval) {
      if (CATCH_UP <= 0) {
         return acc % interval;
      }
      long r = acc - interval;
      if (r >= interval) {
         catchUpSlots++;
      }
      long limit = (CATCH_UP + 1L) * interval;
      if (r >= limit) {
         long kept = CATCH_UP * interval + r % interval;
         droppedSlots += (r - kept) / interval;
         r = kept;
      }
      return r;
   }

   /** Render thread: the frame number the next afterSwap logs (for on-glass feedback matched later). */
   public static long currentFrame() {
      return frameNo - 1;
   }

   /** The limiter's current frame interval (0 = uncapped). */
   public static long capIntervalNs() {
      return capIntervalNs;
   }

   /** Render thread: frame f reached the screen at shownNs (System.nanoTime base); fills the log row while it is pending. */
   public static void onGlass(long f, long shownNs) {
      long[] row = rows[(int)(f & 15)];
      if (row[7] == f && f > frameNo - 1 - LAG_ROWS) {
         row[8] = shownNs;
      }
   }

   /**
    * The pacing mode for this frame. AUTO: gpu wherever the GL has timestamp queries (since 2026-09-25 also at a fixed
    * refresh: the Rosewood drive's present jitter 1.3 -> 0.4 ms, frames below the cap 28 -> 11 %, for ~1.7 ms more
    * step-to-screen time on average), the cpu fallback only while VRR is active, off under the macOS Metal bridge
    * (it paces its own presents).
    */
   static int effectiveMode() {
      return mode == AUTO ? autoResolved() : mode;
   }

   private static int autoResolved() {
      if (MacPresent.active()) {
         return OFF;
      }
      return autoMode == GPU || Vrr.active() ? autoMode : OFF;
   }

   public static String describeMode() {
      String m = mode == AUTO ? "auto (" + NAMES[autoResolved()] + ")" : NAMES[mode];
      return QUEUE ? "frameQueue at a cap, else " + m : m;
   }

   private static void addLag(long lag) {
      if (lag <= 0L || lag > 250_000_000L) {
         return;
      }
      lagWin[lagPos] = lag;
      lagPos = (lagPos + 1) % WINDOW;
      lagCount = Math.min(WINDOW, lagCount + 1);
   }

   private static void initGl() {
      glChecked = true;
      try {
         GLCapabilities caps = GL.getCapabilities();
         timestamps = (caps.OpenGL33 || caps.GL_ARB_timer_query) && CoreGl.timerQueries();
         if (mode == GPU_FINISH && !(caps.OpenGL32 || caps.GL_ARB_sync)) {
            mode = CPU;
         }
         if (timestamps) {
            queryIds = new int[QUERIES];
            GL15.glGenQueries(queryIds);
            queryBuf = QueryBuf.create(QUERIES, "pacing");
            Arrays.fill(queryFrame, -1L);
            calibrate(System.nanoTime());
         } else {
            autoMode = CPU;
            if (mode == GPU) {
               mode = CPU;
            }
         }
         if (QUEUE) {
            Log.info("pacing: frameQueue on (p" + Config.FRAME_QUEUE_PCT + " + " + Config.FRAME_QUEUE_MARGIN_US + " us, D " + Config.FRAME_QUEUE_MIN_PCT + ".." + Config.FRAME_QUEUE_MAX_PCT
               + " % of the cap interval, limiter catch-up " + CATCH_UP + " slots); presentPacing applies uncapped only");
         }
         if (MODE != OFF) {
            Log.info("pacing: presentPacing=" + Config.PRESENT_PACING + " -> " + NAMES[mode] + (mode == AUTO ? " (" + NAMES[autoMode] + (autoMode == GPU ? ")" : " while VRR is active)") : "")
               + " (GL timestamps " + (timestamps ? "yes" : "no") + ", p" + Config.PRESENT_PACING_PCT + " + " + Config.PRESENT_PACING_MARGIN_US + " us)");
         }
      } catch (Throwable t) {
         timestamps = false;
         autoMode = CPU;
         if (mode == GPU || mode == GPU_FINISH) {
            mode = CPU;
         }
         Log.warn("pacing: GL queries unavailable: " + t);
      }
   }

   /** GPU clock -> System.nanoTime offset; GL_TIMESTAMP read synchronously returns the GPU's current time. */
   private static void calibrate(long now) {
      long before = System.nanoTime();
      long gpu = GL32.glGetInteger64(GL33.GL_TIMESTAMP);
      long after = System.nanoTime();
      gpuToCpuNs = (before + after) / 2L - gpu;
      lastCalibrationNs = now;
   }

   private static void issueQuery(long frame) {
      int slot = (int)(frame % QUERIES);
      if (queryFrame[slot] != -1L) {
         return; // still pending from QUERIES frames ago (GPU far behind): skip this frame
      }
      GL33.glQueryCounter(queryIds[slot], GL33.GL_TIMESTAMP);
      if (queryBuf != null) { // pacingQueryBuffer: the GPU writes the result into the slot once it is there
         queryBuf.request(slot, queryIds[slot]);
      }
      queryFrame[slot] = frame;
      querySim[slot] = frameSimNs;
   }

   private static void collectQueries(long now) {
      if (now - lastCalibrationNs > 8_000_000_000L) { // tail pass: every 8 s, was 1 s: glGetInteger64(GL_TIMESTAMP) waits for NVIDIA's threaded driver queue (a render-thread stall in 15 of the slow frames of run t1-jfrn1); the GPU and CPU clocks drift far less than a microsecond in 8 s
         calibrate(now);
      }
      for (int i = 0; i < QUERIES; i++) {
         long f = queryFrame[i];
         if (f == -1L) {
            continue;
         }
         long done;
         if (queryBuf != null) { // pacingQueryBuffer: read from client memory, no driver call
            long v = queryBuf.get(i);
            if (v == -1L) {
               continue;
            }
            done = v + gpuToCpuNs;
         } else {
            if (GL15.glGetQueryObjecti(queryIds[i], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
               continue;
            }
            done = GL33.glGetQueryObjecti64(queryIds[i], GL15.GL_QUERY_RESULT) + gpuToCpuNs;
         }
         queryFrame[i] = -1L;
         if (effectiveMode() == GPU && querySim[i] != 0L) {
            addLag(done - querySim[i]);
         }
         long[] row = rows[(int)(f & 15)];
         if (row[7] == f) {
            row[6] = done;
         }
      }
   }

   /** Render thread, after Display.update returned. */
   public static void afterSwap() {
      if (Config.GC_SLACK_FIT) {
         rtSwapRetNs = System.nanoTime(); // gcSlackFit: the render thread is free until its next acquire
      }
      if (!LOG) {
         return;
      }
      long ret = System.nanoTime();
      long f = frameNo - 1;
      long[] row = rows[(int)(f & 15)];
      row[0] = ret;
      row[1] = frameSimNs;
      row[2] = acquireNs;
      row[3] = swapCallNs;
      row[4] = ret;
      row[5] = waitNs;
      row[6] = 0L;
      row[7] = f;
      row[8] = 0L;
      if (f >= LAG_ROWS) {
         write(rows[(int)((f - LAG_ROWS) & 15)]);
      }
   }

   private static void write(long[] row) {
      if (logFailed) {
         return;
      }
      try {
         if (log == null) {
            String dir = ZomboidFileSystem.instance.getCacheDir();
            if (dir == null) {
               return;
            }
            File file = new File(dir, "pzopt-pacing.out");
            log = new BufferedWriter(new FileWriter(file, false), 1 << 16);
            logStartNs = System.nanoTime();
            logStartEpochMs = System.currentTimeMillis();
            log.write("# epoch_ms sim_ns acquire_ns swap_call_ns swap_ret_ns wait_ns gpu_done_ns shown_ns (System.nanoTime; shown = on-glass time where the platform reports it (macOS Metal bridge); presentPacing="
               + Config.PRESENT_PACING + " mode=" + NAMES[mode] + ")\n");
            Log.info("pacing: logging frames to " + file);
         }
         StringBuilder b = line;
         b.setLength(0);
         b.append(logStartEpochMs + (row[4] - logStartNs) / 1_000_000L);
         for (int i = 1; i <= 6; i++) {
            b.append(' ').append(row[i]);
         }
         b.append(' ').append(row[8]);
         b.append('\n');
         log.write(b.toString());
         if (row[4] - lastFlushNs > 1_000_000_000L) {
            log.flush();
            lastFlushNs = row[4];
         }
      } catch (IOException | RuntimeException e) {
         logFailed = true;
         Log.warn("pacing: log stopped: " + e);
      }
   }

   public static void flushLog() {
      for (BufferedWriter w : new BufferedWriter[]{log, qLog}) {
         if (w != null) {
            try {
               w.flush();
            } catch (IOException ignored) {
            }
         }
      }
   }

   /**
    * limiterSleep: parks until limiterSpinUs before the limiter's next step and returns; the stock limiter loop spins the
    * rest, so a late wake costs nothing but the spin it replaces.
    */
   public static void limiterWait(long stepNs) {
      SlackWork.run(stepNs); // slackWork: deferred game-thread jobs that fit before the next step
      if (!slackSet) {
         slackSet = true;
         timerSlack1ns();
      }
      long left = stepNs - System.nanoTime() - Config.LIMITER_SPIN_US * 1000L;
      if (left > 20_000L) {
         LockSupport.parkNanos(left);
      }
   }

   private static boolean slackSet; // game thread only

   /**
    * Linux: the calling thread's timer slack to 1 ns (default 50 us), so a park ends when asked; prctl(PR_SET_TIMERSLACK)
    * only touches the calling thread.
    */
   static void timerSlack1ns() {
      if (!new java.io.File("/proc/self/task").isDirectory()) {
         return;
      }
      try {
         java.lang.foreign.Linker l = java.lang.foreign.Linker.nativeLinker();
         java.lang.invoke.MethodHandle prctl = l.downcallHandle(l.defaultLookup().find("prctl").orElseThrow(),
               java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_INT,
                     java.lang.foreign.ValueLayout.JAVA_LONG), java.lang.foreign.Linker.Option.firstVariadicArg(1));
         int r = (int) prctl.invokeExact(29, 1L); // PR_SET_TIMERSLACK
         if (r != 0) {
            Log.warn("limiterSleep: prctl(PR_SET_TIMERSLACK) = " + r);
         }
      } catch (Throwable t) {
         Log.warn("limiterSleep: no timer slack change (" + t + ")");
      }
   }

   /** Sleeps to ~1 ms before the deadline (park is coarse), then spins the rest. */
   public static void waitUntil(long deadlineNs) {
      while (true) {
         long left = deadlineNs - System.nanoTime();
         if (left <= 0L) {
            return;
         }
         if (left > 1_200_000L) {
            LockSupport.parkNanos(left - 1_000_000L);
         } else {
            Thread.onSpinWait();
         }
      }
   }
}
