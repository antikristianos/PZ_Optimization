package pzopt;

import zombie.characters.IsoZombie;

/**
 * The zombies' separation pass on the frame workers ({@code separateParallel}, 2026-09-22).
 *
 * <p>{@code IsoMovingObject.separate()} pushes a character out of the characters it overlaps: it walks its square and
 * the eight around it, and for every other solid object within the two half-widths computes a push. It was 3 % of the
 * game thread on the Louisville horde, all of it reads of the grid and of the other objects' positions — the writes
 * (the position, the contact, the wasSeparated flag, the {@code collideWith} calls that run Lua events and can start a
 * window climb or a thump) are recorded by {@code IsoZombie.pzoptSeparateCompute} and applied by the game thread at
 * the point in the zombie's own update where stock would have done them, in loop order.
 *
 * <p>The scheduler override collects the zombies scheduled for this frame while it fills the simulation buckets
 * ({@link #add}) and runs their computes on {@link FrameBatch} before the update loop ({@link #run}). The one
 * difference from stock: a neighbour's position is read at the top of the frame instead of as the loop advances — a
 * Jacobi step instead of Gauss-Seidel, a fraction of the zombie's per-frame movement. A zombie the batch did not
 * reach (not scheduled, batching off, an exception) computes inline in {@code separate()} exactly as before.
 */
public final class SeparateBatch {
   private SeparateBatch() {
   }

   private static IsoZombie[] queue = new IsoZombie[2048];
   private static int count;
   private static volatile boolean failed;

   public static long frames, batched, maxBatch, workNanos, waitNanos;

   // separateMemo (zombie pass 2026-10-13): while the batch runs nothing writes the grid or a character, so a neighbour's
   // "solid for separation" (its state, sitting, ragdoll checks: 31 % of the pass, asked once per neighbour visit) is
   // computed once per frame and a worker caches the square-to-square blocked answers in its own table (SeparateMask).
   private static final boolean MEMO = Config.SEPARATE_MEMO;
   private static final boolean MEMO_CHECK = Config.DEV_SEPARATE_MEMO_CHECK;
   private static volatile boolean memoActive;
   private static volatile int memoStamp;
   private static final java.util.concurrent.atomic.AtomicLong memoChecked = new java.util.concurrent.atomic.AtomicLong();
   private static final java.util.concurrent.atomic.AtomicLong memoDiffs = new java.util.concurrent.atomic.AtomicLong();

   /** Any thread: inside the separation batch with separateMemo on. */
   public static boolean memoActive() {
      return memoActive;
   }

   /** Any thread: obj.isSolidForSeparate(), memoised per zombie for the batch's frame (devSeparateMemoCheck: 1 in 16 also computed directly). */
   public static boolean solid(zombie.iso.IsoMovingObject obj) {
      if (memoActive && obj instanceof IsoZombie z) {
         boolean m = z.pzoptSolidMemo(memoStamp);
         if (MEMO_CHECK && (System.identityHashCode(obj) + memoStamp & 15) == 0) {
            memoChecked.incrementAndGet();
            if (m != obj.isSolidForSeparate()) {
               memoDiffs.incrementAndGet();
            }
         }
         return m;
      }
      return obj.isSolidForSeparate();
   }

   /** True while the scheduler should hand this frame's zombies over. */
   public static boolean enabled() {
      return Config.SEPARATE_PARALLEL && Config.SEPARATE_FAST && !failed && Config.effectiveWorkers() > 1 && Overrides.enabled();
   }

   /** Game thread, from startFrame: this object updates this frame and its separation can be precomputed. */
   public static void add(IsoZombie zombie) {
      if (count == queue.length) {
         queue = java.util.Arrays.copyOf(queue, count * 2);
      }

      queue[count++] = zombie;
   }

   /** Game thread, before the update loop: the computes on the workers, then the loop applies them in order. */
   public static void run() {
      if (count == 0) {
         return;
      }

      frames++;
      batched += count;
      if (count > maxBatch) {
         maxBatch = count;
      }

      long w0 = FrameBatch.workNanos;
      long q0 = FrameBatch.waitNanos;
      memoStamp = FrameTick.frame();
      memoActive = MEMO && GtAb.on(GtAb.SEPARATE_MEMO); // separateMemo: the grid and every character are read-only for the batch's length
      Throwable t;
      try {
         t = FrameBatch.run(count, i -> queue[i].pzoptSeparateCompute());
      } finally {
         memoActive = false;
      }
      workNanos += FrameBatch.workNanos - w0;
      waitNanos += FrameBatch.waitNanos - q0;
      if (t != null && !failed) {
         failed = true; // the zombies without a record compute inline; stock path from the next frame on
         Log.warn("separateParallel: separation failed on a worker, batching off: " + t);
      }

      java.util.Arrays.fill(queue, 0, count, null);
      count = 0;
   }

   /** Game thread: drop whatever was collected (a frame that never reached run()). */
   public static void clear() {
      java.util.Arrays.fill(queue, 0, count, null);
      count = 0;
   }

   public static String describe() {
      return "separate batch: frames=" + frames + " batched=" + batched + " max=" + maxBatch
            + " work ms=" + (workNanos / 1_000_000L) + " wait ms=" + (waitNanos / 1_000_000L) + (failed ? " FAILED" : "")
            + (MEMO_CHECK ? " memoChecked=" + memoChecked.get() + " memoDiffs=" + memoDiffs.get() : "");
   }
}
