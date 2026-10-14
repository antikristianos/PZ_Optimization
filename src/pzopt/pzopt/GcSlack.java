package pzopt;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;

/**
 * {@code gcSlack} (2026-10-14, Louisville 120 GC pass): the young collection moved into the frame's slack. With G1 a young
 * pause comes when eden is full, wherever the game thread is: mid-step, a 3-10 ms pause makes the step late and stalls
 * the render thread's frame too. At a frame cap the game thread parks at the end of most steps ({@link SlackWork}); when
 * eden will be full within {@code gcSlackFrames} steps and the predicted cost (the filler below plus the learned pause)
 * fits the slack left plus {@code gcSlackOverUs}, this producer fills the rest of eden with short-lived filler arrays (each
 * well under half a region, so in eden, never humongous) until the collector counts a collection. The collection G1
 * would have run a few steps later runs now, while the game thread would otherwise park. A collection due inside the
 * next step runs at the first slack call whatever the slack (it would come mid-step otherwise). Only the moment of the
 * collection changes: no game state is touched, the filler is garbage the collection frees.
 *
 * <p>Where eden ends: G1's young target is not what the Eden pool reports as committed (that is up to ~180 MB more on
 * the Louisville route), so the trigger point is learned from the collections themselves: the Eden pool's usage before
 * each young collection ({@code com.sun.management.GcInfo}), the median of the last five.
 *
 * <p>{@code gcSlackAlternate}: every other collection cycle stays natural, for a within-run A/B (the console's
 * "gc slack: collect" lines give the scheduled ones' times).
 */
public final class GcSlack {
   private GcSlack() {
   }

   private static MemoryPoolMXBean eden;
   private static GarbageCollectorMXBean young; // "G1 Young Generation": young and mixed pauses
   private static final ArrayList<GarbageCollectorMXBean> COLLECTORS = new ArrayList<>();
   private static boolean initDone;
   private static boolean usable;
   private static long regionBytes = 2L << 20;
   // Pacing.stepStartNs of the step last sampled: pending() runs once per job pick of SlackWork.run's loop, and SlackWork.run
   // itself runs once per game step (its first limiterWait pass), so the collection stays pending for the whole of that run
   private static long sampledStep = Long.MIN_VALUE;
   private static long lastUsed = -1L;
   private static long lastCount = -1L;
   private static long lastYoungTimeMs = -1L;
   private static long lastYoungCount = -1L;
   private static long remaining;
   private static long lastRemaining = -1L;
   private static boolean due;
   private static boolean urgent; // the collection would come inside the next step
   private static double perStep = 1 << 20;
   private static boolean skipCycle; // gcSlackAlternate: this cycle stays natural
   private static final long[] CAPS = new long[5]; // eden used before the last young collections
   private static int capN;
   private static long capEst = -1L;
   private static double pauseNs = 3_000_000.0; // learned young pause (the collector's own time, ms resolution, averaged)
   private static double fillNsPerByte = 0.15; // learned filler cost (zeroing), ns a byte
   private static Object sink;
   // jdk.internal.misc.Unsafe.allocateUninitializedArray (the game's launcher exports jdk.internal.misc to the class path):
   // filler arrays nobody reads need no zeroing; on the route zeroing cost ~0.12 ns a byte, ~4 ms for 33 MB of eden
   private static java.lang.invoke.MethodHandle uninit;
   private static boolean uninitTried;
   public static long targetAfterUsed;
   public static long scheduled, urgentRuns, natural, missed, noCollect, fillerBytes, fillerNs, dueSteps, steps;
   private static long remBeforeNaturalSum, remBeforeNaturalN, remBeforeNaturalMax;

   static final SlackWork.Producer PRODUCER = new SlackWork.Producer() {
      @Override
      public boolean pending() {
         sample();
         return due && !skipCycle;
      }

      @Override
      public long nextCostNs() {
         if (urgent) {
            return 0L; // now, whatever the slack, beats mid-step
         }
         double cost = Math.min(pauseNs, 6_000_000.0) + Math.max(0L, remaining) * fillNsPerByte;
         return Math.max(0L, (long)cost - Config.GC_SLACK_OVER_US * 1000L);
      }

      @Override
      public int nextAgeFrames() {
         // ahead of the other slack jobs (a collection that misses the slack costs a whole pause mid-step, a deferred job
         // only waits a frame), but never the forced path: a natural collection comes anyway
         return Config.SLACK_MAX_WAIT_FRAMES - 1;
      }

      @Override
      public void runNext() {
         collect();
      }
   };

