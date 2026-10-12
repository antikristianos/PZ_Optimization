"""Usage: python3 harness/mirrors/double-judge.py <run> <mirror x,y,z>: per dev-view-9 frame (devMirrorsView=9 + devMirrorsViewToggleMs, 1:1 devCapture crop at 1660,530) the person images (green blobs) on one mirror's glass, per walk phase, then Jev: two_reflections + verdict. The 2026-10-12 bathroom double-reflection rig."""
import sys, re, os, json, numpy as np
from collections import deque
sys.path.insert(0, "harness/mirrors"); sys.path.insert(0, "harness")
from walkframes import load
from typesafe_client import ask, noul, choice, fmt
run, key = sys.argv[1], sys.argv[2]
ox, oy = 1660, 530
rects, events = [], []
for line in open(os.path.join(run, "console.txt"), errors="replace"):
    m = re.search(r"epoch_ms=(\d+)", line)
    if not m: continue
    if "dev rects" in line:
        r = re.search(r"\[mirror (\d+),(\d+) (\d+)x(\d+) @" + re.escape(key) + r"\]", line)
        if r: rects.append((int(m.group(1)), *map(int, r.groups())))
    elif "harness: mirror walk: " in line and "director" not in line:
        events.append((int(m.group(1)), re.sub(r"\s*\(along.*|\s*epoch_ms.*", "", line.split("mirror walk: ", 1)[1].strip())))
def blobs(mask, minpx=40):
    h, w = mask.shape; seen = np.zeros_like(mask); out = []
    for y in range(h):
        for x in range(w):
            if mask[y, x] and not seen[y, x]:
                q = deque([(y, x)]); seen[y, x] = 1; px = []
                while q:
                    cy, cx = q.popleft(); px.append((cy, cx))
                    for ny, nx in ((cy+1,cx),(cy-1,cx),(cy,cx+1),(cy,cx-1)):
                        if 0 <= ny < h and 0 <= nx < w and mask[ny, nx] and not seen[ny, nx]:
                            seen[ny, nx] = 1; q.append((ny, nx))
                if len(px) >= minpx:
                    ys, xs = zip(*px); out.append({"px": len(px), "x_from": round(min(xs)/w, 2), "x_to": round(max(xs)/w, 2)})
    return out
mm, st, gray = load(run)
rows = []
for i in range(len(st)):
    t = int(st[i]); rr = min(rects, key=lambda q: abs(q[0]-t))
    if abs(rr[0]-t) > 1500: continue
    _, x, y, w, h = rr
    f = np.asarray(mm[i])[::-1][..., :3].astype(int)
    c = f[y-oy:y-oy+h, x-ox:x-ox+w]
    navy = ((c[...,2] > 15) & (c[...,2] < 70) & (c[...,0] < 40) & (c[...,1] < 40)).mean()
    if navy < 0.15: continue  # picture half of the toggle, not the dev view
    g = (c[...,1] > 150) & (c[...,0] < 120) & (c[...,2] < 140)
    b = sorted(blobs(g), key=lambda q: -q["px"])
    ev = [e for e in events if e[0] <= t]
    rows.append({"frame": i, "t_s": round((t - st[0]) / 1000, 1), "phase": ev[-1][1] if ev else "before walk", "images": len(b), "blobs": b})
n = len(rows); two = [r for r in rows if r["images"] >= 2]; one = [r for r in rows if r["images"] == 1]
phases = {}
for r in rows:
    p = phases.setdefault(r["phase"], {"dev_frames": 0, "with_two_or_more": 0, "with_one": 0, "with_none": 0})
    p["dev_frames"] += 1; p["with_two_or_more" if r["images"] >= 2 else "with_one" if r["images"] == 1 else "with_none"] += 1
state = {
    "what": "Project Zomboid, one player character (zombies off, no other people) walked by a scripted harness around a bathroom wall mirror. "
            "In debug view 9 the mirror's glass is painted flat navy, and every pixel where the mirrored-person layer draws a person image is painted green. "
            "Each green blob counted below is one drawn image of a person on that one mirror's glass (blobs under 40 px ignored). "
            "A correct mirror shows at most one image of the single player.",
    "mirror": key, "dev_view_frames": n, "frames_with_two_or_more_images": len(two), "frames_with_one_image": len(one),
    "frames_with_no_image": n - len(two) - len(one), "per_walk_phase": phases,
    "examples_two_images": two[:8],
    "maintainer_report": "the maintainer's screenshot shows two reflections of their character in this mirror",
}
print(json.dumps({k: v for k, v in state.items() if k not in ("what", "examples_two_images")}, indent=1))
print("examples:", json.dumps(two[:4]))
qs = {"two_reflections": noul("Does this mirror show two images of the one player character at the same time (two or more green blobs on its glass in a substantial number of frames)?"),
      "verdict": choice("What do the numbers say about the maintainer's report of two reflections in this mirror?",
          {"confirmed": "the mirror draws two images of the single player in many frames",
           "occasional": "two images appear only in a few isolated frames",
           "not_reproduced": "the mirror never shows more than one image",
           "inconclusive": "the numbers do not allow a judgment"})}
ans = ask(state, qs); print("jev:", fmt(ans))
json.dump({"state": state, "rows": rows, "jev": ans}, open(os.path.join(run, "double-reflection.json"), "w"), indent=1)
