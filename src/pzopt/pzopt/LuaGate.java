package pzopt;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Lua on the frame workers (luaWorkerGate, 2026-10-01). Kahlua has one stack per KahluaThread and the game runs all its
 * Lua on LuaManager.thread from the game thread. Our batches move zombie work onto {@link FrameBatch.Worker}s; a Lua call
 * reached from there (a mod's function the zombie update calls: a hook, a global-object system such as farming's
 * destroyPlant when a zombie tramples a crop) pushed onto that shared stack beside the game thread: Kahlua's
 * "Lua code called from the wrong thread" and, with a second worker, a corrupted stack (ArrayIndexOutOfBounds -2,
 * the maintainer's farm save, 2026-09-28). Events were already captured and replayed (UpdateBatch); this covers every
 * other Java -> Lua call, at the one door they all use (the LuaCaller override):
 *
 * <ul>
 *   <li>a void call inside the entity update batch joins that task's Lua replay: it runs on the game thread after the
 *       join, in stock's serial order (UpdateBatch.captureLuaCall);
 *   <li>any other call waits on the worker while the game thread runs it: at its next task boundary or while it waits
 *       in a join (FrameBatch). The game thread never sits inside Lua at those points, so the stack stays its own.
 *       After {@link #TIMEOUT_NS} with nobody taking it (a game thread blocked elsewhere) the worker runs the call
 *       itself, which is what happened before this gate; counted and logged.
 * </ul>
 *
 * Counters on the FBORenderCell log line ({@link #describe}); the first call per Lua file is logged with the file, so a
 * mod's function reached from a worker shows up in console.txt by name.
 */
public final class LuaGate {
   private LuaGate() {
   }

   static final boolean ENABLED = Config.LUA_WORKER_GATE && Overrides.enabled();
   static final long TIMEOUT_NS = 500_000_000L;

   private static final class Call {
      final Callable<Object> body;
      final Thread waiter;
      final AtomicInteger state = new AtomicInteger(); // 0 queued, 1 taken by the game thread, 2 done, 3 abandoned by the worker
      Object result;
      Throwable error;

      Call(Callable<Object> body, Thread waiter) {
         this.body = body;
         this.waiter = waiter;
      }
   }

   private static final ConcurrentLinkedQueue<Call> queue = new ConcurrentLinkedQueue<>();
   private static final AtomicInteger queued = new AtomicInteger();
   private static final AtomicLong replayed = new AtomicLong(), served = new AtomicLong(), timeouts = new AtomicLong();
   private static final Set<String> logged = ConcurrentHashMap.newKeySet();

   /** True on a frame worker with the gate on: the LuaCaller override hands the call here instead of running it. */
   public static boolean onWorker() {
      return ENABLED && Thread.currentThread() instanceof FrameBatch.Worker;
   }

   /** A void Lua call from a worker: into the update batch's replay when one is capturing, else run on the game thread. */
   public static void callVoid(Object function, Runnable call) {
      note(function);
      if (UpdateBatch.captureLuaCall(call)) {
         replayed.incrementAndGet();
         return;
      }
      run(() -> {
         call.run();
         return null;
      });
   }

   /** A Lua call with a result from a worker: run on the game thread, the worker waits for the result. */
   public static Object call(Object function, Callable<Object> call) {
      note(function);
      return run(call);
   }

   private static Object run(Callable<Object> body) {
      Call c = new Call(body, Thread.currentThread());
      queue.add(c);
      queued.incrementAndGet();
      long deadline = System.nanoTime() + TIMEOUT_NS;
      while (c.state.get() != 2) {
         if (c.state.get() == 0 && System.nanoTime() > deadline && c.state.compareAndSet(0, 3)) {
            timeouts.incrementAndGet();
            if (logged.add("timeout")) {
               Log.warn("lua gate: the game thread did not take a worker's Lua call within " + TIMEOUT_NS / 1_000_000L
                     + " ms; it ran on " + Thread.currentThread().getName() + " (logged once, counted as luaGate timeouts)");
            }
            return callHere(body);
         }
         ShortWait.pause(20_000L);
      }
      if (c.error != null) {
         throw sneaky(c.error);
      }
      return c.result;
   }

   /**
    * Game thread, at a FrameBatch task boundary and in every join wait: run the calls workers are waiting for. One
    * volatile read when none is queued.
    */
   public static void service() {
      if (queued.get() == 0 || Thread.currentThread() instanceof FrameBatch.Worker) {
         return;
      }
      Call c;
      while ((c = queue.poll()) != null) {
         queued.decrementAndGet();
         if (!c.state.compareAndSet(0, 1)) {
            continue; // the worker gave up and ran it itself
         }
         try {
            c.result = c.body.call();
         } catch (Throwable t) {
            c.error = t;
         }
         served.incrementAndGet();
         c.state.set(2);
         LockSupport.unpark(c.waiter);
      }
   }

   private static Object callHere(Callable<Object> body) {
      try {
         return body.call();
      } catch (Exception e) {
         throw sneaky(e);
      }
   }

   private static void note(Object function) {
      if (logged.size() > 32) {
         return;
      }
      String file = function instanceof se.krka.kahlua.vm.LuaClosure c && c.prototype != null ? c.prototype.filename : String.valueOf(function);
      if (file != null && logged.add(file)) {
         Log.info("lua gate: " + Thread.currentThread().getName() + " reached Lua " + file
               + " (" + LuaOrigin.describe(file) + "); it runs on the game thread (luaWorkerGate)");
      }
   }

   @SuppressWarnings("unchecked")
   private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
      throw (T) t;
   }

   /** Calls replayed after the update batch's join, served on the game thread, run on the worker after the timeout. */
   public static String describe() {
      return "luaGate replayed=" + replayed.get() + " served=" + served.get() + " timeouts=" + timeouts.get();
   }

   public static long timeouts() {
      return timeouts.get();
   }

   public static long handled() {
      return replayed.get() + served.get();
   }
}
