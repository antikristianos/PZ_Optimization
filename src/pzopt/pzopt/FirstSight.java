package pzopt;

import zombie.characterTextures.CharacterSmartTexture;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoZombie;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.model.ModelInstanceTextureCreator;

/**
 * zombieFirstSightSprite (f8-sight pass 2026-10-14; approved by the maintainer as an opt-in, off by default): when a horde
 * comes into view, ~130-370 zombies take a 3D model within a second and each makes its clothing composite on the render
 * thread in the frame it is first drawn (~120 us each, up to 88 in one frame: 10 ms). Stock draws nothing of a zombie
 * until its composite is made (ModelSlotRenderData.checkReady: not ready and nothing ready from before).
 *
 * <p>With the key on (and zombieLodDynamic, whose crossfade it uses), the cull loop (nearest first) grants the first
 * composites of the frame up to {@code zombieCompositeBudgetUs} of render-thread time (the count follows the measured
 * cost of one, at least one a frame); a zombie past it is drawn this frame as its lit flat sprite (the LOD's atlas
 * sprite at {@code zombieLodSpriteLight}) and its model draw skips the composite (ModelSlotRenderData.pzoptSkipTexture:
 * nothing drawn, as stock while not ready). When its turn comes its model fades in over the sprite like the LOD's
 * sprite -> model switch ({@code zombieLodFadeMs}). Only a model instance that never had a composite waits: a zombie
 * whose clothes change (blood, holes) keeps the stock order.
 *
 * <p>The census (devFirstSightCensus, or instrumented runs with the key on): composites a frame and their
 * render-thread time a frame (histograms, max), grants, deferred zombie-frames, the cost estimate.
 */
public final class FirstSight {
   private FirstSight() {
   }

   public static final boolean ON = Config.ZOMBIE_FIRST_SIGHT_SPRITE && Overrides.enabled();
   public static final boolean CENSUS = Config.DEV_FIRST_SIGHT_CENSUS || Config.INSTRUMENT && ON;
   public static final boolean TIMING = ON || CENSUS;

   // ---- game thread ----
   /** the cull pass this frame (ModelSlotRenderData.init compares a zombie's deferral stamp with it) */
   public static volatile int frame;
   private static int grants;
   private static int limit = 1;
   private static boolean culling;
   public static long grantedTotal, deferredTotal, maxDeferred;
   private static int deferredThisFrame;

   // ---- render thread ----
   /** the render-thread cost of one composite (ns), exponential average of the made ones */
   private static volatile long costNs = 120_000L;

   public static boolean active() {
      return ON && ZombieLod.active();
   }

   /** Cull loop, once a frame before the loop (zombieLodDynamic on). */
   public static void beginCull() {
      if (!ON) {
         return;
      }
      if (deferredThisFrame > maxDeferred) {
         maxDeferred = deferredThisFrame;
      }
      deferredThisFrame = 0;
      frame++;
      grants = 0;
      long c = Math.max(5_000L, costNs);
      limit = (int)Math.max(1L, Config.ZOMBIE_COMPOSITE_BUDGET_US * 1000L / c);
      culling = true;
   }

   /** Cull loop, after a zombie took (or kept) its 3D slot: its place in the nearest-first order. */
   public static void won(IsoZombie z, int rank) {
      z.pzoptSightRank = rank;
   }

   private static final java.util.ArrayList<IsoZombie> pend = new java.util.ArrayList<>();

   /** CharDraw.start, before its tests: whether any zombie about to draw its model waits for its first composite. */
   public static boolean anyPending(java.util.List<IsoZombie> candidates) {
      if (!culling) {
         return false;
      }
      for (int i = 0; i < candidates.size(); i++) {
         if (pending(candidates.get(i))) {
            return true;
         }
      }
      return false;
   }
   private static final java.util.Comparator<IsoZombie> BY_RANK = java.util.Comparator.comparingInt(z -> z.pzoptSightRank);

