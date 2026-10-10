#!/usr/bin/env python3
"""Reflective props (pzopt prop reflections): every tile sprite that is not a window or an IsMirror tile but has glass, a
screen, polished metal or glazed ceramic the camera can see, with its material class.

Classes (one per sprite, first rule that matches):
  mirror   the gym's wall mirrors (recreational_sports_01_74-79: no IsMirror property)
  screen   televisions, monitors, computers, arcade / pinball / jukebox fronts (dark glass)
  glass    a glass material or group (doors, store-front panes, railings, shower screens, display cases, glass-door fridges,
           the glass table, chandeliers), or a sliding glass door's leaf (only its DoorSound, from the .patch.tiles file,
           says glass): its translucent texels, else its light grey-blue texels
  steel    steel / chrome / industrial counters, sinks, fridges, ovens, microwaves, toasters, ladders, 50s diner tables
  ceramic  toilets, white / beige sinks, bathtubs

usage: catalog.py [--list] [--sheet OUT.png] [--class C]
"""
import argparse, glob, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "mirrors"))
import tiles  # noqa: E402

GAME = tiles.GAME

WINDOW_KEYS = ("WindowN", "WindowW", "windowN", "windowW", "IsMirror", "GlassRemovedOffset", "WindowShape")
GYM_MIRRORS = {f"recreational_sports_01_{i}" for i in range(74, 80)}
SCREEN = re.compile(r"television|monitors|computer|arcade|pinball|jukebox", re.I)
STEEL = re.compile(r"steel|chrome|stainless|industrial (sink|oven|fridge)|^industrial$|microwave|toaster|modern oven|diner table|large industrial sink|dark industrial sink", re.I)
CERAMIC = re.compile(r"toilet|sink|bathtub|bath tub|urinal", re.I)


def glassy(v):
    return v is not None and "glass" in v.lower()


def classify(name, p):
    if any(k in p for k in WINDOW_KEYS):
        return None
    if name in GYM_MIRRORS:
        return "mirror"
    label = (p.get("GroupName", "") + " " + p.get("CustomName", "")).strip()
    if name.startswith("brokenglass"):
        return None
    if SCREEN.search(label):
        return "screen"
    if any(glassy(p.get(k)) for k in ("MaterialType", "Material", "Material2", "Material3", "GroupName", "CustomName")):
        return "glass"
    if p.get("DoorSound") == "SlidingGlassDoor":  # the leaf: Material=Door, doorTrans, no glass word (its fixed pane is a window)
        return "glass"
    mats = [p.get(k, "") for k in ("MaterialType", "Material", "Material2", "Material3")]
    if STEEL.search(label) or "Steel" in mats:
        return "steel"
    if (CERAMIC.search(label) or "Ceramic" in mats) and not name.startswith("walls_"):  # (tiled bathroom walls: every wall per frame)
        return "ceramic"
    return None


def catalog():
    props = {}  # a sprite's properties over every .tiles file (tiledefinitions_noiseworks.patch.tiles adds e.g. DoorSound)
    for f in sorted(glob.glob(os.path.join(GAME, "media", "*.tiles"))):
        for n, p in tiles.read(f):
            props.setdefault(n, {}).update(p)
    out = {}
    for n, p in props.items():
        c = classify(n, p)
        if c:
            out[n] = (c, p)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--list", action="store_true")
    ap.add_argument("--sheet")
    ap.add_argument("--class", dest="cls")
    a = ap.parse_args()
    cat = catalog()
    if a.cls:
        cat = {k: v for k, v in cat.items() if v[0] == a.cls}
    from collections import Counter
    print(Counter(c for c, _ in cat.values()))
    if a.list:
        for n, (c, p) in cat.items():
            print(f"{c:8s} {n:40s} {p.get('GroupName', '')} {p.get('CustomName', '')} facing={p.get('Facing', '')}")
    if a.sheet:
        import packsprite
        from PIL import Image, ImageDraw
        imgs = dict(packsprite.pages(os.path.join(GAME, "media", "texturepacks", "Tiles2x.pack"), set(cat)))
        names = [n for n in cat if n in imgs]
        cols = 16
        cw, ch = 128, 256 + 14
        sheet = Image.new("RGB", (cols * cw, ((len(names) + cols - 1) // cols) * ch), (255, 0, 255))
        d = ImageDraw.Draw(sheet)
        for i, n in enumerate(names):
            r, c = divmod(i, cols)
            im = imgs[n].resize((128, 256))
            sheet.paste(im, (c * cw, r * ch), im)
            d.text((c * cw + 2, r * ch + 256), n.replace("location_", "")[-22:], fill=(255, 255, 255))
        sheet.save(a.sheet)
        print("sheet", a.sheet, len(names), "sprites")


if __name__ == "__main__":
    main()
