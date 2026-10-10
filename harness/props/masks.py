#!/usr/bin/env python3
"""The prop reflection atlas for pzopt.Props: per texel of every reflective prop sprite (harness/props/catalog.py), the
face it lies on, that face's plane and how much of it reflects.

Each sprite's depth map (media/depthmaps, else the tile it is assigned in tileDepthTextureAssignments.txt) gives the iso
depth of every texel: in the 2x frame (128 x 256) a texel (X, Y) is at u = x - y = (X - 64) / 64, v = x + y - 6z =
(Y - 192) / 32 relative to its square's north floor corner, with w = x + y + 2z = 4 (1 - d) (d = blue / 255; the square's
far corner (x, y, z) is d = 1, its near top corner (x + 1, y + 1, z + 1) d = 0). A plane fitted to w over (u, v) around the
texel names its face: a top (z = c: dw/du 0, dw/dv 1), a south face (y = c: 4/3, -1/3) or an east face (x = c: -4/3, -1/3),
the only faces the camera sees; of four windows round the texel the best fit wins, so a texel on a fold keeps its own face.

Atlas: RG8, one 64 x 128 cell per sprite (the 2x frame halved; Tiles1x frames map the same way), 32 cells a row.
  R = face << 6 | offset, face 0 none, 1 top, 2 south, 3 east; offset q (0..63): the face's plane at (q / 32 - 0.25)
      squares (south: y, east: x) or levels (top: z) from the square's north floor corner
  G = reflectance weight 0..255 (the class strength is applied at run time; mode "alpha": the sprite's translucent texels
      decide at run time, G marks the faces)
props.txt: sprite cell class mode axis offset top   (axis / offset: the main vertical face, top: the reflecting top's height
  (levels), both for the mirrored characters; -1 none)

usage: masks.py [--out src/media/ui/pzopt/props] [--sheet /tmp/prop-faces.png]
"""
import argparse, os, re, sys
from collections import Counter, deque
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(HERE, "..", "mirrors"))
import catalog, packsprite  # noqa: E402

M = os.path.join(catalog.GAME, "media")
CW, CH, COLS = 64, 128, 32
FACES = np.array([[0.0, 1.0], [4 / 3, -1 / 3], [-4 / 3, -1 / 3]], np.float32)  # top, south, east


def assignments():
    a = {}
    for line in open(os.path.join(M, "tileDepthTextureAssignments.txt")):
        m = re.match(r"\s*(\S+)\s*=\s*(\S+?),?\s*$", line)
        if m and m.group(1) != "VERSION":
            a[m.group(1)] = m.group(2)
    return a


_DEPTH = {}


def depth_tile(name):
    ts, idx = name.rsplit("_", 1)
    idx = int(idx)
    if ts not in _DEPTH:
        p = os.path.join(M, "depthmaps", "DEPTH_" + ts + ".png")
        _DEPTH[ts] = np.asarray(Image.open(p).convert("RGBA")) if os.path.exists(p) else None
    a = _DEPTH[ts]
    if a is None:
        return None
    cols = a.shape[1] // 128
    r, c = divmod(idx, cols)
    if (r + 1) * 256 > a.shape[0]:
        return None
    t = a[r * 256:(r + 1) * 256, c * 128:(c + 1) * 128]
    if not (t[..., 3] > 0).any():
        return None
    return np.where(t[..., 3] > 0, t[..., 2].astype(np.float32) / 255.0, -1.0)


