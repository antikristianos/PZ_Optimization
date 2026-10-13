package pzopt;

import zombie.GameTime;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoZombie;
import zombie.characters.action.ActionContext;
import zombie.network.GameClient;
import zombie.network.GameServer;

/**
 * The zombies' action-context transition evaluation on the other cores ({@code actionEvalParallel}, 2026-09-22,
 * docs/plan-zombie-multithread.md §3.2 step 2).
 *
 * <p>Every frame every animating zombie's {@code ActionContext.update()} evaluates the transitions of its current
 * action state (and of its sub-states): tens of conditions, each a variable lookup and a callback into the zombie,
 * then a compare. It was 7-10 % of the game thread on the Louisville horde after the typed-operand rewrite. The
 * evaluation only reads the zombie (its variables, its target's position, the event set of the context) and writes
 * the context's own "next" container, so it can run on the {@link FrameBatch} workers; the state change itself
 * (history, enter / exit hooks, the events) and everything after it in {@code postUpdateAnimating} (the animator, the
 * model slot, the light info) must stay on the game thread in the original order.
 *
 * <p>The postupdate loop is split in two by the {@code MovingObjectUpdateScheduler} override:
 * <ol>
 * <li>the loop itself: every object's {@code postupdate()} as before, but an eligible zombie's {@code postUpdateAnimating}
 *     stops after the forward direction, aim angle and turning flags (the inputs the conditions read) and queues the
 *     zombie here ({@link #submit});</li>
 * <li>{@link #flush}: the queued contexts evaluate on the workers into their "next" containers; then, on the game
 *     thread and in queue order, each zombie runs the rest of its {@code postUpdateAnimating}
 *     ({@code pzoptPostUpdateAnimatingRest}: the {@code ActionContext.update()} override finds the evaluation done and
 *     only applies it, then the animator and the model update as stock).</li>
 * </ol>
 * The bone batch ({@code AnimBatch}) collects its work during step 2 and runs after it, as before.
 *
 * <p>Not queued (stock path inline): players and animals, multiplayer, a zombie being grappled / grappling / that
 * reanimated a player (its counterpart reads it in the same loop), and any zombie whose current state or sub-states
 * have a transition condition other than a variable compare or an event test (a {@code LuaCall} runs Lua). A
 * condition callback reached from a worker must not touch shared scratch: {@code IsoZombie.isFacingTarget} is the
 * one that did (static vectors), fixed in the IsoZombie override. {@code devActionEvalCheck=true} re-evaluates every
 * queued context on the game thread at apply time and counts disagreements ({@code mismatches}), which is the
 * determinism rig of this phase (two runs of the game are never frame-aligned, so a run-to-run checksum cannot be).
 */
public final class ActionEval {
   private ActionEval() {
   }

   private static final boolean ENABLED = Config.ACTION_EVAL_PARALLEL && Config.effectiveWorkers() > 1;

   private static boolean active; // game thread only: submissions accepted
   private static boolean applying; // game thread only: inside the phase-2 loop
   private static int generation; // one per flush; a context stamps its evaluation with it
   private static IsoZombie[] queue = new IsoZombie[1024];
   private static int count;
   private static volatile boolean failed;

   public static long batched, inline, frames, maxBatch, waitNanos, workNanos, mismatches, checks; // counters for the log
   public static long filterMisses; // devActionEvalCheck: lookups the name filter skipped that did resolve to an impure callback

   /** Marker for a null variable value in a snapshot map. */
   public static final Object NULL = new Object();

