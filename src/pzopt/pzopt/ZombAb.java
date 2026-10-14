package pzopt;

/**
 * The within-run A/B keys of the zombie redesign pass (2026-10-13, f8-zomb2), alternated by GtAb's two groups but with their
 * own mask, so this pass takes no more of GtAb's 64 bits than one: every name here maps to GtAb bit 40 in GtAb's own parse
 * (that turns GtAb's timers on, and bit 40 is asked by nobody else), and {@link #on} reads GtAb's phase of the group the key
 * is listed in ({@code devGtAlternateKeys} / {@code devGtAlternateKeys2}).
 */
public final class ZombAb {
   private ZombAb() {
   }

   private static final String[] NAMES = {"zombNoop", "separateAsync", "postupdateMoveStream", "entityUpdateStream", "separateEdgeCache",
      "postupdateNoopSkip", "postupdateFuse", "luaSilentSkip", "frameThreadsAlt", "entityUpdateAlerted", "postupdateClean", "postupdateEligibleOnWorker", "postupdateVehicleOnWorker"};

   public static final long NOOP = 1L; // zombNoop: no code reads it (an alternation of it turns the timers on, both halves alike)
   public static final long SEP_ASYNC = 1L << 1; // separateAsync
   public static final long PU_MOVE_STREAM = 1L << 2; // postupdateMoveStream (dropped; the name stays so old run arguments parse)
   public static final long STREAM = 1L << 3; // entityUpdateStream
   public static final long EDGE_CACHE = 1L << 4; // separateEdgeCache
   public static final long PU_NOOP = 1L << 5; // postupdateNoopSkip
   public static final long PU_FUSE = 1L << 6; // postupdateFuse
   public static final long LUA_SILENT = 1L << 7; // luaSilentSkip
   public static final long THREADS_ALT = 1L << 8; // frameThreadsAlt: the "on" half uses frameThreadsAlt frame workers
   public static final long ALERTED = 1L << 9; // entityUpdateAlerted
   public static final long CLEAN = 1L << 10; // postupdateClean
   public static final long EXTRA1 = 1L << 11; // postupdateEligibleOnWorker
   public static final long EXTRA2 = 1L << 12; // postupdateVehicleOnWorker

   /** GtAb.parse: a name of this pass shares GtAb bit 40 (timers on); 0 for any other name. */
   static long bit(String name) {
      return own(name) != 0L ? 1L << 40 : 0L;
   }

   private static long own(String name) {
      for (int i = 0; i < NAMES.length; i++) {
         if (NAMES[i].equals(name)) {
            return 1L << i;
         }
      }
      return 0L;
   }

   private static long parse(String keys) {
      long m = 0L;
      if (keys == null || keys.isEmpty()) {
         return 0L;
      }
      for (String k : keys.split(",")) {
         m |= own(k.trim());
      }
      return m;
   }

   private static final long MASK1 = Config.DEV_GT_ALTERNATE > 0 ? parse(Config.DEV_GT_ALTERNATE_KEYS) : 0L;
   private static final long MASK2 = Config.DEV_GT_ALTERNATE2 > 0 ? parse(Config.DEV_GT_ALTERNATE_KEYS2) : 0L;

   /** False in the off half of the alternation group a listed key belongs to: the caller takes its old path this frame. */
   public static boolean on(long bit) {
      return ((MASK1 & bit) == 0L || !GtAb.offPhase1()) && ((MASK2 & bit) == 0L || !GtAb.offPhase2());
   }
}
