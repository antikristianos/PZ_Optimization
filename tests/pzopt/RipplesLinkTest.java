package pzopt;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The patched puddle program (raindrop rings, car wakes: pzopt.RainRipples / pzopt.Wakes, 2026-10-11) links and validates
 * with the real driver and with Mesa (llvmpipe through glvnd's Mesa EGL vendor), headless through /tmp/glslcheck
 * (tools/hdr/glslcheck.c), for the stock and puddleEarlyZ's units. glValidateProgram runs with every sampler on unit 0, as
 * the game's ShaderProgram does: the flipbook's sampler3D needs layout(binding) on Mesa (without it Mesa refuses the program
 * and the game drops the puddles), while NVIDIA's GLSL 1.20 refuses layout() even with 420pack, so Ripples.patchShader
 * falls back to the plain declaration there. Skipped without the checker.
 */
public class RipplesLinkTest {
   public static void main(String[] args) throws Exception {
      Path check = Path.of("/tmp/glslcheck");
      if (!Files.isExecutable(check)) {
         System.out.println("RipplesLinkTest skipped (no /tmp/glslcheck)");
         return;
      }
      File jar = new File(zombie.core.Core.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      Path shaders = jar.toPath().getParent().resolve("media/shaders");
      Path mesa = Path.of("/usr/share/glvnd/egl_vendor.d/50_mesa.json");
      int n = 0;
      for (String base : new String[] {"puddles_common", "pzopt_puddles_common"}) {
         if (!Files.exists(shaders.resolve(base + ".frag.glsl"))) {
            continue;
         }
         String vert = Wakes.patchVert(Files.readString(shaders.resolve(base + ".vert.glsl")));
         Check.check(vert != null, base + " vertex unit takes the wake gate");
         Path vp = Files.writeString(Files.createTempFile("pzrip", ".vert"), vert + "\nvoid main(void) { puddlesMain(); }\n");
         for (boolean binding : new boolean[] {false, true}) {
            String frag = Ripples.patchText(Files.readString(shaders.resolve(base + ".frag.glsl")), binding);
            Path fp = Files.writeString(Files.createTempFile("pzrip", ".frag"), frag + "\nvoid main() { puddlesMain(); }\n");
            String tag = base + (binding ? " (layout binding)" : " (plain sampler3D)");
            if (!binding) {
               String out = run(new ProcessBuilder(check.toString(), vp.toString(), fp.toString()));
               if (!out.contains("NVIDIA")) {
                  continue; // the plain variant is NVIDIA's
               }
               Check.check(out.contains("link ok") && out.contains("validate ok"), tag + " with the driver:\n" + out);
               n++;
            } else if (Files.exists(mesa)) {
               ProcessBuilder pb = new ProcessBuilder(check.toString(), vp.toString(), fp.toString());
               pb.environment().put("__EGL_VENDOR_LIBRARY_FILENAMES", mesa.toString());
               pb.environment().put("LIBGL_ALWAYS_SOFTWARE", "1");
               String out = run(pb);
               Check.check(out.contains("link ok") && out.contains("validate ok"), tag + " with Mesa (llvmpipe):\n" + out);
               n++;
            }
         }
      }
      System.out.println("RipplesLinkTest ok (" + n + " programs)");
   }

   static String run(ProcessBuilder pb) throws Exception {
      Process p = pb.redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes());
      p.waitFor();
      return out;
   }
}
