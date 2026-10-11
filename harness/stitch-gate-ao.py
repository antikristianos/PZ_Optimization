#!/usr/bin/env python3
"""Before / after video of the stale gate AO fix (Workshop 2026-10-11, `aoBorderRefresh`, docs/override-edits.md).

    harness/stitch-gate-ao.py [off-label] [on-label] [out.mp4]

Two runs of the gate rig (`--flag gate=auto`, `pzopt.GateRig`, the recipe in harness/CLAUDE.md's gate-ao.py row) with
`windowsInChunkTexture=true` and a 1:1 `devCapture` crop round the gate, e.g. `--prop devCapture=4,26,30,100,crop=2710:830:960:540,ram`:
`--prop aoBorderRefresh=false` (left) and the default (right). Each pane is aligned on its own run's first gate toggle
(the rig toggles / re-bakes every 3 s from the route start in both), scaled 2x with nearest sampling, captioned per rig
event from console.txt, then a results card. AV1 10-bit PQ / BT.2020 (SDR captures mapped to PQ as encode-av1-hdr.sh
does); the poster .jpg is the only tone-mapped copy. Defaults: runs gate-vid-off / gate-vid-on, docs/media/gate-ao-fix.mp4.
"""
import glob
import os
import re
import subprocess
import sys

import numpy as np

OFF = sys.argv[1] if len(sys.argv) > 1 else 'gate-vid-off'
ON = sys.argv[2] if len(sys.argv) > 2 else 'gate-vid-on'
OUT = sys.argv[3] if len(sys.argv) > 3 else 'docs/media/gate-ao-fix.mp4'
RUNS = os.environ.get('RUNS', 'harness/runs')
FPS = 30
PRE, POST = 1.5, 2.5  # seconds before the first toggle, after the last re-bake
SCALE = 2
DIV = 8
HEAD, LAB, FOOT = 120, 70, 230
CARD = 6.0

FONT_B = '/usr/share/fonts/noto/NotoSans-Bold.ttf'
FONT_R = '/usr/share/fonts/noto/NotoSans-Regular.ttf'
FONT_M = '/usr/share/fonts/noto/NotoSansMono-Regular.ttf'
WHITE, GREY, RED, GREEN, AMBER = '0xB4B4B8', '0x8A8A90', '0xB85C5C', '0x5CB878', '0xB88A40'  # PQ code values: ~60 % = comfortable white


def run_dir(label):
    runs = sorted(glob.glob(f'{RUNS}/{label}-2*'))
    if not runs:
        raise SystemExit(f'no run {label} under {RUNS}')
    return runs[-1]


def capture(d):
    """(memmap of the RGBA frames, bottom-up rows; epoch ms per frame; w; h)."""
    c = os.path.join(d, 'capture')
    lines = open(os.path.join(c, 'index.txt')).read().split('\n')
    head = dict(kv.split('=') for kv in lines[0].split())
    w, h = int(head['w']), int(head['h'])
    stamps = np.array([int(x) for x in lines[1:] if x.strip()])
    raw = np.memmap(os.path.join(c, 'frames.rgba'), dtype=np.uint8, mode='r')
    n = min(len(stamps), raw.size // (w * h * 4))
    return raw[:n * w * h * 4].reshape(n, h, w, 4), stamps[:n], w, h


def events(d):
    """[(epoch ms, 'open' | 'closed' | 'rebake')] of the gate rig, in order."""
    ev = []
    for line in open(os.path.join(d, 'console.txt'), errors='replace'):
        m = re.search(r'harness: gate (toggled (open|closed)|rebake) epoch_ms=(\d+)', line)
        if m:
            ev.append((int(m.group(3)), m.group(2) or 'rebake'))
    if not ev or ev[0][1] == 'rebake':
        raise SystemExit(f'{d}: no gate toggle in console.txt')
    return ev


def pane(d, t0_ms, n):
    """<run>/gate-pane.mkv: n frames at FPS from epoch t0_ms (nearest captured frame), scaled SCALE x, lossless."""
    frames, stamps, w, h = capture(d)
    if stamps[0] > t0_ms + 100 or stamps[-1] < t0_ms + (n - 1) * 1000 / FPS - 100:
        raise SystemExit(f'{d}: the capture ({stamps[0]}..{stamps[-1]}) does not cover {t0_ms}..+{n / FPS:.1f} s')
    out = os.path.join(d, 'gate-pane.mkv')
    ff = subprocess.Popen(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{w}x{h}',
                           '-r', str(FPS), '-i', '-', '-vf', f'scale={w * SCALE}:{h * SCALE}:flags=neighbor', '-c:v', 'ffv1',
                           '-pix_fmt', 'gbrp', out], stdin=subprocess.PIPE)
    gaps = 0
    for i in range(n):
        t = t0_ms + i * 1000 / FPS
        j = int(np.argmin(np.abs(stamps - t)))
        gaps += abs(stamps[j] - t) > 1000 / FPS
        ff.stdin.write(np.ascontiguousarray(frames[j, ::-1, :, :3]).tobytes())  # rows are bottom-up
    ff.stdin.close()
    if ff.wait() != 0:
        raise SystemExit(f'{d}: pane encode failed')
    print(f'{d}: {n} frames, {gaps} more than a frame from a capture')
    return out, w * SCALE, h * SCALE