   private static void init() {
      initDone = true;
      try {
         for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getName().equals("G1 Eden Space")) {
               eden = p;
            }
         }
         for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            // young and mixed pauses ("G1 Young Generation"), full ones ("G1 Old Generation"); the concurrent cycle's count
            // ("G1 Concurrent GC": remark and cleanup) does not empty eden
            if (!g.getName().contains("Concurrent")) {
               COLLECTORS.add(g);
            }
            if (g.getName().equals("G1 Young Generation")) {
               young = g;
            }
         }
         try {
            String v = ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class).getVMOption("G1HeapRegionSize").getValue();
            regionBytes = Math.max(1L << 20, Long.parseLong(v));
         } catch (Throwable ignored) {
         }
         usable = eden != null && young != null && young instanceof com.sun.management.GarbageCollectorMXBean;
         Log.info("gc slack: " + (usable ? "on, region " + (regionBytes >> 20) + " MB, collect " + Config.GC_SLACK_FRAMES + " steps ahead, "
               + Config.GC_SLACK_OVER_US + " us over the slack allowed, " + (uninit() != null ? "uninitialised" : "zeroed") + " filler" + (Config.GC_SLACK_ALTERNATE ? ", every other cycle (A/B)" : "")
               : "off (not G1: no \"G1 Eden Space\" pool / \"G1 Young Generation\" collector)"));
      } catch (Throwable t) {
         usable = false;
         Log.warn("gc slack: off (" + t + ")");
      }
   }

   private static long gcCount() {
      long n = 0;
      for (int i = 0; i < COLLECTORS.size(); i++) {
         n += COLLECTORS.get(i).getCollectionCount();
      }
      return n;
   }

   /** After a collection: eden's usage before it (the trigger point) and the pause, learned. */
   private static void learnCollection() {
      try {
         com.sun.management.GcInfo gi = ((com.sun.management.GarbageCollectorMXBean)young).getLastGcInfo();
         if (gi != null) {
            MemoryUsage before = gi.getMemoryUsageBeforeGc().get("G1 Eden Space");
            if (before != null && before.getUsed() > 0L) {
               CAPS[capN++ % CAPS.length] = before.getUsed();
               int n = Math.min(capN, CAPS.length);
               long[] s = java.util.Arrays.copyOf(CAPS, n);
               java.util.Arrays.sort(s);
               capEst = s[n / 2];
            }
            // G1 sizes eden anew at every collection (on the route 20-40 regions, from the pause predictions): the Eden
            // pool's committed size right after the collection is that new target, the next trigger point
            MemoryUsage after = gi.getMemoryUsageAfterGc().get("G1 Eden Space");
            if (Config.GC_SLACK_TARGET_AFTER && after != null && after.getCommitted() >= regionBytes) {
               capEst = after.getCommitted();
               targetAfterUsed++;
            }
         }
         long t = young.getCollectionTime();
         long c = young.getCollectionCount();
         if (lastYoungTimeMs >= 0L && t >= lastYoungTimeMs && c > lastYoungCount) {
            // per collection: the collector's time (whole ms) over the collections since the last look
            double per = (t - lastYoungTimeMs) * 1_000_000.0 / (c - lastYoungCount);
            pauseNs = pauseNs * 0.8 + Math.min(per, 20_000_000.0) * 0.2;
         }
         lastYoungTimeMs = t;
         lastYoungCount = c;
      } catch (Throwable ignored) {
      }
   }

   /** Once a step: eden's fill, its rate per step, and whether the collection is due within gcSlackFrames steps. */
   private static void sample() {
      long step = Pacing.stepStartNs();
      if (step == sampledStep) {
         return;
      }
      sampledStep = step;
      if (!initDone) {
         init();
      }
      if (!usable) {
         due = false;
         return;
      }
      steps++;
      MemoryUsage u = eden.getUsage();
      long used = u.getUsed();
      long count = gcCount();
      if (lastCount >= 0L && count != lastCount) {
         // a collection since the last step that this producer did not run (its own reset lastCount in collect)
         natural += count - lastCount;
         if (lastRemaining >= 0L) {
            remBeforeNaturalSum += lastRemaining;
            remBeforeNaturalN++;
            remBeforeNaturalMax = Math.max(remBeforeNaturalMax, lastRemaining);
         }
         if (due && !skipCycle) {
            missed++; // it was due but never ran
         }
         learnCollection();
         newCycle();
      } else if (lastUsed >= 0L && used >= lastUsed) {
         perStep = perStep * 0.95 + (used - lastUsed) * 0.05;
      }
      if (lastYoungTimeMs < 0L) {
         lastYoungTimeMs = young.getCollectionTime();
         lastYoungCount = young.getCollectionCount();
      }
      lastUsed = used;
      lastCount = count;
      long cap = capEst > 0L ? Math.min(capEst, u.getCommitted()) : u.getCommitted();
      remaining = cap - used;
      lastRemaining = remaining;
      // due: a few steps from the end, or past gcSlackEarlyPct of eden (the streamer allocates in bursts of tens of MB, so
      // the per-step rate alone misses most collections: on Louisville the step before a natural one still showed ~40 MB)
      due = cap > 0L && capEst > 0L && (remaining <= (long)(perStep * Config.GC_SLACK_FRAMES) + regionBytes
            || used >= cap * Config.GC_SLACK_EARLY_PCT / 100L);
      urgent = due && remaining <= (long)(perStep * 1.5) + regionBytes;
      if (due) {
         dueSteps++;
      }
   }

   private static void newCycle() {
      skipCycle = Config.GC_SLACK_ALTERNATE && !skipCycle;
   }

   /** Fill the rest of eden until the collector runs (bounded: eden left + sixteen regions, at most 80 MB). */
   private static void collect() {
      long t0 = System.nanoTime();
      long c0 = gcCount();
      long limit = Math.min(Math.max(0L, remaining) + 16L * regionBytes, 80L << 20); // uninitialised filler: ~0.03-0.04 ns a byte, a wrong estimate costs ~3 ms at most
      int chunk = (int)Math.min(256L << 10, regionBytes / 4L); // well below half a region: never humongous
      long filled = 0L;
      java.lang.invoke.MethodHandle mh = uninit();
      while (filled < limit) {
         if (mh != null) {
            try {
               sink = (Object)mh.invokeExact(byte.class, chunk);
            } catch (Throwable t) {
               uninit = null;
               mh = null;
               sink = new byte[chunk];
            }
         } else {
            sink = new byte[chunk];
         }
         filled += chunk;
         if ((filled & ((1L << 20) - 1L)) == 0L && gcCount() != c0) {
            break;
         }
      }
      sink = null;
      long t1 = System.nanoTime();
      long c1 = gcCount();
      fillerBytes += filled;
      fillerNs += t1 - t0;
      boolean wasUrgent = urgent;
      if (c1 != c0) {
         scheduled++;
         if (wasUrgent) {
            urgentRuns++;
         }
         long pauseBefore = lastYoungTimeMs;
         learnCollection();
         long pauseMs = lastYoungTimeMs - pauseBefore;
         long fillOnly = (t1 - t0) - Math.max(0L, pauseMs) * 1_000_000L;
         if (filled > (1L << 20) && fillOnly > 0L) {
            fillNsPerByte = fillNsPerByte * 0.8 + (double)fillOnly / filled * 0.2;
         }
         newCycle();
      } else {
         noCollect++;
         if (capEst > 0L) {
            capEst += filled; // eden is larger than learned this cycle
         }
      }
      lastCount = c1;
      lastUsed = -1L;
      lastRemaining = -1L;
      due = false;
      urgent = false;
      if (Config.INSTRUMENT) {
         Log.info("gc slack: collect at " + System.currentTimeMillis() + " ms, " + (filled >> 10) + " KB filler, " + (t1 - t0) / 1000L
               + " us with the pause" + (wasUrgent ? " (urgent)" : "") + (c1 == c0 ? " (no collection)" : ""));
      }
   }

   private static java.lang.invoke.MethodHandle uninit() {
      if (!uninitTried) {
         uninitTried = true;
         if (Config.GC_SLACK_UNINIT) {
            try {
               Class<?> uc = Class.forName("jdk.internal.misc.Unsafe");
               Object u = uc.getMethod("getUnsafe").invoke(null);
               uninit = java.lang.invoke.MethodHandles.lookup().findVirtual(uc, "allocateUninitializedArray",
                     java.lang.invoke.MethodType.methodType(Object.class, Class.class, int.class)).bindTo(u);
               Object probe = (Object)uninit.invokeExact(byte.class, 16);
               if (!(probe instanceof byte[])) {
                  uninit = null;
               }
            } catch (Throwable t) {
               uninit = null;
               Log.info("gc slack: zeroed filler (" + t + ")");
            }
         }
      }
      return uninit;
   }

   private static String collectors() {
      StringBuilder sb = new StringBuilder();
      for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
         sb.append(" | ").append(g.getName().replace(' ', '_')).append(" count=").append(g.getCollectionCount()).append(" ms=").append(g.getCollectionTime());
      }
      return sb.toString();
   }

   /** pzopt-bench.out's gc_slack=. */
   public static String describe() {
      return "scheduled=" + scheduled + " urgent=" + urgentRuns + " natural=" + natural + " missed=" + missed + " noCollect=" + noCollect
            + " steps=" + steps + " dueSteps=" + dueSteps
            + " edenLeftBeforeNaturalMB(avg/max)=" + (remBeforeNaturalN > 0 ? remBeforeNaturalSum / remBeforeNaturalN >> 20 : -1) + "/" + (remBeforeNaturalMax >> 20)
            + " edenCapEstMB=" + (capEst >> 20) + " targetAfter=" + targetAfterUsed + " fillerMB=" + (fillerBytes >> 20) + " fillerAndPauseMs=" + fillerNs / 1_000_000L
            + " pauseEstUs=" + (long)(pauseNs / 1000) + " fillNsPerByte=" + String.format("%.3f", fillNsPerByte)
            + " perStepKB=" + (long)(perStep / 1024) + collectors();
   }
}
