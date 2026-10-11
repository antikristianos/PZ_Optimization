package pzopt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.lwjgl.util.vector.Quaternion;
import org.lwjgl.util.vector.Vector3f;
import zombie.core.skinnedmodel.animation.AnimationClip;
import zombie.core.skinnedmodel.animation.Keyframe;
import zombie.core.skinnedmodel.model.jassimp.JAssImpImporter;

/**
 * Disk cache of imported animation clips (docs/plan-instant-load.md, B11).
 *
 * The game imports 2,209 .X animation files with jassimp at every boot
 * (~17 thread-seconds), and all that survives of each import is a map of
 * AnimationClip: name, duration, two flags and a list of keyframes (bone
 * index, bone name, time, position, rotation, scale). This class writes that
 * map to one small binary file per animation after a stock import and reads
 * it back on later boots; the clips are rebuilt through the same
 * AnimationClip constructor, so the per-bone tables come out identical.
 *
 * The key covers the source file (path, size, mtime), the skinning mesh the
 * bone indices refer to (its asset path and its skeleton: bone names in index
 * order and parents, so a mod's replacement mesh gets entries of its own,
 * pzopt.CachedAnimationTask.meshIdentity), a format version and the pzopt build (game revision
 * + commit, + build time for a dirty or git-less build); anything else
 * compiles as stock and refreshes its cache entry. Files live under
 * <cache dir>/pzopt/anims/, emptied by pzopt.CacheDir when another build
 * filled it, so an update re-caches the animations during its first boot's
 * stock imports. Writes go through one daemon thread so the file pool never
 * waits on disk.
 */
public final class AnimClipCache {
   private static final int VERSION = 1;
   private static final int MAGIC = 0x505A4143; // "PZAC"
   private static final AtomicInteger hits = new AtomicInteger();
   private static final AtomicInteger misses = new AtomicInteger();
   private static final AtomicInteger written = new AtomicInteger();
   private static final AtomicInteger bad = new AtomicInteger();
   private static volatile File dir;
   private static final LinkedBlockingQueue<Runnable> writes = new LinkedBlockingQueue<>();
   private static Thread writer;

   private AnimClipCache() {
   }

   public static boolean enabled() {
      return Config.ANIM_CLIP_CACHE && Overrides.enabled();
   }

   private static File dir() {
      File d = dir;
      if (d == null) {
         d = CacheDir.open("anims");
         dir = d;
      }
      return d;
   }

   /** The cache file for this source and mesh, or null if the source cannot be described. */
   public static File fileFor(String sourcePath, String meshKey) {
      try {
         File src = new File(sourcePath);
         if (!src.isFile()) {
            return null;
         }
         String key = src.getCanonicalPath() + "|" + src.length() + "|" + src.lastModified() + "|" + meshKey + "|v" + VERSION + "|" + CacheDir.BUILD_KEY;
         MessageDigest md = MessageDigest.getInstance("SHA-1");
         byte[] h = md.digest(key.getBytes("UTF-8"));
         StringBuilder sb = new StringBuilder(40);
         for (byte b : h) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
         }
         return new File(dir(), sb + ".bin");
      } catch (Exception e) {
         return null;
      }
   }

   /** The clips stored in this file, or null if it is missing or unreadable. */
   public static HashMap<String, AnimationClip> read(File f) {
      if (f == null || !f.isFile()) {
         misses.incrementAndGet();
         return null;
      }
      try {
         byte[] bytes = Files.readAllBytes(f.toPath());
         DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
         if (in.readInt() != MAGIC || in.readInt() != VERSION) {
            bad.incrementAndGet();
            return null;
         }
         int nClips = in.readInt();
         HashMap<String, AnimationClip> clips = new HashMap<>(nClips * 2);
         for (int c = 0; c < nClips; c++) {
            String mapKey = in.readUTF();
            String name = in.readUTF();
            float duration = in.readFloat();
            boolean keepLast = in.readBoolean();
            boolean ragdoll = in.readBoolean();
            int nk = in.readInt();
            ArrayList<Keyframe> frames = new ArrayList<>(nk);
            for (int k = 0; k < nk; k++) {
               Keyframe kf = new Keyframe();
               kf.bone = in.readInt();
               String bone = in.readUTF();
               kf.boneName = JAssImpImporter.getSharedString(bone, "Keyframe.BoneName");
               if (kf.boneName == null) {
                  kf.boneName = bone;
               }
               kf.time = in.readFloat();
               kf.position = new Vector3f(in.readFloat(), in.readFloat(), in.readFloat());
               kf.rotation = new Quaternion(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());
               kf.scale = new Vector3f(in.readFloat(), in.readFloat(), in.readFloat());
               frames.add(kf);
            }
            clips.put(mapKey, new AnimationClip(duration, frames, name, keepLast, ragdoll));
         }
         hits.incrementAndGet();
         return clips;
      } catch (Exception e) {
         bad.incrementAndGet();
         return null;
      }
   }

   /** Queue the clips for writing; the map is copied now, the file is written on the cache thread. */
   public static void writeAsync(File f, Map<String, AnimationClip> clips) {
      if (f == null || clips == null) {
         return;
      }
      final HashMap<String, AnimationClip> copy = new HashMap<>(clips);
      synchronized (AnimClipCache.class) {
         if (writer == null) {
            writer = new Thread(() -> {
               while (true) {
                  try {
                     writes.take().run();
                  } catch (InterruptedException e) {
                     return;
                  } catch (Throwable t) {
                     bad.incrementAndGet();
                  }
               }
            }, "pzopt-animcache-writer");
            writer.setDaemon(true);
            writer.start();
         }
      }
      writes.add(() -> write(f, copy));
   }

   private static void write(File f, Map<String, AnimationClip> clips) {
      try {
         ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
         DataOutputStream out = new DataOutputStream(bos);
         out.writeInt(MAGIC);
         out.writeInt(VERSION);
         out.writeInt(clips.size());
         for (Map.Entry<String, AnimationClip> e : clips.entrySet()) {
            AnimationClip clip = e.getValue();
            out.writeUTF(e.getKey());
            out.writeUTF(clip.name == null ? "" : clip.name);
            out.writeFloat(clip.getDuration());
            out.writeBoolean(clip.keepLastFrame);
            out.writeBoolean(clip.isRagdoll);
            Keyframe[] frames = clip.getKeyframes();
            out.writeInt(frames.length);
            for (Keyframe kf : frames) {
               out.writeInt(kf.bone);
               out.writeUTF(kf.boneName == null ? "" : kf.boneName);
               out.writeFloat(kf.time);
               Vector3f p = kf.position;
               out.writeFloat(p.x);
               out.writeFloat(p.y);
               out.writeFloat(p.z);
               Quaternion q = kf.rotation;
               out.writeFloat(q.x);
               out.writeFloat(q.y);
               out.writeFloat(q.z);
               out.writeFloat(q.w);
               Vector3f s = kf.scale;
               out.writeFloat(s.x);
               out.writeFloat(s.y);
               out.writeFloat(s.z);
            }
         }
         out.flush();
         File tmp = new File(f.getPath() + ".tmp");
         Files.write(tmp.toPath(), bos.toByteArray());
         Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         written.incrementAndGet();
      } catch (Exception e) {
         bad.incrementAndGet();
         if (bad.get() <= 3) {
            Log.warn("anim clip cache write " + f + ": " + e);
         }
      }
   }

   public static String stats() {
      return "anim clip cache: hits=" + hits.get() + " misses=" + misses.get() + " written=" + written.get() + " bad=" + bad.get()
            + " pendingWrites=" + writes.size();
   }
}
