#!/usr/bin/env python3
"""The still under the puddle ripples card's AVIF (harness/ripples-card.py), in the reflective props still's layout
(harness/props-reflect-changes.py: the AVIFs' strip and label, Martian Mono, grey stock / white now): what ripples now
against stock, the measured cost (docs/findings-puddle-ripples-2026-10-10.md), the setting.

    python3 harness/ripples-changes.py [--size 630] [--out workshop-media/puddle-ripples-changes.png]
"""
import argparse
import importlib.util
import os

from PIL import Image, ImageDraw, ImageFont

here = os.path.dirname(__file__)
spec = importlib.util.spec_from_file_location('anim', os.path.join(here, 'efficiency-anim.py'))
anim = importlib.util.module_from_spec(spec)
spec.loader.exec_module(anim)
spec = importlib.util.spec_from_file_location('ent', os.path.join(here, 'entity-shadows-card.py'))
ent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ent)

INTRO = ('Whoever walks or drives through a puddle ripples it: real water, each step a train of rings that spreads, '
         'crosses the others and bends what the puddle mirrors')
# what, stock, now
ROWS = [
    ('You, zombies, animals', 'no rings', 'a ring each step'),
    ('Cars', 'no rings', 'a wake'),
    ('Rain', 'raindrops only', 'steps stir more'),
    ('Sky and reflections', 'still', 'bent by the rings'),
    ('A horde in a flood', '', 'max 160 rings/s'),
]
COST = [('Desktop, 190 rings, max zoom', '+0.009 ms'), ('Handheld, the same rings', '+0.019 ms'),
        ('Game thread, 450-zombie horde', '~0.006 ms')]
FOOT = ('Options > PZ Optimization > Visuals > Water and puddles: "Puddle ripples" (next launch). GPU time per frame, '
        'desktop (RTX 4090, 5120x2160) and Ayaneo Flip (Radeon 890M, 1920x1080); the rings are an iWave water '
        'simulation\'s own impulse response, binned per screen tile off the game thread.')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--size', type=int, default=630)
    ap.add_argument('--out', default='workshop-media/puddle-ripples-changes.png')
    ap.add_argument('--label', default='Puddle Ripples')
    ap.add_argument('--theme', default='light', choices=anim.THEMES)
    a = ap.parse_args()
    cw = sh = a.size
    s = cw / 630
    strip = round(80 * s)
    strip += strip % 2
    k = 0.75 if a.size <= 700 else 1.0
    pad = round(26 * k)
    img = anim.chrome(a.theme, a.label, sh, cw, strip, round(42 * s), pad)
    sw = cw - strip
    scene = Image.new('RGB', (sw, sh), (11, 11, 14))
    d = ImageDraw.Draw(scene)
    F = lambda px: ImageFont.truetype(anim.FONT, max(8, round(px * s)))
    cap, name, val, foot = F(10.5), F(12), F(12), F(9.6)
    x0, x1 = pad, sw - pad
    c_now = x1
    c_before = x1 - max(d.textlength(r[2], font=val) for r in ROWS) - round(22 * s)
    y = pad
    for line in ent.wrap(d, INTRO, cap, x1 - x0):
        d.text((x0, y), line, font=cap, fill=anim.CAPTION, anchor='lt')
        y += round(15 * s)
    y += round(10 * s)
    d.text((x0, y), 'WHAT', font=cap, fill=anim.CAPTION, anchor='lt')
    d.text((c_before, y), 'STOCK', font=cap, fill=anim.CAPTION, anchor='rt')
    d.text((c_now, y), 'NOW', font=cap, fill=anim.CAPTION, anchor='rt')
    y += round(20 * s)
    d.line((x0, y, x1, y), fill=(60, 60, 66), width=1)
    row_h = round(33 * s)
    for what, before, now in ROWS:
        cy = y + row_h // 2
        d.text((x0, cy), what, font=name, fill=anim.AFTER, anchor='lm')
        d.text((c_before, cy), before, font=cap, fill=anim.BEFORE, anchor='rm')
        d.text((c_now, cy), now, font=val, fill=anim.AFTER, anchor='rm')
        y += row_h
    d.line((x0, y, x1, y), fill=(40, 40, 46), width=1)
    y += round(14 * s)
    d.text((x0, y), 'COST PER FRAME', font=cap, fill=anim.CAPTION, anchor='lt')
    y += round(18 * s)
    for what, cost in COST:
        cy = y + round(11 * s)
        d.text((x0, cy), what, font=cap, fill=anim.BEFORE, anchor='lm')
        d.text((c_now, cy), cost, font=val, fill=anim.AFTER, anchor='rm')
        y += round(24 * s)
    lines = ent.wrap(d, FOOT, foot, x1 - x0)
    lh = round(13.5 * s)
    fy = sh - pad - lh * len(lines)
    d.line((x0, fy - round(10 * s), x1, fy - round(10 * s)), fill=(40, 40, 46), width=1)
    for line in lines:
        d.text((x0, fy), line, font=foot, fill=(120, 120, 126), anchor='lt')
        fy += lh
    if y > fy - lh * len(lines) - round(14 * s):
        print('WARNING: the rows run into the footer')
    img.paste(scene, (strip, 0))
    os.makedirs(os.path.dirname(a.out) or '.', exist_ok=True)
    img.save(a.out)
    print('->', a.out, img.size)


if __name__ == '__main__':
    main()
