package pzopt;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import zombie.core.skinnedmodel.animation.AnimationClip;
import zombie.core.skinnedmodel.model.AnimationAsset;
import zombie.core.skinnedmodel.model.FileTask_AbstractLoadModel;
import zombie.core.skinnedmodel.model.FileTask_LoadAnimation;
import zombie.core.skinnedmodel.model.ModelFileExtensionType;
import zombie.core.skinnedmodel.model.ModelMesh;
import zombie.core.skinnedmodel.model.SkinningData;
import zombie.fileSystem.FileSystem;
import zombie.fileSystem.IFileTaskCallback;

/**
 * FileTask_LoadAnimation that answers from pzopt.AnimClipCache when it can
 * (docs/plan-instant-load.md, B11). The stock task resolves the source file
 * (txt / X / fbx / glTF) in a private step of call(); this subclass runs that
 * step, and for the imported formats returns the cached clips as a
 * {@link CachedClips} result, which the AnimationAssetManager override
 * applies. On a miss the stock import runs and its clips are written to the
 * cache afterwards.
 */
public final class CachedAnimationTask extends FileTask_LoadAnimation {
   private static final Method CHECK_EXTENSION;

   static {
      Method m = null;
      try {
         m = FileTask_AbstractLoadModel.class.getDeclaredMethod("checkExtensionType");
         m.setAccessible(true);
      } catch (Exception e) {
         Log.warn("anim clip cache disabled: " + e);
      }
      CHECK_EXTENSION = m;
   }

   /** Result type carrying cached clips. */
   public static final class CachedClips {
      public final HashMap<String, AnimationClip> clips;

      CachedClips(HashMap<String, AnimationClip> clips) {
         this.clips = clips;
      }
   }

   private final String meshKey;
   /** Set by call(): where the clips of this source belong in the cache (null = not cacheable). */
   public volatile File cacheFile;

   public CachedAnimationTask(AnimationAsset anim, FileSystem fileSystem, IFileTaskCallback cb) {
      super(anim, fileSystem, cb);
      String mesh = "none";
      try {
         if (anim.assetParams != null && anim.assetParams.animationsMesh != null && anim.assetParams.animationsMesh.getPath() != null) {
            mesh = String.valueOf(anim.assetParams.animationsMesh.getPath().getPath()) + meshIdentity(anim.assetParams.animationsMesh);
         }
      } catch (Throwable ignored) {
      }
      this.meshKey = mesh;
   }

   /**
    * What an import takes from the skinning mesh (ImportedSkeleton copies its boneIndices and skeletonHierarchy), for the
    * cache key: bone names in index order and their parents. The mesh's asset path alone is the same when a mod replaces
    * the mesh (Tomb's Player Body ships its own Skinned/MaleBody with another bone order and ten more bones): the cache
    * then served clips with the vanilla bone indices and the characters came out mangled (2026-10-11). Not the mesh
    * file's path or mtime: a re-downloaded or copied mod with the same skeleton keeps its entries.
    */
   static String meshIdentity(ModelMesh mesh) {
      StringBuilder sb = new StringBuilder();
      SkinningData sd = mesh.skinningData;
      if (sd != null && sd.boneIndices != null) {
         String[] names = new String[sd.boneIndices.size()];
         for (Map.Entry<String, Integer> e : sd.boneIndices.entrySet()) {
            int i = e.getValue();
            if (i >= 0 && i < names.length) {
               names[i] = e.getKey();
            }
         }
         sb.append("|bones=").append(String.join(",", java.util.Arrays.asList(names)));
         if (sd.skeletonHierarchy != null) {
            sb.append("|parents=").append(sd.skeletonHierarchy);
         }
      }
      return sb.toString();
   }

   @Override
   public Object call() throws Exception {
      if (CHECK_EXTENSION == null) {
         return super.call();
      }
      ModelFileExtensionType res = (ModelFileExtensionType) CHECK_EXTENSION.invoke(this);
      if (res == null) {
         return null;
      }
      switch (res) {
         case Txt:
            return this.loadTxt();
         case X:
         case Fbx:
         case glTF:
            File f = AnimClipCache.fileFor(this.fileName, this.meshKey);
            HashMap<String, AnimationClip> clips = AnimClipCache.read(f);
            if (clips != null) {
               return new CachedClips(clips);
            }
            this.cacheFile = f;
            switch (res) {
               case X:
                  return this.loadX();
               case Fbx:
                  return this.loadFBX();
               default:
                  return this.loadGLTF();
            }
         default:
            return null;
      }
   }
}