   /**
    * CharDraw.start (game thread), the zombies about to draw their model this frame, before their draw data is built:
    * the first composites of the frame go to the nearest waiting ones (cull rank) up to the budget, the rest wait as
    * sprites. Granting here, among the zombies really drawn, keeps a grant from going to one off screen.
    */
   public static void grant(java.util.ArrayList<IsoZombie> drawn) {
      if (!culling || !active()) {
         return;
      }
      pend.clear();
      for (int i = 0; i < drawn.size(); i++) {
         IsoZombie z = drawn.get(i);
         if (pending(z)) {
            pend.add(z);
         }
      }
      if (pend.size() > limit) {
         pend.sort(BY_RANK);
      }
      for (int i = 0; i < pend.size(); i++) {
         IsoZombie z = pend.get(i);
         // every waiting zombie is its sprite this frame: the LOD's sprite -> model crossfade held at its start (the
         // granted one too: its model fades in from the frame after its composite is made, and when the render thread's
         // budget makes it wait once more it stays the sprite)
         z.pzoptSightPend = frame;
         z.pzoptLodFade = 2;
         z.pzoptLodFadeStartNs = ZombieLod.frameNs();
         z.pzoptLodModelAlpha = 0.0F;
         z.pzoptLodSpriteAlpha = 1.0F;
         if (grants < limit) {
            grants++;
            grantedTotal++;
         } else {
            z.pzoptSightDefer = frame;
            deferredTotal++;
            deferredThisFrame++;
         }
      }
      pend.clear();
   }

   /** The zombie's model instance has never had its clothing composite (stock would draw nothing of it until it has). */
   static boolean pending(IsoZombie z) {
      if (z.legsSprite == null) {
         return false;
      }
      ModelManager.ModelSlot slot = z.legsSprite.modelSlot;
      if (slot == null || slot.model == null || slot.model.tex instanceof CharacterSmartTexture) {
         return false;
      }
      if (z.pzoptUpdateModelTextures()) {
         return true; // the render pre-pass makes its texture creator this frame
      }
      ModelInstanceTextureCreator tc = z.getTextureCreator();
      return tc != null && !tc.isRendered();
   }

   /** Cull loop: this zombie was drawn as its sprite last frame waiting for its composite (losing its slot, it stays the sprite: no fade-out of a model that never drew). */
   public static boolean waiting(IsoZombie z) {
      return ON && z.pzoptSightPend >= frame - 1 && z.pzoptSightPend >= 0;
   }

   /** ModelSlotRenderData.init: this character's first composite may be made this frame within the render thread's budget. */
   public static boolean eligible(IsoGameCharacter chr) {
      return ON && chr instanceof IsoZombie z && z.pzoptSightPend == frame;
   }

   private static final long BUDGET_NS = Config.ZOMBIE_COMPOSITE_BUDGET_US * 1000L;
   public static long renderWaits;

   /** ModelSlotRenderData.checkReady (render thread): the frame's first composites already took the budget (at least one is made a frame). */
   public static boolean overBudget() {
      if (frameComposites > 0 && frameCompositeNs >= BUDGET_NS) {
         renderWaits++;
         return true;
      }
      return false;
   }

   /** ModelSlotRenderData.init (game thread or a draw-prep worker): this character's composite waits this frame. */
   public static boolean deferred(IsoGameCharacter chr) {
      return ON && chr instanceof IsoZombie z && z.pzoptSightDefer == frame;
   }

   // ---- render thread ----
   private static int frameComposites;
   private static long frameCompositeNs;
   private static final long[] histCount = new long[8]; // 0, 1, 2-3, 4-7, 8-15, 16-31, 32-63, 64+ composites a frame
   private static final long[] histMs = new long[7]; // <0.25, <0.5, <1, <2, <4, <8, >=8 ms a frame
   private static long composites, compositeNs, maxFrameComposites, maxFrameNs, notReady;
   private static long lastLog = System.nanoTime();

   /** render thread counters (TextureCombiner): what a slow composite did */
   public static long combines, newTex, newFbo;
   private static long c0, t0n, f0;
   private static int slowLogged;

   /** ModelSlotRenderData.checkReady, before a texture creator's render(). */
   public static void composing() {
      c0 = combines;
      t0n = newTex;
      f0 = newFbo;
   }