def faces_of(d):
    """Per 2x texel: face (0 none, 1 top, 2 south, 3 east) and the plane offset."""
    h, w = d.shape
    valid = d >= 0
    X, Y = np.meshgrid(np.arange(w, dtype=np.float32), np.arange(h, dtype=np.float32))
    U = (X + 0.5 - 64) / 64
    V = (Y + 0.5 - 192) / 32
    W = 4 * (1 - d)
    face = np.zeros((h, w), np.int8)
    off = np.zeros((h, w), np.float32)
    R = 4
    # least squares w = a u + b v + c over every R x R window (box sums of the normal equations), five windows round each
    # texel, the best fit wins
    vm = valid.astype(np.float64)
    Uc, Vc = (U - U.mean()) * vm, (V - V.mean()) * vm  # (centred: better conditioned)
    Wd = np.where(valid, W, 0).astype(np.float64)
    terms = [Uc * Uc, Uc * Vc, Uc, Vc * Vc, Vc, vm, Uc * Wd, Vc * Wd, Wd, Wd * Wd]

    def box(a):
        p = np.pad(a, ((R, R), (R, R)))
        c = np.cumsum(np.cumsum(np.pad(p, ((1, 0), (1, 0))), 0), 1)
        return c[R:, R:] - c[:-R, R:] - c[R:, :-R] + c[:-R, :-R]  # window [i, i + R) of the padded array

    S = [box(t) for t in terms]  # S[k][i, j]: window starting at padded (i, j) = original (i - R, j - R)
    best_err = np.full((h, w), np.inf)
    best_g = np.zeros((h, w, 2))
    ys, xs = np.mgrid[0:h, 0:w]
    for oy, ox in ((-R + 1, -R + 1), (-R + 1, 0), (0, -R + 1), (0, 0), (-R // 2, -R // 2)):
        iy, ix = ys + oy + R, xs + ox + R
        uu, uv, u1, vv, v1, n, uw, vw, w1, ww = (s[iy, ix] for s in S)
        A = np.stack([np.stack([uu, uv, u1], -1), np.stack([uv, vv, v1], -1), np.stack([u1, v1, n], -1)], -2)
        b = np.stack([uw, vw, w1], -1)
        ok = (n >= 6) & (np.abs(np.linalg.det(A)) > 1e-9)
        A[~ok] = np.eye(3)
        sol = np.linalg.solve(A, b[..., None])[..., 0]
        err = (ww - 2 * (sol * b).sum(-1) + np.einsum("...i,...ij,...j", sol, A, sol)) / np.maximum(n, 1)
        err = np.where(ok, err, np.inf)
        better = err < best_err
        best_err = np.where(better, err, best_err)
        best_g[better] = sol[better][:, :2]
    dist = np.linalg.norm(best_g[:, :, None, :] - FACES[None, None], axis=-1)
    k = np.argmin(dist, -1)
    good = valid & np.isfinite(best_err) & (np.take_along_axis(dist, k[..., None], -1)[..., 0] <= 0.6)  # (else curved: no single face)
    z = (W - V) / 8
    s = W - 2 * z
    px, py = (s + U) / 2, (s - U) / 2
    face = np.where(good, k + 1, 0).astype(np.int8)
    off = np.choose(k, [z, py, px]).astype(np.float32)
    # each face's plane: the median offset of its texels nearby (the depth is 8-bit: 0.016 squares a step)
    sm = off.copy()
    for y, x in zip(*np.nonzero(face)):
        ys, xs = slice(max(0, y - 3), y + 4), slice(max(0, x - 3), x + 4)
        same = face[ys, xs] == face[y, x]
        sm[y, x] = np.median(off[ys, xs][same])
    return face, sm


def components(m):
    lab = np.zeros(m.shape, np.int32)
    n = 0
    h, w = m.shape
    for y0, x0 in zip(*np.nonzero(m)):
        if lab[y0, x0]:
            continue
        n += 1
        lab[y0, x0] = n
        q = deque([(y0, x0)])
        while q:
            y, x = q.popleft()
            for yy, xx in ((y + 1, x), (y - 1, x), (y, x + 1), (y, x - 1)):
                if 0 <= yy < h and 0 <= xx < w and m[yy, xx] and not lab[yy, xx]:
                    lab[yy, xx] = n
                    q.append((yy, xx))
    return lab, n


def largest(m):
    lab, n = components(m)
    if n == 0:
        return m
    return lab == 1 + int(np.argmax(np.bincount(lab.ravel())[1:]))


def erode(m):
    return m & np.roll(m, 1, 0) & np.roll(m, -1, 0) & np.roll(m, 1, 1) & np.roll(m, -1, 1)


def dilate(m):
    return m | np.roll(m, 1, 0) | np.roll(m, -1, 0) | np.roll(m, 1, 1) | np.roll(m, -1, 1)


def weight_of(cls, props, img, face, off):
    a = np.asarray(img).astype(np.float32) / 255.0
    rgb, al = a[..., :3], a[..., 3]
    mx, mn = rgb.max(-1), rgb.min(-1)
    sat = (mx - mn) / np.maximum(mx, 1e-3)
    lum = rgb @ np.array([0.299, 0.587, 0.114], np.float32)
    opaque = al > 0.9
    has = face > 0
    trans = (al > 0.1) & (al < 0.97)
    mode = "atlas"
    if cls == "glass":
        if trans.sum() > 0.03 * max(1, (al > 0.1).sum()):
            mode = "alpha"
            m = has & (al > 0.1) & (lum > 0.3)  # (a sprite's soft drop shadow is translucent too, but dark)
        elif props.get("DoorSound") == "SlidingGlassDoor":
            return None, mode  # an open sliding leaf is its bare frame (the glass slid behind the fixed pane): the white frame is no glass
        else:
            m = has & opaque & (lum > 0.45) & (sat < 0.3) & (rgb[..., 2] >= rgb[..., 0] - 0.02) & (face >= 2)  # (opaque glass is a door / front: a light grey top is the cabinet)
            m = erode(dilate(m))
    elif cls == "screen":
        if props.get("Facing") in ("N", "W"):
            return None, mode
        m = has & opaque & (lum < 0.33) & (sat < 0.45) & (face >= 2)
        m = largest(m)
        m = erode(dilate(dilate(m)) & has)
    elif cls == "mirror":
        m = has & opaque & (lum > 0.42) & (sat < 0.32)
        m = erode(m)
    elif cls == "steel":
        m = has & opaque & (sat < 0.25) & (lum > 0.25) & (lum < 0.97)
    else:  # ceramic
        m = has & opaque & (lum > 0.55) & (sat < 0.22)
    m &= ~((face == 1) & (off < 0.06))  # nothing lying on the floor is a reflecting top (shadows, rugs under the prop)
    if m.sum() < 12:
        return None, mode
    return (m * 255).astype(np.uint8), mode


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(HERE, "..", "..", "src", "media", "ui", "pzopt", "props"))
    ap.add_argument("--sheet")
    ap.add_argument("--only")
    a = ap.parse_args()
    cat = catalog.catalog()
    if a.only:
        cat = {k: v for k, v in cat.items() if re.search(a.only, k)}
    asg = assignments()
    imgs = dict(packsprite.pages(os.path.join(M, "texturepacks", "Tiles2x.pack"), set(cat)))
    cells, lines, sheet_items = [], [], []
    for n, (cls, props) in cat.items():
        if n not in imgs:
            continue
        d = depth_tile(n)
        if d is None and n in asg:
            d = depth_tile(asg[n])
        if d is None:
            continue
        img = imgs[n]
        if img.size != (128, 256):
            img = img.resize((128, 256), Image.NEAREST)
        face, off = faces_of(d)
        wgt, mode = weight_of(cls, props, img, face, off)
        if wgt is None:
            continue
        face = np.where(wgt > 0, face, 0)
        q = np.clip(np.round((off + 0.25) * 32), 0, 63).astype(np.uint8)
        r8 = np.where(face > 0, (face.astype(np.uint8) << 6) | q, 0).astype(np.uint8)
        # halve: each 1x texel takes the 2x texel of its block with the commonest face
        R1 = np.zeros((CH, CW), np.uint8)
        G1 = np.zeros((CH, CW), np.uint8)
        for y in range(CH):
            for x in range(CW):
                blk = r8[2 * y:2 * y + 2, 2 * x:2 * x + 2].ravel()
                gb = wgt[2 * y:2 * y + 2, 2 * x:2 * x + 2].ravel()
                fs = blk >> 6
                if (fs > 0).sum() == 0:
                    continue
                f = Counter(fs[fs > 0].tolist()).most_common(1)[0][0]
                R1[y, x] = int(np.median(blk[fs == f]))
                G1[y, x] = int(gb.mean())
        vert = face >= 2
        axis, voff = -1, 0.0
        if (vert & (wgt > 0)).sum() > 40:
            fs = face[vert & (wgt > 0)]
            f = Counter(fs.tolist()).most_common(1)[0][0]
            axis = 0 if f == 2 else 1
            voff = float(np.median(off[(face == f) & (wgt > 0)]))
        top = -1.0
        if ((face == 1) & (wgt > 0)).sum() > 40:
            top = float(np.median(off[(face == 1) & (wgt > 0)]))
        ys, xs = np.nonzero(wgt > 0)
        bb = (xs.min() / 128.0, ys.min() / 256.0, (xs.max() + 1) / 128.0, (ys.max() + 1) / 256.0)  # the reflective texels' box (frame fractions)
        idx = len(cells)
        cells.append((R1, G1))
        lines.append(f"{n} {idx} {cls} {mode} {axis} {voff:.3f} {top:.3f} {bb[0]:.4f} {bb[1]:.4f} {bb[2]:.4f} {bb[3]:.4f}")
        sheet_items.append((n, img, face, wgt))
        print(f"{n:40s} {cls:8s} {mode:5s} cell {idx:3d} faces {Counter(face[face > 0].tolist())} axis {axis} {voff:.2f}")
    rows = (len(cells) + COLS - 1) // COLS
    atlas = np.zeros((rows * CH, COLS * CW, 3), np.uint8)
    for i, (R1, G1) in enumerate(cells):
        r, c = divmod(i, COLS)
        atlas[r * CH:(r + 1) * CH, c * CW:(c + 1) * CW, 0] = R1
        atlas[r * CH:(r + 1) * CH, c * CW:(c + 1) * CW, 1] = G1
    os.makedirs(a.out, exist_ok=True)
    if not a.only:
        Image.fromarray(atlas, "RGB").save(os.path.join(a.out, "prop-atlas.png"), optimize=True)
        open(os.path.join(a.out, "props.txt"), "w").write(
            "# pzopt.Props reflective props (harness/props/masks.py): sprite cell class mode axis offset top box(x0 y0 x1 y1); cells 64x128, 32 a row\n"
            + "\n".join(lines) + "\n")
    if a.sheet:
        cols = 16
        sh = Image.new("RGB", (cols * 128, ((len(sheet_items) + cols - 1) // cols) * 256), (40, 40, 40))
        pal = np.array([[0, 0, 0], [255, 220, 0], [0, 200, 255], [255, 60, 200]], np.uint8)
        for i, (n, img, face, wgt) in enumerate(sheet_items):
            r, c = divmod(i, cols)
            base = np.asarray(img.convert("RGB")).astype(np.float32)
            col = pal[face].astype(np.float32)
            k = (wgt[..., None] / 255.0) * 0.65
            out = base * (1 - k) + col * k
            out[np.asarray(img)[..., 3] < 10] = 40
            sh.paste(Image.fromarray(out.astype(np.uint8)), (c * 128, r * 256))
        sh.save(a.sheet)
        print("sheet", a.sheet)


if __name__ == "__main__":
    main()
