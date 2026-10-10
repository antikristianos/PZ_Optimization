package pzopt;

import org.json.JSONObject;

/**
 * pzopt.GcChoice's launcher edit: ZGC -> G1 with the marker (top level and per-platform sections), the optional pause
 * target, the undo back to the exact stock arguments, re-applying is a no-op, and a JSON without ZGC or our marker is
 * left alone.
 */
public class GcChoiceTest {
   private static final String STOCK = "{\"mainClass\":\"m\",\"classpath\":[\".\",\"projectzomboid.jar\"],"
         + "\"vmArgs\":[\"-Xmx3072m\",\"-XX:+UseZGC\",\"-XX:-OmitStackTraceInFastThrow\"],"
         + "\"windows\":{\"vmArgs\":[\"-Xmx3072m\",\"-XX:+UseZGC\"]}}";

   public static void main(String[] args) {
      JSONObject stock = new JSONObject(STOCK);

      JSONObject j = new JSONObject(STOCK);
      check(GcChoice.toG1(j, 0), "switches");
      check(j.getJSONArray("vmArgs").toList().contains("-XX:+UseG1GC") && !j.getJSONArray("vmArgs").toList().contains("-XX:+UseZGC"), "top level G1");
      check(j.getJSONArray("vmArgs").toList().contains(GcChoice.MARKER), "marker");
      check(j.getJSONObject("windows").getJSONArray("vmArgs").toList().contains("-XX:+UseG1GC"), "windows section G1");
      check(!j.toString().contains("MaxGCPauseMillis"), "no pause target at 0");
      check(!GcChoice.toG1(j, 0), "second switch is a no-op");
      check(GcChoice.toStock(j), "undo");
      check(j.similar(stock), "undo gives the stock JSON back: " + j);

      JSONObject p = new JSONObject(STOCK);
      GcChoice.toG1(p, 50);
      check(p.getJSONArray("vmArgs").toList().contains("-XX:MaxGCPauseMillis=50"), "pause target added");
      check(p.getJSONArray("vmArgs").toList().contains(GcChoice.MARKER_PAUSE), "pause marker");
      GcChoice.toStock(p);
      check(p.similar(stock), "undo removes the pause target too: " + p);

      JSONObject g1 = new JSONObject(STOCK.replace("-XX:+UseZGC", "-XX:+UseG1GC"));
      String before = g1.toString();
      check(!GcChoice.toG1(g1, 0) && !GcChoice.toStock(g1) && g1.toString().equals(before), "a JSON already on G1 without our marker is left alone");
      // the real Windows JSON: the collector per OS version, one level under "windows" (2026-10-11)
      String win = "{\"mainClass\":\"m\",\"classpath\":[\".\",\"projectzomboid.jar\"],\"vmArgs\":[\"-Xmx3072m\"],"
            + "\"windows\":{\"6.1\":{\"vmArgs\":[\"-XX:+UseG1GC\"]},\"10.0.17134\":{\"vmArgs\":[\"-XX:+UseZGC\"]}}}";
      JSONObject w = new JSONObject(win);
      check(GcChoice.toG1(w, 0), "windows: switches");
      JSONObject w10 = w.getJSONObject("windows").getJSONObject("10.0.17134");
      check(w10.getJSONArray("vmArgs").toList().contains("-XX:+UseG1GC") && w10.getJSONArray("vmArgs").toList().contains(GcChoice.MARKER), "windows 10 section G1 + marker");
      check(w.getJSONObject("windows").getJSONObject("6.1").getJSONArray("vmArgs").toList().equals(java.util.List.of("-XX:+UseG1GC")), "windows 6.1 section untouched");
      check(GcChoice.toStock(w) && w.similar(new JSONObject(win)), "windows: undo gives the stock JSON back: " + w);
      // jitSteady: the marker and the trap-limit flags in every section, idempotent, undone exactly, a player's own flag kept
      JSONObject js = new JSONObject(STOCK);
      GcChoice.jitToSteady(js);
      check(js.getJSONArray("vmArgs").toList().contains(GcChoice.JIT_MARKER), "jit marker");
      for (String f : GcChoice.JIT_FLAGS) {
         check(js.getJSONArray("vmArgs").toList().contains(f) && js.getJSONObject("windows").getJSONArray("vmArgs").toList().contains(f), "jit flag " + f);
      }
      String once = js.toString();
      GcChoice.jitToSteady(js);
      check(js.toString().equals(once), "second jitToSteady is a no-op");
      GcChoice.jitToStock(js);
      check(js.similar(stock), "jitToStock gives the stock JSON back: " + js);
      JSONObject own = new JSONObject(STOCK.replace("\"-XX:-OmitStackTraceInFastThrow\"", "\"-XX:-OmitStackTraceInFastThrow\",\"-XX:PerMethodTrapLimit=50\""));
      String ownBefore = own.getJSONArray("vmArgs").toString();
      GcChoice.jitToSteady(own);
      check(own.getJSONArray("vmArgs").toString().equals(ownBefore), "a player's own trap-limit flag: the section is left alone");
      GcChoice.jitToStock(own);
      check(own.getJSONArray("vmArgs").toList().contains("-XX:PerMethodTrapLimit=50"), "undo keeps the player's own flag");
      // gcHeap / gcHeapFixed / gcPreTouch: -Xmx replaced in place, -Xms and pre-touch added, all undone exactly
      JSONObject h = new JSONObject(STOCK);
      GcChoice.heapToPzopt(h, 6144, true, true);
      java.util.List<Object> ha = h.getJSONArray("vmArgs").toList();
      check(ha.get(0).equals("-Xmx6144m") && !ha.contains("-Xmx3072m"), "-Xmx replaced in place: " + ha);
      check(ha.contains("-Xms6144m") && ha.contains("-XX:+AlwaysPreTouch") && ha.contains(GcChoice.HEAP_MARKER + "3072m,none,1"), "-Xms, pre-touch, marker: " + ha);
      check(h.getJSONObject("windows").getJSONArray("vmArgs").toList().contains("-Xmx6144m"), "windows section heap");
      String hOnce = h.toString();
      GcChoice.heapToPzopt(h, 6144, true, true);
      check(h.toString().equals(hOnce), "second heapToPzopt is a no-op");
      GcChoice.heapToStock(h);
      check(h.similar(stock), "heapToStock gives the stock JSON back: " + h);
      JSONObject h0 = new JSONObject(STOCK);
      GcChoice.heapToPzopt(h0, 0, false, false);
      check(h0.similar(stock), "gcHeap=game without fixed / pre-touch leaves the JSON alone");
      GcChoice.heapToPzopt(h0, 0, true, false);
      check(h0.getJSONArray("vmArgs").toList().contains("-Xms3072m"), "fixed at the launcher's own size: " + h0);
      GcChoice.heapToStock(h0);
      check(h0.similar(stock), "undo of the fixed-only edit");
      JSONObject touched = new JSONObject(STOCK.replace("\"-XX:+UseZGC\",\"-XX:-Omit", "\"-XX:+UseZGC\",\"-XX:+AlwaysPreTouch\",\"-XX:-Omit"));
      JSONObject touchedStock = new JSONObject(touched.toString());
      GcChoice.heapToPzopt(touched, 4096, false, true);
      GcChoice.heapToStock(touched);
      check(touched.similar(touchedStock), "a player's own pre-touch flag stays: " + touched);
      check(GcChoice.heapMb(0) == 0 && GcChoice.heapMb(512) == 1024, "0 stays the launcher's own, at least 1 GB");
      check(GcChoice.heapMb(1 << 20) < 1 << 20 && GcChoice.heapMb(1 << 20) % 512 == 0, "1 TB is clamped to half the RAM, 512 MB steps");
      // gcHeap=auto: 4 GB, 8 GB from gcHeapAutoMods mods; game = the launcher's own; a number is MB
      check(GcChoice.wantMb("auto", 0) == Config.GC_HEAP_AUTO_MB && GcChoice.wantMb("auto", Config.GC_HEAP_AUTO_MODS - 1) == Config.GC_HEAP_AUTO_MB, "auto, few mods");
      check(GcChoice.wantMb("auto", Config.GC_HEAP_AUTO_MODS) == Config.GC_HEAP_AUTO_MODS_MB, "auto, a big mod list");
      check(GcChoice.wantMb("game", 200) == 0 && GcChoice.wantMb("6144", 0) == 6144 && GcChoice.wantMb("6144m", 0) == 6144, "game / MB");
      try {
         java.nio.file.Path mt = java.nio.file.Files.createTempFile("pzopt-mods", ".txt");
         java.nio.file.Files.writeString(mt, "VERSION = 1,\n\nmods\n{\n    mod = A,\n    mod = Authentic Z - Current,\n    mod = pzopt-harness,\n}\n\nmaps\n{\n}\n");
         check(GcChoice.countMods(mt.toFile()) == 2, "mods.txt counted without the harness mod");
         java.nio.file.Files.delete(mt);
      } catch (java.io.IOException e) {
         throw new AssertionError(e);
      }
      System.out.println("GcChoiceTest ok");
   }

   private static void check(boolean ok, String what) {
      if (!ok) {
         throw new AssertionError("GcChoiceTest: " + what);
      }
   }
}