   /** ModelSlotRenderData.checkReady, after a texture creator's render(). */
   public static void composed(long ns, boolean rendered) {
      if (CENSUS && ns > 2_000_000L && slowLogged < 40) {
         slowLogged++;
         Log.info("first sight: slow composite " + ns / 1000L + " us, rendered " + rendered + ", combines " + (combines - c0) + ", new textures " + (newTex - t0n) + ", new framebuffers " + (newFbo - f0) + ", frame composites before " + frameComposites);
      }
      if (!rendered) {
         notReady++;
         return;
      }
      composites++;
      compositeNs += ns;
      frameComposites++;
      frameCompositeNs += ns;
      costNs = costNs + (Math.min(ns, 500_000L) - costNs) / 8L; // (a first-of-its-kind composite can take ms: capped so one does not stop the next frames)
   }

   private static boolean prewarmed;

   /** renderPrewarm: render thread, the first swap once the world exists. */
   private static void prewarm() {
      prewarmed = true;
      long t0 = System.nanoTime();
      try {
         int parts = Config.DEV_PREWARM_PARTS; // dev bisection: 1 horde batch programs / buffers, 2 atlas batch, 4 combiner pool, 8 composite shaders
         if ((parts & 1) != 0) {
            HordeBatch.prewarm();
         }
         if ((parts & 2) != 0) {
            AtlasBatch.prewarm();
         }
         if ((parts & 4) != 0 && CombinerPool.ON) {
            CombinerPool.budgetBytes();
            zombie.core.textures.TextureCombiner.instance.pzoptPrewarm(); // the composite sizes the reserve then fills
         }
         if ((parts & 8) != 0) {
            zombie.core.textures.SmartTexture st = new zombie.core.textures.SmartTexture();
            st.add(zombie.core.textures.Texture.getErrorTexture()); // the combiner's static shaders (made at the first add)
            st.clear();
         }
      } catch (Throwable t) {
         Log.warn("render prewarm: " + t);
      }
      Log.info("render prewarm: " + (System.nanoTime() - t0) / 1000L + " us");
   }

   /** Render thread, right after the swap. */
   public static void afterSwap() {
      if (Config.RENDER_PREWARM && !prewarmed && Overrides.enabled() && zombie.iso.IsoWorld.instance != null && zombie.iso.IsoWorld.instance.currentCell != null) {
         prewarm();
      }
      if (!TIMING) {
         return;
      }
      int c = frameComposites;
      long ns = frameCompositeNs;
      frameComposites = 0;
      frameCompositeNs = 0L;
      if (!CENSUS) {
         return;
      }
      histCount[c == 0 ? 0 : Math.min(7, 32 - Integer.numberOfLeadingZeros(c))]++;
      if (c > 0) {
         double ms = ns / 1.0e6;
         histMs[ms < 0.25 ? 0 : ms < 0.5 ? 1 : ms < 1 ? 2 : ms < 2 ? 3 : ms < 4 ? 4 : ms < 8 ? 5 : 6]++;
      }
      if (c > maxFrameComposites) {
         maxFrameComposites = c;
      }
      if (ns > maxFrameNs) {
         maxFrameNs = ns;
      }
      long now = System.nanoTime();
      if (now - lastLog < 10_000_000_000L) {
         return;
      }
      lastLog = now;
      Log.info("first sight: composites " + composites + " (" + compositeNs / 1_000_000L + " ms render thread, mean "
         + (composites == 0 ? 0 : compositeNs / composites / 1000L) + " us, cost estimate " + costNs / 1000L + " us), not ready " + notReady
         + ", per frame max " + maxFrameComposites + " / " + String.format(java.util.Locale.ROOT, "%.2f", maxFrameNs / 1.0e6)
         + " ms; frames with 0/1/2-3/4-7/8-15/16-31/32-63/64+ composites " + join(histCount) + "; frames by composite time <0.25/<0.5/<1/<2/<4/<8/8+ ms "
         + join(histMs) + (ON ? "; granted " + grantedTotal + ", deferred zombie-frames " + deferredTotal + " (max " + maxDeferred + " a frame), render-budget waits " + renderWaits + ", limit now " + limit : ""));
   }

   private static String join(long[] a) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < a.length; i++) {
         if (i > 0) {
            sb.append('/');
         }
         sb.append(a[i]);
      }
      return sb.toString();
   }
}
