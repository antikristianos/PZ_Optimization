package pzopt;

import zombie.GameWindow;
import zombie.iso.IsoGridSquare;

/**
 * The "is this square blocked to that neighbour" answers of the separation pass, cached for one frame
 * ({@code separateFast}, 2026-09-22).
 *
 * <p>{@code IsoMovingObject.separate()} asks {@code current.isBlockedTo(nav[i])} for each of the eight neighbouring
 * squares that hold a moving object, once per character per frame. The answer only depends on the geometry of the two
 * squares and, for a diagonal, of the two squares between them (walls, windows, doors, stair tops) — never on the
 * character asking. On the Louisville horde several hundred zombies stand on a few hundred squares, so the same
 * answer was computed three to eight times a frame; {@code isBlockedTo} and its recursive diagonal calls were 1.7 % of
 * the game thread.
 *
 * <p>A direct-mapped table keyed by square identity holds, per square and per frame, which of the eight answers have
 * been computed and what they were. Entries are computed lazily (a square whose neighbours hold nobody still costs
 * nothing) and expire with the frame, so a door that opens mid-frame is seen on the next one — a separation push is
 * never visible at that granularity. A collision in the table simply recomputes.
 */
public final class SeparateMask {
   private SeparateMask() {
   }

   private static final int SIZE = 8192;
   private static final int MASK = SIZE - 1;

   private static final IsoGridSquare[] KEYS = new IsoGridSquare[SIZE];
   private static final int[] VALUES = new int[SIZE]; // low 8 bits: blocked; bits 8-15: computed
   private static final int[] STAMPS = new int[SIZE];

   public static long hits, misses, collideSpills;

   /** 42.21's isBlockedTo, through pzopt.EdgeFast when edgeTestFast is on. */
   private static boolean blockedNow(IsoGridSquare square, IsoGridSquare other) {
      return Config.EDGE_TEST_FAST && Overrides.enabled() ? EdgeFast.isBlockedTo(square, other) : square.isBlockedTo(other);
   }

   /** Whether {@code square} is blocked to its {@code i}-th surrounding square, from this frame's cache. */
   /** A frame worker's own table (separateMemo, 2026-10-13): used only while the separation batch runs, when the grid is read-only. */
   private static final class Table {
      final IsoGridSquare[] keys = new IsoGridSquare[SIZE];
      final int[] values = new int[SIZE];
      final int[] stamps = new int[SIZE];
   }

   private static final ThreadLocal<Table> TABLES = ThreadLocal.withInitial(Table::new);

   public static boolean blocked(IsoGridSquare square, IsoGridSquare other, int i) {
      IsoGridSquare[] keysT = KEYS;
      int[] valuesT = VALUES;
      int[] stampsT = STAMPS;
      if (Thread.currentThread() != GameWindow.gameThread) {
         if (!SeparateBatch.memoActive()) {
            // the table is the game thread's; a mod that updates characters on other threads (PZMulticore) asks directly
            return blockedNow(square, other);
         }
         // separateMemo: inside the separation batch the grid does not change, so a worker keeps its own table for the frame
         Table t = TABLES.get();
         keysT = t.keys;
         valuesT = t.values;
         stampsT = t.stamps;
      }
      int slot = (System.identityHashCode(square) >>> 4) & MASK;
      int value = valuesT[slot];
      if (keysT[slot] != square || stampsT[slot] != FrameTick.frame()) {
         keysT[slot] = square;
         stampsT[slot] = FrameTick.frame();
         value = 0;
      }

      int bit = 1 << i;
      if ((value & bit << 8) != 0) {
         hits++;
         valuesT[slot] = value;
         return (value & bit) != 0;
      }

      misses++;
      boolean blocked = blockedNow(square, other);
      value |= bit << 8;
      if (blocked) {
         value |= bit;
      }

      valuesT[slot] = value;
      return blocked;
   }

   /** One line for the periodic FBORenderCell log. */
   public static String describe() {
      long total = hits + misses;
      return "separate mask: asks=" + total + " computed=" + misses + (total > 0 ? " (" + 100L * misses / total + " %)" : "")
            + " spills=" + collideSpills + " | " + SeparateBatch.describe();
   }
}
