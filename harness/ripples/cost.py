#!/usr/bin/env python3
"""Puddle ripples cost per evaluation mode, from one devRipplesAlternate run (2026-10-10, pzopt.Ripples).

Run with --prop gpuSections=true --prop instrument=true --prop devRipplesAlternate=<ms> --prop "devRipplesModes=loop|tiled|...":
the puddle pass's GPU section is named puddles.off / puddles.<mode> by phase, the compute pass ripples.field / ripples.sim.
Prints per phase: frames, the puddle pass + compute GPU time per frame (mean / median / p90 us) and its delta to the
off phase, the whole frame (overlay log: frame time, GPU busy ms) per phase, the game thread's ripple CPU time per frame
(pzopt-bench.out ripples= line).

usage: cost.py <run> [<run> ...] [--skip S] [--json out.json]
"""
import json
import re
import statistics
import sys
from pathlib import Path


def pct(v, p):
    if not v:
        return float("nan")
    v = sorted(v)
    return v[min(len(v) - 1, int(p / 100 * len(v)))]


def phases_from_console(run):
    """[(epoch_ms, phase)] from the 'ripples: phase X at <ms>' console lines."""
    out = []
    for line in (run / "console.txt").read_text(errors="replace").splitlines():
        m = re.search(r"ripples: phase (\w+) at (\d+)", line)
        if m:
            out.append((int(m.group(2)), m.group(1)))
        if "ripples: phases" in line:
            for m in re.finditer(r"(\w+)@(\d+)", line):
                out.append((int(m.group(2)), m.group(1)))
    return sorted(out)


def phase_at(changes, ms):
    ph = None
    for t, p in changes:
        if t <= ms:
            ph = p
        else:
            break
    return ph


def analyse(run, skip_s):
    run = Path(run)
    gs = run / "pzopt-gpusections.out"
    per = {}  # phase -> list of per-frame us
    rcpu = {}  # phase -> list of per-frame render-thread us (puddle pass + compute pass issue)
    if gs.exists():
        recs = []
        for line in gs.read_text(errors="replace").splitlines():
            parts = line.split()
            if len(parts) < 3 or not parts[0].isdigit():
                continue
            recs.append((int(parts[0]), parts[1], float(parts[2]), float(parts[3]) if len(parts) > 3 else 0.0))
        recs.sort()
        t0 = recs[0][0] if recs else 0
        pending = 0.0
        pending_cpu = 0.0
        for ns, name, gpu, cpu in recs:
            if (ns - t0) / 1e9 < skip_s:
                continue
            if name.startswith("ripples."):
                pending += gpu
                pending_cpu += cpu
            elif name.startswith("puddles."):
                ph = name.split(".", 1)[1]
                per.setdefault(ph, []).append((gpu + pending) / 1000.0)
                rcpu.setdefault(ph, []).append((cpu + pending_cpu) / 1000.0)
                pending = 0.0
                pending_cpu = 0.0
    res = {"run": run.name, "gpu": {}, "frame": {}, "cpu": {}}
    off = statistics.mean(per["off"]) if per.get("off") else None
    for ph, v in sorted(per.items()):
        mean = statistics.mean(v)
        res["gpu"][ph] = {"frames": len(v), "mean_us": round(mean, 2), "median_us": round(statistics.median(v), 2),
                          "p90_us": round(pct(v, 90), 2), "delta_us": round(mean - off, 2) if off is not None else None,
                          "render_cpu_us": round(statistics.mean(rcpu.get(ph, [0.0])), 2)}
    # whole frame per phase from the overlay log
    changes = phases_from_console(run)
    ov = run / "pzopt-overlay.out"
    if ov.exists() and changes:
        rows = ov.read_text(errors="replace").splitlines()
        hdr = rows[0].split(",")
        i_ft, i_gpu, i_ms = hdr.index("frametime"), hdr.index("gpu_ms"), hdr.index("epoch_ms")
        first = None
        frames = {}
        for r in rows[1:]:
            c = r.split(",")
            try:
                ms = int(float(c[i_ms]))
                ft, gm = float(c[i_ft]), float(c[i_gpu])
            except (ValueError, IndexError):
                continue
            first = first or ms
            if (ms - first) / 1000 < skip_s:
                continue
            ph = phase_at(changes, ms)
            if ph:
                frames.setdefault(ph, []).append((ft, gm))
        for ph, v in sorted(frames.items()):
            fts = [a for a, _ in v]
            res["frame"][ph] = {"n": len(v), "frametime_ms": round(statistics.mean(fts), 3), "p99_ms": round(pct(fts, 99), 3),
                                "gpu_ms": round(statistics.mean([b for _, b in v]), 3)}
    bench = run / "pzopt-bench.out"
    if bench.exists():
        for line in bench.read_text(errors="replace").splitlines():
            if line.startswith("ripples="):
                for m in re.finditer(r"cpu_us\.(\w+)=([\d.]+)", line):
                    res["cpu"][m.group(1)] = float(m.group(2))
                res["counters"] = line[len("ripples="):]
    return res


def main():
    args = sys.argv[1:]
    skip = 5.0
    out = None
    runs = []
    i = 0
    while i < len(args):
        if args[i] == "--skip":
            skip = float(args[i + 1]); i += 2
        elif args[i] == "--json":
            out = args[i + 1]; i += 2
        else:
            runs.append(args[i]); i += 1
    if not runs:
        print(__doc__)
        sys.exit(2)
    all_res = []
    for r in runs:
        res = analyse(r, skip)
        all_res.append(res)
        print(f"== {res['run']}")
        print(f"   {'phase':8} {'frames':>7} {'mean us':>9} {'median':>8} {'p90':>8} {'delta':>8} {'rt cpu':>7}   {'frame ms':>8} {'p99':>7} {'gpu ms':>7}   {'gt cpu':>7}")
        for ph in sorted(set(res["gpu"]) | set(res["frame"])):
            g = res["gpu"].get(ph, {})
            f = res["frame"].get(ph, {})
            d = g.get("delta_us")
            print(f"   {ph:8} {g.get('frames', 0):7d} {g.get('mean_us', float('nan')):9.2f} {g.get('median_us', float('nan')):8.2f} {g.get('p90_us', float('nan')):8.2f} "
                  f"{(d if d is not None else float('nan')):8.2f} {g.get('render_cpu_us', float('nan')):7.2f}   {f.get('frametime_ms', float('nan')):8.3f} {f.get('p99_ms', float('nan')):7.3f} {f.get('gpu_ms', float('nan')):7.3f}   "
                  f"{res['cpu'].get(ph, float('nan')):7.2f}")
        if "counters" in res:
            print("   " + res["counters"])
    if out:
        Path(out).write_text(json.dumps(all_res, indent=1))


if __name__ == "__main__":
    main()
