#!/usr/bin/env python3
"""Census with the lot header's RoomDefs: squares with a roof / floor tile 1-2 levels above that the game flags exterior."""
import os, sys, glob, struct, collections, pickle
from multiprocessing import Pool
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from covered import MAPDIR, PROPS, roofish, blocks, floor

def rstr(data, pos):
    e = data.index(b"\n", pos); return data[pos:e].decode("latin-1").strip(), e + 1

def header(path, cx, cy):
    data = open(path, "rb").read()
    pos = 4 if data[:4] == b"LOTH" else 0
    ver, n = struct.unpack_from("<ii", data, pos); pos += 8
    tiles = []
    for _ in range(n):
        t, pos = rstr(data, pos); tiles.append(t)
    if ver == 0: pos += 1
    pos += 8
    if ver == 0: lo, hi = 0, struct.unpack_from("<i", data, pos)[0] - 1; pos += 4
    else: lo, hi = struct.unpack_from("<ii", data, pos); pos += 8
    rooms = set(); empty = set()
    nr = struct.unpack_from("<i", data, pos)[0]; pos += 4
    for _ in range(nr):
        name, pos = rstr(data, pos)
        lvl, nrect = struct.unpack_from("<ii", data, pos); pos += 8
        tgt = empty if name.lower() == "emptyoutside" else rooms
        for _ in range(nrect):
            rx, ry, rw, rh = struct.unpack_from("<iiii", data, pos); pos += 16
            for a in range(rw):
                for b in range(rh):
                    tgt.add((cx * 256 + rx + a, cy * 256 + ry + b, lvl))
        nobj = struct.unpack_from("<i", data, pos)[0]; pos += 4 + 12 * nobj
    return tiles, lo, hi, rooms, empty

def cell(hp):
    cx, cy = map(int, os.path.basename(hp)[:-10].split("_"))
    tiles, lo, hi, rooms, empty = header(hp, cx, cy)
    R = [roofish(t) for t in tiles]; B = [blocks(t) for t in tiles]; F = [floor(t) for t in tiles]
    pk = open(os.path.join(MAPDIR, f"world_{cx}_{cy}.lotpack"), "rb").read()
    ver = struct.unpack_from("<i", pk, 4)[0] if pk[:4] == b"LOTP" else 0
    base = (8 if ver >= 1 else 0) + 4
    out = collections.Counter(); tilec = collections.Counter(); ex = collections.defaultdict(list)
    for lwx in range(32):
        for lwy in range(32):
            pos = struct.unpack_from("<i", pk, base + (lwx * 32 + lwy) * 8)[0]
            sq = {}; skip = 0
            for z in range(max(lo, -32), min(hi, 31) + 1):
                for x in range(8):
                    for y in range(8):
                        if skip > 0: skip -= 1; continue
                        count = struct.unpack_from("<i", pk, pos)[0]; pos += 4
                        if count == -1:
                            skip = struct.unpack_from("<i", pk, pos)[0]; pos += 4
                            if skip > 0: skip -= 1
                            continue
                        if count <= 1: continue
                        pos += 4
                        sq[(x, y, z)] = struct.unpack_from(f"<{count-1}i", pk, pos); pos += 4 * (count - 1)
            cols = collections.defaultdict(list)
            for (x, y, z) in sq: cols[(x, y)].append(z)
            for (x, y), zs in cols.items():
                for z in zs:
                    if z < 0 or not any(F[i] for i in sq[(x, y, z)]): continue
                    cover = [i for z2 in (z + 1, z + 2) if (x, y, z2) in sq for i in sq[(x, y, z2)] if R[i] or F[i]]
                    if not cover: continue
                    gx, gy = cx * 256 + lwx * 8 + x, cy * 256 + lwy * 8 + y
                    have_roof = any(z2 > z and z2 >= 1 and any(B[i] for i in sq[(x, y, z2)]) for z2 in zs)
                    in_room = (gx, gy, z) in rooms
                    if in_room or have_roof:
                        out["not-exterior"] += 1; continue
                    near = any((gx + a, gy + b, z) in rooms for a in (-1, 0, 1) for b in (-1, 0, 1))
                    kind = ("emptyoutside" if (gx, gy, z) in empty else "plain") + ("/beside-room" if near else "/standalone")
                    out["EXTERIOR " + kind] += 1
                    top = tiles[cover[0]]
                    tilec[(kind, top)] += 1
                    if len(ex[(kind, top)]) < 3: ex[(kind, top)].append((gx, gy, z))
    return out, tilec, ex

if __name__ == "__main__":
    hs = sorted(glob.glob(os.path.join(MAPDIR, "*.lotheader")))
    tot = collections.Counter(); tilec = collections.Counter(); ex = collections.defaultdict(list)
    with Pool(14) as p:
        for o, t, e in p.imap_unordered(cell, hs, chunksize=8):
            tot.update(o); tilec.update(t)
            for k, v in e.items():
                if len(ex[k]) < 4: ex[k].extend(v[:4 - len(ex[k])])
    pickle.dump((tot, tilec, dict(ex)), open(sys.argv[1], "wb"))
    for k, v in sorted(tot.items()): print(f"{v:9d} {k}")
    for kind in sorted(set(k for k, _ in tilec)):
        print("==", kind)
        for (k, n), c in tilec.most_common():
            if k != kind: continue
            if c < 150: break
            p = PROPS.get(n, {})
            print(f"  {c:7d} {n:34s} {','.join(t for t in ('WestRoofB','WestRoofM','WestRoofT','RoofGroup','solidfloor','BlockRain') if t in p):30s} {ex[(k, n)][:3]}")
