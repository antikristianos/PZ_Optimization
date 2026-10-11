#!/usr/bin/env python3
"""Tree-crown sky AO under a roof: 2x2 video from four devCapture runs (labels skyao-cl-{25,0}{,-ao}).

usage: harness/stitch-tree-sky.py <out.mp4>   (DRY=<seconds>: one composed frame as PNG to <out>)
Panels follow the test canopy's ground footprint (the camera follows the player walking west at 0.3 sq/s), frames picked
by time since the route start, footprint outlined in red. SDR captures mapped to PQ / BT.2020, AV1 10-bit (repo rule).
"""
import glob, os, subprocess, sys
import numpy as np
from PIL import Image, ImageDraw

REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
out = sys.argv[1]
RUNS = [("skyao-cl-25", 0, 0), ("skyao-cl-0", 1, 0), ("skyao-cl-25-ao", 0, 1), ("skyao-cl-0-ao", 1, 1)]
W, H = 2560, 1080            # capture size (50 % of 5120x2160, zoom 1: a square is 64 x 32 px)
PW, PH = 960, 540            # panel crop at capture resolution
SC = 2                       # panel upscale
FPS, T0, T1 = 30, -0.5, 20.0
SPEED, X0, Y0 = 0.3, 8010.5, 11193.5   # the route: west from 8010,11193 at 0.3 squares / s
HEAD, LAB, FOOT = 120, 0, 110


def kv(p):
    return dict(l.strip().split("=", 1) for l in open(p) if "=" in l)


def load(label):
    d = sorted(glob.glob(f"{REPO}/harness/runs/{label}-2*"))[-1]
    st = np.array([int(x) for x in open(d + "/capture/index.txt").read().split("\n")[1:] if x.strip()])
    r0 = int(kv(d + "/pzopt-schedule.out")["route_start_epoch_ms"])
    raw = np.memmap(d + "/capture/frames.rgba", dtype=np.uint8, mode="r")
    n = min(len(st), raw.size // (W * H * 4))
    return (st[:n] - r0) / 1000.0, raw


def scr(X, Y, t):
    px = X0 - SPEED * max(0.0, t)
    # (+12, +50): the camera's offset from the player measured on the canopy roof and the tree trunk in the capture
    return 1292 + ((X - px) - (Y - Y0)) * 32, 590 + ((X - px) + (Y - Y0)) * 16


caps = [load(l) for l, _, _ in RUNS]
OW, OH = 2 * PW * SC, 2 * PH * SC
fonts = "/usr/share/fonts/noto/NotoSans-Bold.ttf"
SDR2PQ = ("setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv,zscale=t=linear:npl=203,format=gbrpf32le,"
          "zscale=pin=bt709:tin=linear:p=bt2020:t=smpte2084:m=bt2020nc:r=tv:npl=203,format=yuv420p10le")


def txt(s, size, color, x, y, box=True):
    s = s.replace("'", "").replace(":", " -")
    b = ":box=1:boxcolor=black@0.65:boxborderw=10" if box else ""
    return f"drawtext=fontfile={fonts}:text='{s}':fontsize={size}:fontcolor={color}{b}:x={x}:y={y}"


labels = [
    txt("Tree-crown sky occlusion darkens the ground under a roof", 64, "white", "(w-tw)/2", 28, False),
    txt("DEFAULT  aoTreeCanopyPct 25", 44, "0xFF9080", f"{PW * SC // 2}-tw/2", HEAD + 20),
    txt("CROWN SKY TERM OFF  aoTreeCanopyPct 0", 44, "0x80FF9A", f"{PW * SC}+{PW * SC // 2}-tw/2", HEAD + 20),
    txt("picture", 40, "white", 24, HEAD + PH * SC - 70),
    txt("AO term only  devAoView 1", 40, "white", 24, HEAD + OH - 70),
    txt("Red outline = ground under a test gas-station canopy (roofs_04_55, two levels up). The game flags it roofed "
        "(isOutside false), so no sun shadow lands there, but the crown of the tree beside it still darkens it.", 30,
        "0xD0D0D0", "(w-tw)/2", f"h-{FOOT}+12", False),
    txt("Rosewood, clear noon, ambient occlusion on, sun shadows off. With sun shadows on the same oval shows when overcast, "
        "at dusk and at night (x0.1 in full sun).", 30, "0xA0A0A0", "(w-tw)/2", f"h-{FOOT}+58", False),
]
vf = (f"pad={OW}:{OH + HEAD + FOOT}:0:{HEAD}:color=0x08080a," + ",".join(labels) + f",format=yuv420p,{SDR2PQ},"
      "setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv")
cmd = ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pixel_format", "rgb24", "-video_size", f"{OW}x{OH}", "-framerate",
       str(FPS), "-i", "-", "-vf", vf, "-pix_fmt", "p010le", "-c:v", "av1_nvenc", "-preset", "p7", "-tune", "hq", "-rc", "vbr",
       "-cq", "24", "-b:v", "0", "-color_primaries", "bt2020", "-color_trc", "smpte2084", "-colorspace", "bt2020nc",
       "-color_range", "tv", "-movflags", "+faststart+write_colr", out]
import os
DRY = os.environ.get('DRY')
ff = None if DRY else subprocess.Popen(cmd, stdin=subprocess.PIPE)
FOOTPRINT = [(8004, 11186), (8009, 11186), (8009, 11191), (8004, 11191)]
for k in range(1 if DRY else int((T1 - T0) * FPS)):
    t = float(DRY) if DRY else T0 + k / FPS
    canvas = Image.new("RGB", (OW, OH))
    cx, cy = scr(8006.5, 11188.5, t)
    x0, y0 = int(round(cx - PW / 2)), int(round(cy - PH / 2 - 40))
    for (st, raw), (_, col, row) in zip(caps, RUNS):
        i = int(np.argmin(abs(st - t)))
        f = np.asarray(raw[i * W * H * 4:(i + 1) * W * H * 4]).reshape(H, W, 4)[::-1, :, :3]
        p = Image.fromarray(np.ascontiguousarray(f[y0:y0 + PH, x0:x0 + PW]))
        p = p.resize((PW * SC, PH * SC), Image.LANCZOS)
        poly = [((sx - x0) * SC, (sy - y0) * SC) for sx, sy in (scr(X, Y, t) for X, Y in FOOTPRINT)]
        ImageDraw.Draw(p).line(poly + [poly[0]], fill=(235, 50, 50), width=4)
        canvas.paste(p, (col * PW * SC, row * PH * SC))
    d = ImageDraw.Draw(canvas)
    d.line([(PW * SC, 0), (PW * SC, OH)], fill=(20, 20, 24), width=6)
    d.line([(0, PH * SC), (OW, PH * SC)], fill=(20, 20, 24), width=6)
    if DRY:
        canvas.save(out)
        sys.exit(0)
    ff.stdin.write(canvas.tobytes())
ff.stdin.close()
sys.exit(ff.wait())
