package pzopt;

/**
 * The postupdate stream (zombie redesign pass, 2026-10-13, f8-zomb2): the FrameBatch stream of ActionEval's transition
 * evaluations published during the bucket loop ({@code postupdateFuse}). Built to also carry the movement computes as its first
 * tasks ({@code moves}, the measured and dropped postupdateMoveStream: the loop then waited for them as long as the
 * synchronous batch had taken, run zomb2-ab6 / zomb2-prof2); {@code moves} is 0 now.
 */
public final class PuStream {
   private PuStream() {
   }

   private static boolean open; // game thread: this frame's stream is on the workers
   private static int moves;
   private static int evals;

   /** Game thread: open the stream with {@code moveCount} movement tasks (0 when ActionEval opens it). */
   static void open(int moveCount) {
      moves = moveCount;
      evals = 0;
      FrameBatch.runStream(PuStream::task, PuStream::done);
      open = true;
      FrameBatch.publish(moves);
   }

   /** Game thread: true while this frame's stream takes tasks. */
   static boolean isOpen() {
      if (open && !FrameBatch.streamOpen()) {
         open = false; // another batch user's join sealed it
      }
      return open;
   }

   /** Game thread: ActionEval's evaluation count published so far. */
   static void publishEvals(int count) {
      evals = count;
      FrameBatch.publish(moves + count);
   }

   /** Game thread: the final size; the stream stays pending until the next FrameBatch join. */
   static void seal() {
      if (open) {
         FrameBatch.seal(moves + evals);
         open = false;
      }
   }

   /** Game thread, the end of the frame's postupdate users: nothing more is published. */
   static void close() {
      seal();
      moves = 0;
      evals = 0;
   }

   private static void task(int j) {
      ActionEval.streamTask(j - moves);
   }

   private static void done(Throwable t) {
      if (t != null) {
         Log.warn("postupdate stream: a task failed: " + t);
      }
   }
}
