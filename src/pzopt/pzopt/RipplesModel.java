package pzopt;

/**
 * The water model of pzopt.Ripples (2026-10-10): iWave (Tessendorf, "Interactive Water Surfaces", 2004) on a grid of
 * {@code RES} texels a square stepped at {@code HZ}: h' = a1 h - a2 h_prev - a3 (G * h), G the 13 x 13 vertical-derivative
 * kernel (sum of k^2 exp(-k^2) J0(k r)), which makes the waves dispersive (a footstep spreads as a train of rings, the
 * short ones inside). RipplesGpu's {@code sim} mode runs exactly this on the GPU (shores included); the other modes draw
 * each footstep as this system's own impulse response, computed once here: per wavenumber k the scheme's recurrence
 * (the kernel's spectrum averaged over directions), the Gaussian footstep's spectrum, then the radial slope as a Hankel
 * transform (J1) into a table slope(r, t) the shaders read. Superposed rings are then exact for open water; only the
 * shore reflections need the simulation. Built on a worker at first use (~20 ms), {@link #ready()} until then.
 */
final class RipplesModel {
   private RipplesModel() {
   }

   static final int RES = 16; // texels a square
   static final float HZ = 60F; // steps a second (the shader blends the last two)
   static final float G = 0.032F, ALPHA = 0.02F; // iWave gravity (texels / step^2 scale) and damping a step (0.008 / 0.01 at 120 Hz)
   static final float IMPULSE_TEXELS = 1.2F; // a footstep's Gaussian radius (texels)
   static final float DEPTH = -0.15F; // a footstep's depth (squares) at amplitude 1
   static final int P = 6; // kernel half size: 13 x 13
   static final int LUT_W = 256, LUT_H = 64; // radius 0..RMAX x age 0..LIFE
   static final float RMAX = 2.5F; // squares
   static final float A1 = (2F - ALPHA) / (1F + ALPHA), A2 = 1F / (1F + ALPHA), A3 = G / (1F + ALPHA);

   static final float[] KERNEL = new float[(2 * P + 1) * (2 * P + 1)];
   static final float[] LUT = new float[LUT_W * LUT_H]; // radial slope (squares / square) for DEPTH x amplitude 1, faded out at the end of life
   static float reach0, reachV; // a ring's visible radius <= reach0 + reachV * sqrt(t) (squares)
   private static volatile boolean ready;
   private static boolean started;

   static boolean ready() {
      if (!ready && !started) {
         started = true;
         Thread t = new Thread(() -> {
            try {
               long t0 = System.nanoTime();
               build();
               ready = true;
               Log.info(String.format("ripples: water model ready in %.1f ms (reach %.2f + %.2f sqrt(t) squares)", (System.nanoTime() - t0) / 1e6, reach0, reachV));
            } catch (Throwable e) {
               Ripples.failed = true;
               Log.warn("ripples: water model failed, off: " + e);
            }
         }, "pzopt-ripples-model");
         t.setDaemon(true);
         t.start();
      }
      return ready;
   }

