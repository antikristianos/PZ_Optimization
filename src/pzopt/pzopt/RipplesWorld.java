package pzopt;

import java.nio.ShortBuffer;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

/**
 * pzopt.Ripples' {@code world} mode: the rings binned once, in world space, for their whole life. A ring is written at its
 * footstep into every tile of a toroidal grid (2 x 2 squares a tile, 64 x 64 tiles: 128 squares) its reach at
 * {@code STAGE} s overlaps (widened to its final reach when it gets there: young rings are small and most of a ring's
 * life is young)
 * overlaps, as one RGBA16 texel of the tile's {@code PER_TILE} (its centre relative to the tile, its birth on a 64 s clock,
 * level / size / amplitude; the oldest gives way in a full tile), and leaves them when it dies. The shader finds its
 * pixel's world square and tile, then reads the tile's texels until an empty one: one fetch a ring. A frame costs the game
 * thread only its births and deaths (no per-frame binning: the camera moves freely), the render thread only the changed
 * tiles' texels (one rectangle). Nothing at all while no ring lives.
 */
final class RipplesWorld {
   private RipplesWorld() {
   }

   static final int POOL = 384, WT = 2, WTILES = 64, SPAN = WT * WTILES, PER_TILE = 8;
   // s: a ring is listed with its reach at this age until then, with its final reach after (devRipplesWorldStage, off:
   // measured dearer on the GPU, 28 vs 16.5 us, docs/findings-puddle-ripples-2026-10-10.md)
   static final float STAGE = Config.DEV_RIPPLES_WORLD_STAGE ? 0.6F : Ripples.LIFE * Ripples.BIG;
   static final float OFF_RANGE = 16F, CLOCK = 64F, PACK = 128F; // texel encodings: centre offset +-8 squares, birth mod 64 s, packed / 128

   // game thread: the ring pool (lifetimes and the tiles each ring is in)
   private static final float[] tx = new float[POOL], ty = new float[POOL], born = new float[POOL], packed = new float[POOL], life = new float[POOL];
   private static final int[] bx0 = new int[POOL], by0 = new int[POOL], bn = new int[POOL], bm = new int[POOL]; // tile box: first x, y (unwrapped), count x, y
   private static final boolean[] alive = new boolean[POOL];
   private static int head, tail, count, grow; // grow: the next ring to widen (rings widen in birth order)
   private static final boolean[] grown = new boolean[POOL];
   private static float boxX0, boxY0, boxX1, boxY1;
   private static int boxAge;
   // the tiles: per entry the ring's slot (game thread only) and its texel (RGBA16, uploaded)
   private static final short[] ids = new short[WTILES * WTILES * PER_TILE];
   private static final short[] texels = new short[WTILES * WTILES * PER_TILE * 4];
   private static final byte[] tcount = new byte[WTILES * WTILES];
   private static final int[] tileStamp = new int[WTILES * WTILES];
   private static int stamp = 1;
   private static final int[] chTiles = new int[WTILES * WTILES];
   private static int nChTiles;
   static long births, deaths, evictions, maxCount, tileWrites, widened;

   private static String wantedFor;
   private static boolean wantedNow;

   static boolean wanted() {
      String m = Config.RIPPLES_MODE;
      if (m != wantedFor) { // the live key changed (a new string): look again
         wantedFor = m;
         wantedNow = "world".equals(m) || Config.DEV_RIPPLES_ALTERNATE > 0 && Config.DEV_RIPPLES_MODES.contains("world");
      }
      return wantedNow;
   }

   /** Ring entries over all tiles (the tests: zero once every ring died). */
   static int listed() {
      int n = 0;
      for (byte c : tcount) {
         n += c;
      }
      return n;
   }

   static String stats() {
      return "world: births=" + births + " deaths=" + deaths + " widened=" + widened + " evictions=" + evictions + " max_live=" + maxCount + " tile_writes=" + tileWrites;
   }