   /**
    * Callback variables audited as pure reads of the character (fields, its target's position, static settings), which a
    * worker may call; every other callback a state's conditions reference is read on the game thread into the snapshot
    * before the batch (stock's side effects stay there, in order). Keys are the slot keys, lower case. Stored slots need
    * no entry. Audit of 2026-09-22 (IsoZombie / IsoGameCharacter / IsoMovingObject callback bodies).
    */
   public static final java.util.Set<String> PURE_CALLBACKS = java.util.Set.of(
      "bonfloor", "bknockeddown", "hashitreaction", "bdead", "usephysichitreaction", "bclient", "bmultiplayer", "hitreaction",
      "playerattackposition", "bfakedead", "bstaggerback", "isvehiclecollision", "useragdollvehiclecollision", "canragdoll",
      "isfacingtarget", "realstate", "bumped", "bfalling", "isragdollfall", "issimulationactive", "fallonfront",
      "previousstate", "bcrawling", "issitting", "alerted", "isragdoll", "bbecomecrawler", "bknifedeath",
      "bisreanimatedforgrappleonly", "breanimate", "lasttargetseen", "bpathfindprediction", "bstoplunging", "zombiewalktype", "crawlertype",
      "eatspeed", "lungetimer", "reanimatetimer", "unbalancedlevel", "targetseentime", "bjawstabattach", "hitheadtype",
      "attackdiddamage", "attackoutcome", "bumptype", "collidetype", "footinjurytype", "onbed", "sittingonfurniture",
      "sitonground", "frombehind", "killedbyfall", "isprone", "isgettingup", "footinjury", "angle", "animangle", "twist",
      "targettwist", "shouldertwist", "excesstwist", "numtwistbones", "anglestepdelta", "angletwistdelta", "beenmovingfor",
      "momentumscalar", "isoverencumbered", "criticalhit", "falltime", "aim", "baimatfloor", "aimatflooramount",
      "verticalaimangle", "attackanim", "shoveanim", "stompanim", "isstompanim", "performinghostileanim", "firemode",
      "isanimatingbackwards", "iseditingragdoll", "isupright", "isonback", "bheadlookaround", "lookhorizontal",
      "lookvertical", "hitforce", "hitdir", "hideequippedhandl", "hideequippedhandr", "isunarmed", "ismeleeweaponequipped",
      "israngedweaponequipped", "isrendered", "stateeventdelaytimer",
      // Second audit, 2026-09-22 afternoon: the callbacks the first pass never met. IsoGameCharacter field getters
      // and derived reads (the hand items, the worn items, the action queue, the fall table) with no write and no
      // shared scratch; IsoZombie's network-moving test, small-vehicle test, distance to target, canSeeTarget field
      // and shouldGetUpFromCrawl (state comparisons only).
      "bumpdone", "bumpfall", "bumpfalltype", "bumpstaggered", "rangedweaponempty", "choptreespeed", "movedelta",
      "turndelta", "maxtwist", "isturning", "isturning90", "isturningaround", "bmoving", "hastarget",
      "grapplethrowoutwindow", "grapplethrowoverfence", "grapplethrowintocontainer", "recoilvarx", "recoilvary",
      "shouttype", "shoutitemmodel", "fallspeedseverity", "aimingmode", "hastimedactions",
      "bmovingnetwork", "bistargetissmallvehicle", "distancetotarget", "bcanseetarget", "bgetupfromcrawl",
      // Third audit, 2026-09-22: two grid reads (the square's objects and properties, the sheet-rope walk down).
      "intrees", "canclimbdownrope",
      // 42.21 (2026-09-28): IsoGameCharacter.isNearWallCrouching, a field getter.
      "nearwallcrouching");
      // Deliberately NOT here (they write through a getter or use shared scratch, so the game thread reads them into
      // the snapshot, in stock's order): battack / bhastarget / shouldsprint / getShouldAttack clear the target,
      // bthump drops the thump target, beatbodytarget rescans the corpses, blunge runs a pathfind line test through a
      // shared point pool, turndirection uses the class's static vectors; battackvehicle, bpassengerexposed,
      // bundervehicle, bbeingsteppedon, canclimbdownrope and intrees are not audited yet.

   /**
    * actionSnapshotFilter: the lower-case keys of every callback variable a character registers, read once off the first
    * batched zombie ({@code registerVariableCallbacks} runs in the constructor, so the set is complete and the same for
    * every zombie). A condition operand whose variable name is not in here can only ever resolve to a stored slot — a
    * pure read — so it never needs a snapshot; a name in here but also in {@link #PURE_CALLBACKS} is an audited pure
    * callback. Everything else is the handful of callbacks with side effects, and only those are read per frame.
    */
   private static java.util.Set<String> callbackKeys;

   /** Game thread, once: classify the character's variables for {@link #impureCallback}. */
   public static void initCallbackKeys(Object owner) {
      if (callbackKeys != null || !Config.ACTION_SNAPSHOT_FILTER
            || !(owner instanceof zombie.core.skinnedmodel.advancedanimation.IAnimationVariableRegistry registry)) {
         return;
      }
      java.util.HashSet<String> keys = new java.util.HashSet<>();
      int impure = 0;
      for (zombie.core.skinnedmodel.advancedanimation.IAnimationVariableSlot slot : registry.getGameVariablesInternal().getGameVariables()) {
         if (!(slot instanceof zombie.core.skinnedmodel.advancedanimation.AnimationVariableSlotCallback)) {
            continue;
         }
         String key = slot.getKey();
         if (key == null) {
            continue;
         }
         String lower = key.toLowerCase(java.util.Locale.ENGLISH);
         keys.add(lower);
         if (!PURE_CALLBACKS.contains(lower)) {
            impure++;
         }
      }
      if (keys.isEmpty()) {
         return; // nothing readable yet: stay conservative and try again with the next zombie
      }
      callbackKeys = keys;
      Log.info("actionSnapshotFilter: " + keys.size() + " callback variables, " + impure + " of them read per frame on the game thread");
   }