   static void build() {
      // the kernel: G(r) = sum_n k_n^2 exp(-k_n^2) J0(k_n r) / G0, k_n = n * 0.01 (Tessendorf's numbers)
      int nk = 10000;
      double dk0 = 0.01, g0 = 0;
      for (int n = 1; n <= nk; n++) {
         double k = n * dk0;
         g0 += k * k * Math.exp(-k * k);
      }
      for (int j = -P; j <= P; j++) {
         for (int i = -P; i <= P; i++) {
            double r = Math.sqrt(i * i + j * j), s = 0;
            for (int n = 1; n <= nk; n++) {
               double k = n * dk0;
               s += k * k * Math.exp(-k * k) * bessel0(k * r);
            }
            KERNEL[(j + P) * (2 * P + 1) + i + P] = (float)(s / g0);
         }
      }
      // the scheme per wavenumber: the kernel's spectrum averaged over directions, the recurrence, the footstep's spectrum
      int nK = 384;
      double kMax = Math.PI, dk = kMax / nK;
      double[] ks = new double[nK], gk = new double[nK], h0 = new double[nK];
      double s2 = IMPULSE_TEXELS * IMPULSE_TEXELS;
      for (int q = 0; q < nK; q++) {
         double k = (q + 0.5) * dk;
         ks[q] = k;
         double sum = 0;
         int dirs = 9;
         for (int d = 0; d < dirs; d++) {
            double a = Math.PI / 2 * d / (dirs - 1), kx = k * Math.cos(a), ky = k * Math.sin(a);
            for (int j = -P; j <= P; j++) {
               for (int i = -P; i <= P; i++) {
                  sum += KERNEL[(j + P) * (2 * P + 1) + i + P] * Math.cos(kx * i + ky * j);
               }
            }
         }
         gk[q] = sum / dirs;
         h0[q] = Math.PI * s2 * Math.exp(-k * k * s2 / 4); // 2D transform of exp(-r^2 / s^2), depth 1
      }
      // J1(k r) for every k and table radius (texels)
      double[] j1 = new double[nK * LUT_W];
      for (int q = 0; q < nK; q++) {
         for (int i = 0; i < LUT_W; i++) {
            double r = (i + 0.5) / LUT_W * RMAX * RES;
            j1[q * LUT_W + i] = bessel1(ks[q] * r);
         }
      }
      double[] hn = new double[nK], hp = new double[nK];
      java.util.Arrays.fill(hn, 1.0); // zero initial velocity: both steps hold the footstep
      java.util.Arrays.fill(hp, 1.0);
      int stepsTotal = (int)Math.ceil(Ripples.LIFE * HZ) + 1;
      int row = 0;
      float[] rowMax = new float[LUT_H];
      for (int s = 1; s <= stepsTotal && row < LUT_H; s++) {
         for (int q = 0; q < nK; q++) {
            double nn = A1 * hn[q] - A2 * hp[q] - A3 * gk[q] * hn[q];
            hp[q] = hn[q];
            hn[q] = nn;
         }
         double tRow = (row + 0.5) / LUT_H * Ripples.LIFE;
         while (row < LUT_H && s / HZ >= tRow) {
            double fade = smooth(Ripples.LIFE, Ripples.LIFE * 0.65, tRow);
            for (int i = 0; i < LUT_W; i++) {
               double acc = 0;
               for (int q = 0; q < nK; q++) {
                  acc += ks[q] * ks[q] * h0[q] * hn[q] * j1[q * LUT_W + i];
               }
               // dh/dr = -(1/2pi) int k^2 H(k) J1(k r) dk, per texel -> per square (x RES), depth DEPTH
               float v = (float)(-acc * dk / (2 * Math.PI) * RES * DEPTH * fade);
               LUT[row * LUT_W + i] = v;
               rowMax[row] = Math.max(rowMax[row], Math.abs(v));
            }
            row++;
            tRow = (row + 0.5) / LUT_H * Ripples.LIFE;
         }
      }
      // the visible reach: per row the radius holding 95 % of the ring's slope (weighted by circumference); dispersion
      // slows the front, so the cull is reach0 + reachV sqrt(t) above every row's
      float[] reach = new float[LUT_H];
      for (int j = 0; j < LUT_H; j++) {
         double tot = 0, acc = 0, floor = 0.01 * rowMax[j]; // the Hankel sum's far ringing is noise, not wave
         for (int i = 0; i < LUT_W; i++) {
            float a = Math.abs(LUT[j * LUT_W + i]);
            tot += a > floor ? a * (i + 0.5) : 0;
         }
         int r95 = 0;
         for (int i = 0; i < LUT_W; i++) {
            float a = Math.abs(LUT[j * LUT_W + i]);
            acc += a > floor ? a * (i + 0.5) : 0;
            if (acc < 0.95 * tot) {
               r95 = i;
            }
         }
         reach[j] = (r95 + 1.5F) / LUT_W * RMAX;
      }
      reach0 = 0.15F;
      float v = 0F;
      for (int j = 0; j < LUT_H; j++) {
         float t = (j + 0.5F) / LUT_H * Ripples.LIFE;
         v = Math.max(v, (reach[j] - reach0) / (float)Math.sqrt(t));
      }
      reachV = v;
      for (int j = 0; j < LUT_H; j++) {
         float t = (j + 0.5F) / LUT_H * Ripples.LIFE;
         float rr = reach0 + reachV * (float)Math.sqrt(t);
         for (int i = 0; i < LUT_W; i++) {
            float r = (i + 0.5F) / LUT_W * RMAX;
            LUT[j * LUT_W + i] *= (float)smooth(rr, rr * 0.85, r); // eased to zero at the cull radius
         }
      }
   }

   private static double smooth(double e0, double e1, double x) {
      double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
      return t * t * (3 - 2 * t);
   }

   /** J0 (Numerical Recipes' rational approximations, |error| < 1e-8). */
   static double bessel0(double x) {
      double ax = Math.abs(x);
      if (ax < 8.0) {
         double y = x * x;
         double a1 = 57568490574.0 + y * (-13362590354.0 + y * (651619640.7 + y * (-11214424.18 + y * (77392.33017 + y * -184.9052456))));
         double a2 = 57568490411.0 + y * (1029532985.0 + y * (9494680.718 + y * (59272.64853 + y * (267.8532712 + y))));
         return a1 / a2;
      }
      double z = 8.0 / ax, y = z * z, xx = ax - 0.785398164;
      double a1 = 1.0 + y * (-0.1098628627e-2 + y * (0.2734510407e-4 + y * (-0.2073370639e-5 + y * 0.2093887211e-6)));
      double a2 = -0.1562499995e-1 + y * (0.1430488765e-3 + y * (-0.6911147651e-5 + y * (0.7621095161e-6 - y * 0.934935152e-7)));
      return Math.sqrt(0.636619772 / ax) * (Math.cos(xx) * a1 - z * Math.sin(xx) * a2);
   }

   /** J1 (Numerical Recipes). */
   static double bessel1(double x) {
      double ax = Math.abs(x);
      if (ax < 8.0) {
         double y = x * x;
         double a1 = x * (72362614232.0 + y * (-7895059235.0 + y * (242396853.1 + y * (-2972611.439 + y * (15704.48260 + y * -30.16036606)))));
         double a2 = 144725228442.0 + y * (2300535178.0 + y * (18583304.74 + y * (99447.43394 + y * (376.9991397 + y))));
         return a1 / a2;
      }
      double z = 8.0 / ax, y = z * z, xx = ax - 2.356194491;
      double a1 = 1.0 + y * (0.183105e-2 + y * (-0.3516396496e-4 + y * (0.2457520174e-5 + y * -0.240337019e-6)));
      double a2 = 0.04687499995 + y * (-0.2002690873e-3 + y * (0.8449199096e-5 + y * (-0.88228987e-6 + y * 0.105787412e-6)));
      double ans = Math.sqrt(0.636619772 / ax) * (Math.cos(xx) * a1 - z * Math.sin(xx) * a2);
      return x < 0.0 ? -ans : ans;
   }
}
