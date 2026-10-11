#!/usr/bin/env python3
"""Stale chunk-texture AO after a double gate toggles (2026-10-11, Workshop "Open gates - AO is still there as it's closed").

    gate-ao.py <run> [--thresh 10] [--png]

Reads a `--flag gate=auto|place` run with a devCapture sequence (<run>/capture) and its console lines
`harness: gate toggled open|closed epoch_ms=` / `harness: gate rebake epoch_ms=`. The rig alternates toggle, re-bake of
every loaded chunk, toggle, re-bake...; the gate state is the same between a re-bake and the toggle before it. Per pair it
compares the last frame before the re-bake (the picture the toggle left) with the last frame before the next toggle (the
same scene freshly baked): pixels whose luminance differs by more than --thresh are a stale bake. Prints per pair the
changed pixels and their bounding box; --png writes before / after / diff (x4) PNGs to <run>/gate/. Exit 0 when every
pair is below 200 changed pixels (the idle player's breathing stays under that), 1 otherwise.
"""
import argparse
import importlib.util
import os
import re
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("capture", os.path.join(HERE, "ppl", "capture.py"))
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)

EV = re.compile(r"harness: gate (toggled (open|closed)|rebake) epoch_ms=(\d+)")


def events(run):
    for name in ("console.txt", "console-full.txt"):
        p = os.path.join(run, name)
        if os.path.exists(p):
            out = []
            for line in open(p, errors="replace"):
                m = EV.search(line)
                if m:
                    out.append(("rebake" if m.group(1) == "rebake" else m.group(2), int(m.group(3))))
            return out
    sys.exit("no console.txt in " + run)


def last_before(stamps, t):
    i = int(np.searchsorted(stamps, t)) - 1
    return i if i >= 0 else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--thresh", type=float, default=10.0)
    ap.add_argument("--png", action="store_true")
    a = ap.parse_args()
    frames, stamps = capture.load(a.run)
    ev = events(a.run)
    if not ev:
        sys.exit("no gate events in the console")
    if a.png:
        from PIL import Image
        os.makedirs(os.path.join(a.run, "gate"), exist_ok=True)
    worst = 0
    pairs = 0
    for k, (kind, t) in enumerate(ev):
        if kind != "rebake" or k == 0:
            continue
        state = ev[k - 1][0]
        t_next = ev[k + 1][1] if k + 1 < len(ev) else t + 2500  # the last re-bake: 2.5 s later (the quit fades the picture)
        i = last_before(stamps, t)
        j = last_before(stamps, t_next)
        if i is None or j is None or j <= i:
            print(f"pair {pairs}: gate {state}: no frames (rebake at {t})")
            continue
        la, lb = capture.lum(frames[i].astype(np.float32)), capture.lum(frames[j].astype(np.float32))
        d = np.abs(la - lb)
        mask = d > a.thresh
        n = int(mask.sum())
        box = ""
        if n:
            ys, xs = np.nonzero(mask)
            box = f" box x {xs.min()}..{xs.max()} y {ys.min()}..{ys.max()}, mean diff {d[mask].mean():.1f}, signed {float((la - lb)[mask].mean()):+.1f} (+ = darker after the re-bake)"
        print(f"pair {pairs}: gate {state}: frames {i} / {j} ({(t - stamps[i]):d} ms before the re-bake): {n} px over {a.thresh:g}{box}")
        worst = max(worst, n)
        if a.png:
            base = os.path.join(a.run, "gate", f"pair{pairs}-{state}")
            Image.fromarray(frames[i]).save(base + "-toggled.png")
            Image.fromarray(frames[j]).save(base + "-rebaked.png")
            Image.fromarray(np.clip(d * 4, 0, 255).astype(np.uint8)).save(base + "-diff.png")
        pairs += 1
    print(f"gate-ao: {pairs} pairs, worst {worst} px: " + ("stale bake" if worst >= 200 else "clean"))
    return 1 if worst >= 200 else 0


if __name__ == "__main__":
    sys.exit(main())