   /** animatorParallel: true once the callback variables are classified (before that every name counts as impure). */
   public static boolean callbackKeysReady() {
      return callbackKeys != null;
   }

   /** True when a condition operand of this variable name may resolve to a callback with side effects. */
   public static boolean impureCallback(String name) {
      java.util.Set<String> keys = callbackKeys;
      if (keys == null) {
         return true; // not classified yet: snapshot, i.e. the pre-filter behaves like the full walk
      }
      String lower = name.toLowerCase(java.util.Locale.ENGLISH);
      return keys.contains(lower) && !PURE_CALLBACKS.contains(lower) && !(Config.GUARDED_CALLBACKS && GUARDED_CALLBACKS.contains(lower));
   }

   /**
    * guardedCallbacks (2026-09-23): callbacks whose side effect is conditional and rare, with a guard right before it in
    * the IsoZombie override ({@link AnimParallel#conditionalGuard}): bHasTarget / shouldSprint clear a target that has
    * become a reanimated corpse, bPassengerExposed walks the vehicle areas only when the target sits in a vehicle. Off
    * the game thread the guard throws before the effect: the transition evaluation leaves the context unstamped (the
    * game thread evaluates it stock-wise in the apply loop), the animator task finishes the zombie on the game thread.
    * Otherwise they are plain reads, so they need no snapshot read on the game thread.
    */
   public static final java.util.Set<String> GUARDED_CALLBACKS = java.util.Set.of("bhastarget", "shouldsprint", "bpassengerexposed", "battack", "bthump", "blunge", "battackvehicle", "beatbodytarget");

   public static long guardedFallbacks; // evaluations left to the game thread because a guarded callback hit its side effect

   private static final ThreadLocal<java.util.IdentityHashMap<Object, Object>> SNAPSHOT = new ThreadLocal<>();

   /** The snapshot of the context being evaluated on this thread, or null (read the variables directly). */
   public static java.util.IdentityHashMap<Object, Object> currentSnapshot() {
      return SNAPSHOT.get();
   }

   public static void enterSnapshot(java.util.IdentityHashMap<Object, Object> snapshot) {
      SNAPSHOT.set(snapshot);
   }

   public static void exitSnapshot() {
      SNAPSHOT.remove();
   }

   /** Game thread: from here on eligible zombies stop their postUpdateAnimating after the turning flags and queue here. */
   public static void begin() {
      if (!ENABLED || failed) {
         return;
      }
      generation++;
      count = 0;
      active = true;
   }

   /** The generation of the batch being evaluated / applied; a context's evaluation is only applied in the same one. */
   public static int generation() {
      return generation;
   }

   /** True while the game thread applies the batch (phase 2); an evaluation is consumed by update() only then. */
   public static boolean applying() {
      return applying;
   }

   /**
    * Called by IsoGameCharacter.postUpdateAnimating right after the turning flags: true = the rest of it runs in
    * flush(), in the same order, after the parallel evaluation.
    */
   public static boolean submit(IsoGameCharacter character) {
      return submit(character, false);
   }

   /** headOnWorker: queue the zombie before its postUpdateAnimating head; the evaluation task runs the head first. */
   public static boolean submitWithHead(IsoGameCharacter character) {
      return HEAD_ON_WORKER && GtAb.on(GtAb.HEAD_ON_WORKER) && submit(character, true); // devGtAlternate: within-run A/B
   }

   private static final boolean HEAD_ON_WORKER = Config.HEAD_ON_WORKER;

   private static boolean submit(IsoGameCharacter character, boolean headPending) {
      if (!active) {
         return false;
      }
      if (!(character instanceof IsoZombie)) {
         return false;
      }
      IsoZombie zombie = (IsoZombie)character;
      if (GameClient.client || GameServer.server || zombie.isBeingGrappled() || zombie.isGrappling() || zombie.getReanimatedPlayer() != null) {
         inline++;
         return false;
      }
      initCallbackKeys(zombie); // actionSnapshotFilter: classify the callback variables before the first state is cached
      ActionContext context = zombie.getActionContext();
      if (context == null || context.getGroup() == null || !context.pzoptOffThreadSafe()) {
         inline++;
         return false;
      }
      context.pzoptSnapshot(); // the side-effect callbacks, here and now like stock
      zombie.pzoptHeadPending = headPending;
      if (count == queue.length) {
         queue = java.util.Arrays.copyOf(queue, count * 2);
      }
      queue[count++] = zombie;
      batched++;
      return true;
   }