   private static void touchTile(int t) {
      if (tileStamp[t] != stamp) {
         tileStamp[t] = stamp;
         chTiles[nChTiles++] = t;
      }
   }

   private static short unorm(float v) {
      return (short)Math.max(0, Math.min(65535, Math.round(v * 65535F)));
   }

   private static float finalReach(boolean big) {
      return (RipplesModel.reach0 + RipplesModel.reachV * (float)Math.sqrt(Ripples.LIFE)) * (big ? Ripples.BIG : 1F);
   }

   /** Game thread, Ripples.emit: a new ring (world square x, y), born at {@code t} on Ripples' clock. */
   static void birth(float x, float y, int z, float amp, boolean big, float t) {
      if (!wanted()) {
         return;
      }
      int s = head;
      if (alive[s]) {
         kill(s); // the pool is full: the oldest ring gives way
         tail = (s + 1) % POOL;
         if (grow == s) {
            grow = tail;
         }
      }
      head = (head + 1) % POOL;
      count++;
      maxCount = Math.max(maxCount, count);
      births++;
      alive[s] = true;
      tx[s] = x;
      ty[s] = y;
      born[s] = t;
      packed[s] = z * 4F + (big ? 2F : 0F) + Math.min(0.999F, amp);
      life[s] = Ripples.LIFE * (big ? Ripples.BIG : 1F);
      float reach = finalReach(big);
      float stageReach = Config.DEV_RIPPLES_WORLD_STAGE ? (RipplesModel.reach0 + RipplesModel.reachV * (float)Math.sqrt(STAGE)) * (big ? Ripples.BIG : 1F) : reach;
      grown[s] = !Config.DEV_RIPPLES_WORLD_STAGE;
      if (count == 1) {
         boxX0 = x - reach;
         boxY0 = y - reach;
         boxX1 = x + reach;
         boxY1 = y + reach;
      } else {
         boxX0 = Math.min(boxX0, x - reach);
         boxY0 = Math.min(boxY0, y - reach);
         boxX1 = Math.max(boxX1, x + reach);
         boxY1 = Math.max(boxY1, y + reach);
      }
      int tx0 = (int)Math.floor((x - stageReach) / WT), tx1 = (int)Math.floor((x + stageReach) / WT);
      int ty0 = (int)Math.floor((y - stageReach) / WT), ty1 = (int)Math.floor((y + stageReach) / WT);
      bx0[s] = tx0;
      by0[s] = ty0;
      bn[s] = Math.min(WTILES, tx1 - tx0 + 1);
      bm[s] = Math.min(WTILES, ty1 - ty0 + 1);
      list(s, tx0, ty0, bn[s], bm[s], -1, -1, 0, 0);
   }

   /** Writes ring s into the tiles of the box (unwrapped tile x0, y0, n x m), skipping those of the box it was in before. */
   private static void list(int s, int x0, int y0, int n, int m, int ox0, int oy0, int on, int om) {
      float x = tx[s], y = ty[s];
      short id = (short)(s + 1);
      short b = unorm((float)(Math.floorMod((long)Math.floor(born[s] * 65536.0), (long)CLOCK * 65536) / 65536.0 / CLOCK));
      short a = unorm(packed[s] / PACK);
      for (int j = 0; j < m; j++) {
         int tyU = y0 + j;
         for (int i = 0; i < n; i++) {
            int txU = x0 + i;
            if (txU >= ox0 && txU < ox0 + on && tyU >= oy0 && tyU < oy0 + om) {
               continue; // already there
            }
            int ti = Math.floorMod(tyU, WTILES) * WTILES + Math.floorMod(txU, WTILES);
            int base = ti * PER_TILE;
            int c = tcount[ti];
            int k;
            if (c < PER_TILE) {
               k = c;
               tcount[ti] = (byte)(c + 1);
            } else {
               // full: the oldest ring of the tile gives way here (it stays in its other tiles)
               k = 0;
               float ob = Float.MAX_VALUE;
               for (int e = 0; e < PER_TILE; e++) {
                  float bb = born[(ids[base + e] & 0xFFFF) - 1];
                  if (bb < ob) {
                     ob = bb;
                     k = e;
                  }
               }
               evictions++;
            }
            ids[base + k] = id;
            int o = (base + k) * 4;
            // the centre relative to this tile's own corner (the shader's tile corner has the same lift as its pixel)
            texels[o] = unorm((x - txU * WT) / OFF_RANGE + 0.5F);
            texels[o + 1] = unorm((y - tyU * WT) / OFF_RANGE + 0.5F);
            texels[o + 2] = b;
            texels[o + 3] = a;
            tileWrites++;
            touchTile(ti);
         }
      }
   }