def esc(s):
    return s.replace('\\', '\\\\').replace(':', '\\:').replace("'", '’')  # expansion=none: % is literal


def text(t, x, y, size, color, font=FONT_R, when=None):
    en = f":enable='between(t,{when[0]:.3f},{when[1]:.3f})'" if when else ''
    return f"drawtext=fontfile={font}:expansion=none:text='{esc(t)}':fontsize={size}:fontcolor={color}:x={x}:y={y}{en}"


a, b = run_dir(OFF), run_dir(ON)
ea, eb = events(a), events(b)
k = min(len(ea), len(eb))
ea, eb = ea[:k], eb[:k]
if [e[1] for e in ea] != [e[1] for e in eb]:
    raise SystemExit(f'the two runs toggle differently: {ea} / {eb}')
end_a, end_b = capture(a)[1][-1], capture(b)[1][-1]  # stop at the last re-bake both captures cover with POST after it
last = max(i for i, e in enumerate(ea) if e[1] == 'rebake' and e[0] + POST * 1000 <= end_a and eb[i][0] + POST * 1000 <= end_b)
# from the first close: the rig's first opening is the first change round a freshly loaded gate and is not stale on either
# side (gate-ao.py's pair 0, left out of its verdict too)
first = next(i for i, e in enumerate(ea) if e[1] == 'closed')
ea, eb = ea[first:last + 1], eb[first:last + 1]
span = (ea[-1][0] - ea[0][0]) / 1000 + PRE + POST
n = int(span * FPS)
pa, PW, PH = pane(a, ea[0][0] - PRE * 1000, n)
pb, _, _ = pane(b, eb[0][0] - PRE * 1000, n)
W, H = 2 * PW + DIV, HEAD + LAB + PH + FOOT
seg = n / FPS
print(f'{a} + {b}: {len(ea)} events, {seg:.1f} s, {W}x{H}')

# per rig event: what happened (centre) and what each side shows until the next event
CAPTION = {
    'open': ('The gate is opened', 'the closed gate’s shading stays on the ground', 'the shading follows the open gate'),
    'closed': ('The gate is closed', 'the shading still matches the open gate', 'the shading follows the closed gate'),
    'rebake': ('Every chunk baked again (what zooming out and back in did)', 'now it is right', 'nothing changes: it was already right'),
}
ov = []
times = [(e[0] - ea[0][0]) / 1000 + PRE for e in ea] + [seg]
ov.append(text('The gate is open, everything freshly baked', '(w-tw)/2', HEAD + LAB + PH + 30, 52, GREY, FONT_B, (0, times[0])))
for i, (_, kind) in enumerate(ea):
    when = (times[i], times[i + 1])
    what, left, right = CAPTION[kind]
    stale = kind != 'rebake'
    ov.append(text(what, '(w-tw)/2', HEAD + LAB + PH + 30, 52, WHITE, FONT_B, when))
    ov.append(text(left, f'({PW}-tw)/2', HEAD + LAB + PH + 110, 44, RED if stale else GREY, FONT_R, when))
    ov.append(text(right, f'{PW + DIV}+({PW}-tw)/2', HEAD + LAB + PH + 110, 44, GREEN if stale else GREY, FONT_R, when))
    if stale:  # a red frame round the stale side while it is wrong
        ov.append(f"drawbox=x=0:y={HEAD + LAB}:w={PW}:h={PH}:color={RED}:t=6:enable='between(t,{when[0]:.3f},{when[1]:.3f})'")

sdr2pq = ('fps=60,scale=out_color_matrix=bt709:out_range=tv,format=yuv444p10le,'  # encode-av1-hdr.sh's SDR -> PQ mapping
          'setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=tv,zscale=t=linear:npl=203,format=gbrpf32le,'
          'zscale=pin=bt709:tin=linear:p=bt2020:t=smpte2084:m=bt2020nc:r=tv:npl=203,format=yuv420p10le,setsar=1')
