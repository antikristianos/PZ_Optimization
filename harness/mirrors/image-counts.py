"""Usage: python3 harness/mirrors/image-counts.py <run>: per mirror in the dev rects and dev-view-9 frame (devCapture crop at 1660,530) the person images (green blobs): one / two or more / none. Writes <run>/mirror-image-counts.json."""
import sys, re, os, json, numpy as np
sys.path.insert(0, "harness/mirrors")
from walkframes import load
run = sys.argv[1]; ox, oy = 1660, 530
rects = {}
for line in open(os.path.join(run, "console.txt"), errors="replace"):
    m = re.search(r"epoch_ms=(\d+)", line)
    if m and "dev rects" in line:
        for r in re.finditer(r"\[mirror (\d+),(\d+) (\d+)x(\d+) @([\d,]+)\]", line):
            rects.setdefault(r.group(5), []).append((int(m.group(1)), *map(int, r.groups()[:4])))
from collections import deque
def blobs(mask, minpx=40):
    h, w = mask.shape; seen = np.zeros_like(mask); n = 0
    for y in range(h):
        for x in range(w):
            if mask[y, x] and not seen[y, x]:
                q = deque([(y, x)]); seen[y, x] = 1; c = 0
                while q:
                    cy, cx = q.popleft(); c += 1
                    for ny, nx in ((cy+1,cx),(cy-1,cx),(cy,cx+1),(cy,cx-1)):
                        if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                            seen[ny, nx] = 1; q.append((ny, nx))
                n += c >= minpx
    return n
mm, st, gray = load(run)
out = {}
for key, rr in rects.items():
    cnt = {"dev_frames": 0, "two_or_more": 0, "one": 0, "none": 0}
    for i in range(len(st)):
        t = int(st[i]); q = min(rr, key=lambda z: abs(z[0]-t))
        if abs(q[0]-t) > 1500: continue
        _, x, y, w, h = q
        x0, y0 = x-ox, y-oy
        if x0 < 0 or y0 < 0 or x0+w > mm.shape[2] or y0+h > mm.shape[1]: continue
        f = np.asarray(mm[i])[::-1]
        c = f[y0:y0+h, x0:x0+w, :3].astype(int)
        navy = ((c[...,2] > 15) & (c[...,2] < 70) & (c[...,0] < 40) & (c[...,1] < 40)).mean()
        if navy < 0.15: continue
        n = blobs((c[...,1] > 150) & (c[...,0] < 120) & (c[...,2] < 140))
        cnt["dev_frames"] += 1; cnt["two_or_more" if n >= 2 else "one" if n == 1 else "none"] += 1
    out[key] = cnt; print(key, cnt)
json.dump(out, open(os.path.join(run, "mirror-image-counts.json"), "w"), indent=1)
