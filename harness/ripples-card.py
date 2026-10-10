#!/usr/bin/env python3
"""The New! card for puddle ripples (2026-10-11, docs/findings-puddle-ripples-2026-10-10.md) in the AVIFs' template
(harness/efficiency-anim.py: 630 square, the left strip with its label, Martian Mono, stock left of the white divider and
enhanced right of it, the New! cards' sweep; the layout of harness/props-reflect-card.py).

Two segments, one divider cycle each, each from its own pair of in-game devCapture runs (1:1 crop of the 5120x2160
screen round the player, 30 fps), KY-60 by the bench save, noon, rain with clear puddles (`puddle_mud=0`: the first
minutes of a shower, before the rain makes them muddy; both sides the same):

    walk   the player walks east and back along the road     ripcard-walk-{stock,enh} (zoom 0.5, devCapture crop=2010:450:1100:1260)
    drive  a car at 30 km/h east through the puddles          ripcard-drive3-{stock,enh} (zoom 0.75, devCapture crop=1300:278:1400:1604)

    stock     --prop enabled=false
    enhanced  --prop ripples=true (the defaults otherwise; -Dpzopt.userOptionsFile=<empty file>)

The stock frame shown is the best image match within 0.35 s of the enhanced frame's route time (never going back),
so the two sides stay in step through the small drift between two runs.

    python3 harness/ripples-card.py [--still png --seg N --t S] [--gif] [--out workshop-media/puddle-ripples.avif]
"""
import argparse
import glob
import importlib.util
import os
import shutil

import numpy as np
from PIL import Image

here = os.path.dirname(__file__)
spec = importlib.util.spec_from_file_location('anim', os.path.join(here, 'efficiency-anim.py'))
anim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(anim)
spec = importlib.util.spec_from_file_location('props', os.path.join(here, 'props-reflect-card.py'))
props = importlib.util.module_from_spec(spec)
spec.loader.exec_module(props)

LABEL = 'Puddle Ripples'
# (stock run, enhanced run, route second the segment starts, crop x, y, w, h in the capture (rows top-down))
SEGMENTS = [
    ('ripcard-walk-stock', 'ripcard-walk-enh', 3.0, (275, 315, 550, 630)),
    ('ripcard-drive3-stock', 'ripcard-drive3-enh', 0.1, (0, 0, 1400, 1604)),  # zoom 0.75, the whole capture: the camera leads the car, its wake trails behind
]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--size', type=int, default=630)
    ap.add_argument('--theme', default='light', choices=anim.THEMES)
    ap.add_argument('--crf', default='48')  # rain streaks + raindrop rings: CRF 35 was 6 MB, 48 is 2.1 MB at SSIM 0.85 (no visible loss of the rings)
    ap.add_argument('--gif', action='store_true', help='a review GIF (gifski) instead of the AVIF')
    ap.add_argument('--gif-fps', default='25')
    ap.add_argument('--still', help='one composed frame to this PNG (segment --seg, --t s in, the divider at a third)')
    ap.add_argument('--seg', type=int, default=0)
    ap.add_argument('--t', type=float, default=2.0)
    ap.add_argument('--out')
    ap.add_argument('--frames-only', action='store_true', help='keep the composed frames in build/anim/puddle-ripples-<size>/frames, no encode (anim-encode.py variants)')
    a = ap.parse_args()
    cw = sh = a.size
    s = cw / 630
    strip = round(80 * s)
    strip += strip % 2
    k = 0.75 if a.size <= 700 else 1.0
    sw = cw - strip
    base = np.asarray(anim.chrome(a.theme, LABEL, sh, cw, strip, round(42 * s), round(26 * k))).copy()
    ov = anim.visual_overlay(('STOCK', 'ENHANCED'), sw, sh, k)
    segs = a.still and [SEGMENTS[a.seg]] or SEGMENTS
    pairs = {}
    for st, en, _, _ in segs:
        caps = [props.load(st), props.load(en)]
        pairs[(st, en)] = (caps, props.Matcher(*caps))
    if a.still:
        st, en, t0, box = SEGMENTS[a.seg]
        caps, match = pairs[(st, en)]
        left, right = (props.frame(c, i, box, sw, sh) for c, i in zip(caps, match(t0 + a.t)))
        Image.fromarray(anim.compose(base, left, right, ov, sw // 3, strip)).save(a.still)
        print('still', a.still)
        return
    seq = anim.divider_positions()
    work = f'build/anim/puddle-ripples-{cw}'
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(f'{work}/frames')
    i = 0
    for st, en, t0, box in SEGMENTS:
        caps, match = pairs[(st, en)]
        match.last = 0
        for j, pos in enumerate(seq):
            left, right = (props.frame(c, n, box, sw, sh) for c, n in zip(caps, match(t0 + j / 60.0)))
            Image.fromarray(anim.compose(base, left, right, ov, round(sw * pos), strip)).save(f'{work}/frames/f{i:05d}.png', compress_level=1)
            i += 1
    print(f'== puddle-ripples: {i} frames {cw}x{sh}')
    if a.frames_only:
        return
    out = a.out or ('workshop-media/template-puddle-ripples.gif' if a.gif else 'workshop-media/puddle-ripples.avif')
    anim.encode(a, work, base.shape, out)


if __name__ == '__main__':
    main()