   /** Ring s reached STAGE: listed in the rest of its final box. */
   private static void widen(int s) {
      grown[s] = true;
      boolean big = packed[s] % 4F >= 2F;
      float reach = finalReach(big);
      int tx0 = (int)Math.floor((tx[s] - reach) / WT), tx1 = (int)Math.floor((tx[s] + reach) / WT);
      int ty0 = (int)Math.floor((ty[s] - reach) / WT), ty1 = (int)Math.floor((ty[s] + reach) / WT);
      int n = Math.min(WTILES, tx1 - tx0 + 1), m = Math.min(WTILES, ty1 - ty0 + 1);
      list(s, tx0, ty0, n, m, bx0[s], by0[s], bn[s], bm[s]);
      bx0[s] = tx0;
      by0[s] = ty0;
      bn[s] = n;
      bm[s] = m;
      widened++;
   }

   private static void kill(int s) {
      alive[s] = false;
      count--;
      deaths++;
      short id = (short)(s + 1);
      for (int j = 0; j < bm[s]; j++) {
         int tyW = Math.floorMod(by0[s] + j, WTILES);
         for (int i = 0; i < bn[s]; i++) {
            int ti = tyW * WTILES + Math.floorMod(bx0[s] + i, WTILES);
            int base = ti * PER_TILE;
            int c = tcount[ti];
            for (int k = 0; k < c; k++) {
               if (ids[base + k] == id) {
                  int last = c - 1;
                  ids[base + k] = ids[base + last]; // the last entry takes its place: the list stays packed
                  ids[base + last] = 0;
                  System.arraycopy(texels, (base + last) * 4, texels, (base + k) * 4, 4);
                  java.util.Arrays.fill(texels, (base + last) * 4, (base + last) * 4 + 4, (short)0);
                  tcount[ti] = (byte)last;
                  tileWrites++;
                  touchTile(ti);
                  break;
               }
            }
         }
      }
   }

   /** Game thread, once a frame (whatever the frame's mode): the rings past their life leave their tiles. */
   static void tick() {
      if (count == 0) {
         return;
      }
      float now = (float)Ripples.clock;
      while (grow != head && (!alive[grow] || grown[grow] || now - born[grow] >= STAGE)) {
         if (alive[grow] && !grown[grow]) {
            widen(grow);
         }
         grow = (grow + 1) % POOL;
      }
      while (count > 0 && alive[tail] && now - born[tail] > life[tail] + 0.15F) {
         kill(tail);
         tail = (tail + 1) % POOL;
      }
      while (count > 0 && !alive[tail]) {
         tail = (tail + 1) % POOL; // slots freed by eviction
      }
   }

