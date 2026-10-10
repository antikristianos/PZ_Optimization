#!/usr/bin/env python3
"""List world squares holding given tiles, from the game's .lotheader / .lotpack files (B42 format, IsoMetaGrid / IsoLot).

usage: findtile.py MAPDIR NEAR_X NEAR_Y TILE [TILE...]
"""
import glob, os, struct, sys

mapdir, nx, ny, want = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), set(sys.argv[4:])


def header(path):
    data = open(path, "rb").read()
    pos = 4 if data[:4] == b"LOTH" else 0
    ver, n = struct.unpack_from("<ii", data, pos)
    pos += 8
    tiles = []
    for _ in range(n):
        e = data.index(b"\n", pos)
        tiles.append(data[pos:e].decode("latin-1").strip())
        pos = e + 1
    if ver == 0:
        pos += 1
    w, h = struct.unpack_from("<ii", data, pos)
    pos += 8
    if ver == 0:
        lo, hi = 0, struct.unpack_from("<i", data, pos)[0] - 1
    else:
        lo, hi = struct.unpack_from("<ii", data, pos)
    return tiles, lo, hi


hits = []
for hp in glob.glob(os.path.join(mapdir, "*.lotheader")):
    cx, cy = map(int, os.path.basename(hp)[:-10].split("_"))
    tiles, lo, hi = header(hp)
    idx = {i: t for i, t in enumerate(tiles) if t in want}
    if not idx:
        continue
    pk = open(os.path.join(mapdir, f"world_{cx}_{cy}.lotpack"), "rb").read()
    ver = struct.unpack_from("<i", pk, 4)[0] if pk[:4] == b"LOTP" else 0
    base = (8 if ver >= 1 else 0) + 4
    for lwx in range(32):
        for lwy in range(32):
            pos = struct.unpack_from("<i", pk, base + (lwx * 32 + lwy) * 8)[0]
            skip = 0
            for z in range(max(lo, -32), min(hi, 31) + 1):
                for x in range(8):
                    for y in range(8):
                        if skip > 0:
                            skip -= 1
                            continue
                        count = struct.unpack_from("<i", pk, pos)[0]; pos += 4
                        if count == -1:
                            skip = struct.unpack_from("<i", pk, pos)[0]; pos += 4
                            if skip > 0:
                                skip -= 1
                            continue
                        if count <= 1:
                            continue
                        pos += 4  # room
                        for _ in range(count - 1):
                            d = struct.unpack_from("<i", pk, pos)[0]; pos += 4
                            if d in idx:
                                hits.append((cx * 256 + lwx * 8 + x, cy * 256 + lwy * 8 + y, z, idx[d]))
hits.sort(key=lambda h: (h[0] - nx) ** 2 + (h[1] - ny) ** 2)
print(len(hits), "squares")
for h in hits[:40]:
    print(f"{h[0]},{h[1]},{h[2]} {h[3]}  dist {((h[0] - nx) ** 2 + (h[1] - ny) ** 2) ** 0.5:.0f}")
