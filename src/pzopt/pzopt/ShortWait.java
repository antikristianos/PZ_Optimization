package pzopt;

import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

/**
 * The short wait of the per-frame joins (FrameBatch, CharDraw, LuaGate, VisPolyAsync, AnimParallel), after their spin.
 *
 * <p>They waited with {@code LockSupport.parkNanos(5..20 us)}: on Linux / macOS that sleeps tens of microseconds, but
 * HotSpot's Windows park converts the timeout to whole milliseconds and rounds anything below one up to 1 ms (then the
 * timer resolution), so every slice of a join that outlived its spin cost a millisecond or more. Windows test 2026-10-11
 * (docs/windows-test.md): pixelLight's light gather on the frame workers cost +0.96 ms a frame on the Rosewood drive
 * against +0.28 on Linux, 10 % of the game thread waiting in {@code FrameBatch.run}. On Windows the wait is a
 * {@code Thread.yield()} (SwitchToThread: back at once when no other thread is ready on that core) instead; the joins are
 * short and the waiting thread had nothing else to do. {@code shortWait=yield|park} forces one way for an A/B.
 */
public final class ShortWait {
   private ShortWait() {
   }

   static final boolean YIELD = switch (Config.SHORT_WAIT.toLowerCase(Locale.ROOT)) {
      case "yield" -> true;
      case "park" -> false;
      default -> System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
   };

   /** One slice of a short join wait. */
   public static void pause(long nanos) {
      if (YIELD) {
         Thread.yield();
      } else {
         LockSupport.parkNanos(nanos);
      }
   }
}
