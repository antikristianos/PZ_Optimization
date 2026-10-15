package pzopt;

/** pzopt.Pacing.queueMinPct: the frame queue's fixed D (frameQueueMinPct) only at caps of frameQueueFixedMinFps or more (round 11). */
public class PacingQueueFloorTest {
   public static void main(String[] args) {
      Check.check(Config.FRAME_QUEUE_FIXED_MIN_FPS == 100, "frameQueueFixedMinFps defaults to 100");
      Check.check(Pacing.queueMinPct(1_000_000_000L / 120) == Config.FRAME_QUEUE_MIN_PCT, "120 fps cap: frameQueueMinPct (fixed D)");
      Check.check(Pacing.queueMinPct(1_000_000_000L / 240) == Config.FRAME_QUEUE_MIN_PCT, "240 fps cap: frameQueueMinPct");
      Check.check(Pacing.queueMinPct(1_000_000_000L / 100) == Config.FRAME_QUEUE_MIN_PCT, "100 fps cap: the threshold itself is fixed");
      Check.check(Pacing.queueMinPct(1_000_000_000L / 99) == Config.FRAME_QUEUE_LOW_CAP_MIN_PCT, "99 fps cap: the low-cap floor");
      Check.check(Pacing.queueMinPct(1_000_000_000L / 60) == 60, "60 fps cap: the adaptive 60 % floor (10 ms)");
      Check.check(Pacing.queueMinPct(1_000_000_000L / 30) == 60, "30 fps cap: the adaptive 60 % floor (20 ms)");
      System.out.println("PacingQueueFloorTest ok");
   }
}