   /** Game thread, after the postupdate loop: evaluate the queued contexts on the workers, then finish the queued zombies in order. */
   public static void flush() {
      if (!active) {
         return;
      }
      active = false;
      if (count == 0) {
         return;
      }
      frames++;
      if (count > maxBatch) {
         maxBatch = count;
      }
      if (AnimParallel.pipelined()) {
         // animatorPipeline: the evaluations run on the workers alone; the apply loop takes each zombie as soon as its
         // evaluation is done (AnimParallel.apply step 1) instead of this thread evaluating a share and then applying
         FrameBatch.runAsync(count, i -> queue[i].pzoptEvalTask(), ActionEval::evalDone);
         applying = true;
         GameTime pipelineTime = GameTime.getInstance();
         float pipelineMultiplier = pipelineTime.perObjectMultiplier;
         try {
            AnimParallel.apply(queue, count, true);
         } finally {
            pipelineTime.perObjectMultiplier = pipelineMultiplier; // see the multiplier note below
            applying = false;
            java.util.Arrays.fill(queue, 0, count, null);
            count = 0;
         }
         return;
      }
      long w0 = FrameBatch.workNanos;
      long q0 = FrameBatch.waitNanos;
      Throwable t = FrameBatch.run(count, i -> queue[i].pzoptEvalTask());
      workNanos += FrameBatch.workNanos - w0;
      waitNanos += FrameBatch.waitNanos - q0;
      if (t != null && !failed) {
         failed = true; // the contexts that did not get their stamp evaluate on the game thread below; stock path from the next frame on
         Log.warn("actionEvalParallel: transition evaluation failed on a worker, batching off: " + t);
      }
      applying = true;
      // Each bucket's postupdate ran its zombies at perObjectMultiplier = the bucket's frame mod and reset it to 1 at
      // the end; the rest of postUpdateAnimating (animation advance) must see the same multiplier, or a zombie on a
      // reduced simulation level (off-screen: SIXTEENTH, frame mod 16) advances its thump animation 1 frame per update
      // while ThumpState counts the strikes over 16: every strike counted up to 16 times (bursts of 8 / 16 thumps).
      GameTime gameTime = GameTime.getInstance();
      float multiplier = gameTime.perObjectMultiplier;
      try {
         if (AnimParallel.enabled()) {
            AnimParallel.apply(queue, count, false); // animatorParallel: the animators and models of the queue on the workers (it sets the multiplier per zombie too)
         } else {
            for (int i = 0; i < count; i++) {
               useMultiplier(queue[i]);
               queue[i].pzoptPostUpdateAnimatingRest();
            }
         }
      } finally {
         gameTime.perObjectMultiplier = multiplier;
         applying = false;
         java.util.Arrays.fill(queue, 0, count, null);
         count = 0;
      }
   }

   /**
    * Game thread: the perObjectMultiplier the zombie's bucket ran its postupdate with (its simulation level's frame mod),
    * for the rest of its postUpdateAnimating (see the note in flush). Callers restore the frame's value afterwards.
    */
   public static void useMultiplier(IsoZombie zombie) {
      if (!Config.DEV_ACTION_EVAL_UNIT_MULTIPLIER) {
         GameTime.getInstance().perObjectMultiplier = zombie.getCurrentSimulationLevel().getFrameMod();
      }
   }

   private static void evalDone(Throwable t) {
      if (t != null && !failed) {
         failed = true;
         Log.warn("actionEvalParallel: transition evaluation failed on a worker, batching off: " + t);
      }
   }

   /** One line for the periodic FBORenderCell log. */
   public static String describe() {
      return "action eval: frames=" + frames + " batched=" + batched + " inline=" + inline + " max=" + maxBatch
            + " work ms=" + (workNanos / 1_000_000L) + " wait ms=" + (waitNanos / 1_000_000L)
            + " guardedFallbacks=" + guardedFallbacks
            + (Config.DEV_ACTION_EVAL_CHECK ? " checked=" + checks + " mismatches=" + mismatches + " filterMisses=" + filterMisses : "") + (failed ? " FAILED" : "");
   }
}
