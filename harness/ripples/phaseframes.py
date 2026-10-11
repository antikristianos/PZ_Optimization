#!/usr/bin/env python3
"""One frame (or a short clip) per A/B phase of a recorded ripples run (2026-10-11, rain rings / car wakes).

The run must be recorded (--record) and use devRipplesAlternate with devRipplesModes / devRainModes / devWakeModes: the
console's 'ripples: phases name@epoch_ms ...' lines say when each phase began; the recording starts at launch and
schedule.log says how many seconds after launch the world was ready (pzopt-schedule.out holds that moment's epoch).
For each phase the frame at the middle of its k-th occurrence is cut from the AV1 HDR recording, cropped and tone-mapped
to an SDR PNG (or, with --clip S, an S-second SDR mp4 at the native frame rate, for motion). A run without a recording
(the flip has no screen recorder) but with a devCapture sequence (capture/frames.rgba + index.txt, epoch ms per frame)
gives the captured frame nearest the phase's middle instead (SDR already; --crop then is in capture pixels).

usage: phaseframes.py <run> <out_dir> [--crop w:h:x:y] [--occurrence k] [--clip S] [--at FRACTION] [--prefix P]
"""
import re
import subprocess
import sys
from pathlib import Path

TONEMAP = "zscale=t=linear:npl=203,format=gbrpf32le,zscale=p=bt709,tonemap=hable:desat=0,zscale=t=bt709:m=bt709:r=pc,format=rgb24"


def phases(run):
    out = []
    for line in (run / "console.txt").read_text(errors="replace").splitlines():
        if "ripples: phases" in line:
            for m in re.finditer(r"(\w+)@(\d+)", line):
                out.append((int(m.group(2)), m.group(1)))
    return sorted(out)


def main():
    a = sys.argv[1:]
    if len(a) < 2:
        print(__doc__)
        sys.exit(2)
    run, out = Path(a[0]), Path(a[1])
    crop, occ, clip, at, prefix = "1600:900:1760:630", 1, 0.0, 0.5, ""
    i = 2
    while i < len(a):
        if a[i] == "--crop":
            crop = a[i + 1]
        elif a[i] == "--occurrence":
            occ = int(a[i + 1])
        elif a[i] == "--clip":
            clip = float(a[i + 1])
        elif a[i] == "--at":
            at = float(a[i + 1])
        elif a[i] == "--prefix":
            prefix = a[i + 1]
        i += 2
    out.mkdir(parents=True, exist_ok=True)
    if not (run / "recording.mp4").exists() and (run / "capture" / "index.txt").exists():
        return from_capture(run, out, phases(run), occ, at, prefix, crop if "--crop" in a else None)
    ready_s = float(re.search(r"world ready (\d+) s after launch", (run / "schedule.log").read_text()).group(1))
    sched = dict(l.split("=", 1) for l in (run / "pzopt-schedule.out").read_text().split() if "=" in l)
    ready_ms = int(sched["world_ready_epoch_ms"])
    ph = phases(run)
    seen = {}
    for k, (ms, name) in enumerate(ph):
        end = ph[k + 1][0] if k + 1 < len(ph) else ms + 4000
        seen[name] = seen.get(name, 0) + 1
        if seen[name] != occ:
            continue
        t = ready_s + (ms - ready_ms) / 1000.0 + (end - ms) / 1000.0 * at
        w, h, x, y = crop.split(":")
        vf = f"crop={w}:{h}:{x}:{y},{TONEMAP}"
        if clip > 0:
            dst = out / f"{prefix}{name}.mp4"
            cmd = ["nice", "-n", "19", "ffmpeg", "-v", "error", "-threads", "2", "-ss", f"{t - clip / 2:.3f}", "-i", str(run / "recording.mp4"),
                   "-t", f"{clip}", "-vf", vf + ",format=yuv420p", "-c:v", "libx264", "-crf", "16", "-y", str(dst)]
        else:
            dst = out / f"{prefix}{name}.png"
            cmd = ["nice", "-n", "19", "ffmpeg", "-v", "error", "-threads", "2", "-ss", f"{t:.3f}", "-i", str(run / "recording.mp4"),
                   "-frames:v", "1", "-vf", vf, "-y", str(dst)]
        subprocess.run(cmd, check=True)
        print(f"{name}: video {t:.2f} s -> {dst}")


def from_capture(run, out, ph, occ, at, prefix, crop):
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "ppl"))
    import numpy as np
    from PIL import Image
    from capture import load
    frames, stamps = load(str(run))
    seen = {}
    for k, (ms, name) in enumerate(ph):
        end = ph[k + 1][0] if k + 1 < len(ph) else ms + 4000
        seen[name] = seen.get(name, 0) + 1
        if seen[name] != occ:
            continue
        want = ms + (end - ms) * at
        i = int(np.argmin(np.abs(stamps - want)))
        if abs(stamps[i] - want) > (end - ms) / 2:
            print(f"{name}: no captured frame in the phase")
            continue
        f = frames[i]
        if crop:
            w, h, x, y = map(int, crop.split(":"))
            f = f[y:y + h, x:x + w]
        dst = out / f"{prefix}{name}.png"
        Image.fromarray(np.ascontiguousarray(f)).save(dst)
        print(f"{name}: capture frame {i} ({(stamps[i] - want) / 1000:+.2f} s) -> {dst}")


if __name__ == "__main__":
    main()