   /** Game thread, Ripples.build in world mode: this frame's changed tiles into the frame. Returns whether to send it. */
   static boolean build(Ripples.Frame fr) {
      if (++boxAge >= 16 && count > 0) {
         // the box only grows at births: shrink it to the live rings now and then
         boxAge = 0;
         float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
         for (int k = 0, s = tail; k < POOL && (k == 0 || s != head); k++, s = (s + 1) % POOL) {
            if (alive[s]) {
               float r = finalReach(packed[s] % 4F >= 2F);
               x0 = Math.min(x0, tx[s] - r);
               y0 = Math.min(y0, ty[s] - r);
               x1 = Math.max(x1, tx[s] + r);
               y1 = Math.max(y1, ty[s] + r);
            }
         }
         boxX0 = x0;
         boxY0 = y0;
         boxX1 = x1;
         boxY1 = y1;
      }
      fr.bx0 = boxX0;
      fr.by0 = boxY0;
      fr.bx1 = boxX1;
      fr.by1 = boxY1;
      Ripples.WorldFrame w = fr.world;
      w.now = (float)(Ripples.clock % CLOCK);
      w.nTiles = nChTiles;
      for (int k = 0; k < nChTiles; k++) {
         int ti = chTiles[k];
         w.tileIdx[k] = ti;
         System.arraycopy(texels, ti * PER_TILE * 4, w.tileTexels, k * PER_TILE * 4, PER_TILE * 4);
      }
      nChTiles = 0;
      stamp++;
      w.any = count > 0;
      return w.any || w.nTiles > 0;
   }

   // ------------------------------------------------------------------------------------------------ render thread

   private static final short[] mirror = new short[WTILES * WTILES * PER_TILE * 4];
   private static int tileTex, pbo;
   private static final ShortBuffer BUF = BufferUtils.createShortBuffer(WTILES * WTILES * PER_TILE * 4);
   static long uploadBytes, uploadCalls;

   /** Render thread, Ripples.Begin: the frame's changed tiles into the mirror, then their bounding rectangle to the GPU. */
   static void render(Ripples.Frame f) {
      Ripples.WorldFrame w = f.world;
      if (tileTex == 0) {
         tileTex = GL11.glGenTextures();
         GL11.glBindTexture(GL11.GL_TEXTURE_2D, tileTex);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
         GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
         BUF.clear();
         BUF.put(mirror).flip();
         GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA16, WTILES * PER_TILE, WTILES, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_SHORT, BUF);
         pbo = GL15.glGenBuffers();
      }
      if (w.nTiles == 0) {
         return;
      }
      int r0 = Integer.MAX_VALUE, r1 = -1, c0 = Integer.MAX_VALUE, c1 = -1;
      for (int k = 0; k < w.nTiles; k++) {
         int ti = w.tileIdx[k];
         System.arraycopy(w.tileTexels, k * PER_TILE * 4, mirror, ti * PER_TILE * 4, PER_TILE * 4);
         int row = ti / WTILES, col = ti % WTILES;
         r0 = Math.min(r0, row);
         r1 = Math.max(r1, row);
         c0 = Math.min(c0, col);
         c1 = Math.max(c1, col);
      }
      // the rectangle of changed tiles (a ring across the toroidal seam makes it the whole width / height: rare)
      int cols = (c1 - c0 + 1) * PER_TILE * 4;
      BUF.clear();
      for (int r = r0; r <= r1; r++) {
         BUF.put(mirror, (r * WTILES + c0) * PER_TILE * 4, cols);
      }
      BUF.flip();
      GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo);
      GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, BUF, GL15.GL_STREAM_DRAW);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, tileTex);
      GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, c0 * PER_TILE, r0, (c1 - c0 + 1) * PER_TILE, r1 - r0 + 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_SHORT, 0L);
      GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
      uploadBytes += (long)(r1 - r0 + 1) * cols * 2;
      uploadCalls++;
   }

   /** Render thread, Ripples.puddleUniforms in world mode: the tile texels on TILE_UNIT. */
   static void bindForPuddles() {
      if (tileTex == 0) {
         return;
      }
      GL13.glActiveTexture(GL13.GL_TEXTURE0 + Ripples.TILE_UNIT);
      GL11.glBindTexture(GL11.GL_TEXTURE_2D, tileTex);
      GL13.glActiveTexture(GL13.GL_TEXTURE0);
   }
}