chains = [f'[0:v]{sdr2pq}[a]', f'[1:v]{sdr2pq}[b]',
          f'color=c=0x060608:s={W}x{H}:r=60:d={seg},format=yuv420p10le[bg];'
          f'[bg][a]overlay=0:{HEAD + LAB}:shortest=1[s1];[s1][b]overlay={PW + DIV}:{HEAD + LAB}[s2];'
          f'[s2]drawbox=x={PW}:y={HEAD}:w={DIV}:h={LAB + PH}:color=0x202026:t=fill,'
          + text('Ambient occlusion left behind by an opened gate  ·  Workshop report, 2026-10-11', '(w-tw)/2', 30, 60, WHITE, FONT_B) + ','
          + text('BEFORE  ·  aoBorderRefresh=false', f'({PW}-tw)/2', HEAD + 10, 44, RED, FONT_B) + ','
          + text('AFTER  ·  the fix (aoBorderRefresh, default on)', f'{PW + DIV}+({PW}-tw)/2', HEAD + 10, 44, GREEN, FONT_B) + ','
          + ','.join(ov) + ','
          + text('Chain-link double gate across a chunk border  ·  ambient occlusion on, Windows in chunk texture on  ·  noon, zoom 1, 2x crops of the game’s own frames  ·  the same rig and timing on both sides',
                 '(w-tw)/2', H - 58, 30, GREY)
          + f',fade=t=in:st=0:d=0.3,fade=t=out:st={seg - 0.3}:d=0.3[seg0]']

# results card (numbers: harness/gate-ao.py on the two runs of the video, pairs 1 and 2; runs gate-vid-off / gate-vid-on of
# 2026-10-12, the earlier gate-ao-v2-before / gate-ao-v3-after gave 10,032 / 12,153 / 10,026 vs 0 / 0 / 0)
rows = [('After closing the gate', os.environ.get('PX_CLOSED', '10,027 px'), '0 px'),
        ('After opening the gate', os.environ.get('PX_OPEN', '12,157 px'), '0 px')]
card = [f'color=c=0x060608:s={W}x{H}:r=60:d={CARD},format=yuv420p10le,'
        + text('PIXELS STILL WRONG UNTIL EVERYTHING IS BAKED AGAIN', '(w-tw)/2', 170, 64, WHITE, FONT_B) + ','
        + text('harness/gate-ao.py: the picture right before the full re-bake against the one after it (changes over 10/255)', '(w-tw)/2', 270, 36, GREY)]
cx = [W // 2 - 900, W // 2 + 200, W // 2 + 650]
card.append(text('BEFORE', cx[1], 420, 36, RED, FONT_B))
card.append(text('AFTER', cx[2], 420, 36, GREEN, FONT_B))
card.append(f'drawbox=x={cx[0]}:y=480:w={cx[2] + 300 - cx[0]}:h=2:color=0x505058:t=fill')
for r, (name, before, after) in enumerate(rows):
    yy = 520 + r * 110
    card.append(text(name, cx[0], yy, 48, WHITE))
    card.append(text(before, cx[1], yy, 48, RED, FONT_M))
    card.append(text(after, cx[2], yy, 48, GREEN, FONT_M))
card.append(text('Each chunk’s ambient occlusion is shaded with its eight neighbours’ shapes, but a change only re-shaded its own chunk.',
                 '(w-tw)/2', 960, 38, GREY))
card.append(text('Now a chunk that bakes again because its objects changed also re-shades the neighbours whose shared edge changed.',
                 '(w-tw)/2', 1015, 38, GREY))
chains.append(','.join(card) + f',fade=t=in:st=0:d=0.3[seg1]')

fc = ';'.join(chains) + ';[seg0][seg1]concat=n=2:v=1:a=0,' \
     + 'setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv[v]'
os.makedirs(os.path.dirname(OUT) or '.', exist_ok=True)
cmd = ['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-i', pa, '-i', pb,
       '-filter_complex', fc, '-map', '[v]',
       '-c:v', 'av1_nvenc', '-preset', 'p7', '-tune', 'hq', '-rc', 'vbr', '-cq', '22', '-b:v', '0', '-maxrate', '120M', '-bufsize', '240M',
       '-pix_fmt', 'p010le', '-color_primaries', 'bt2020', '-color_trc', 'smpte2084', '-colorspace', 'bt2020nc', '-color_range', 'tv',
       '-movflags', '+faststart+write_colr', '-r', '60', OUT]
subprocess.run(cmd, check=True)
poster = OUT[:-4] + '.jpg'
subprocess.run(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-ss', f'{times[0] + 2.0:.2f}', '-i', OUT, '-frames:v', '1', '-vf',
                'zscale=tin=smpte2084:pin=bt2020:min=bt2020nc:t=linear:npl=200,format=gbrpf32le,tonemap=hable,'
                'zscale=p=bt709:t=bt709:m=bt709,format=yuv420p', '-q:v', '2', poster], check=False)
print(subprocess.run(['ffprobe', '-v', 'error', '-show_entries', 'format=duration:stream=width,height,codec_name,color_transfer',
                      '-of', 'csv=p=0', OUT], capture_output=True, text=True).stdout.strip())
