#!/usr/bin/env python3
"""Census: squares with a roof / canopy tile above them that the game still flags exterior (no room, no rain-blocking tile above)."""
import glob, os, struct, sys, collections, pickle
from multiprocessing import Pool
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "mirrors"))
from tiles import read, GAME
MAPDIR = os.path.join(GAME, "media/maps/Muldraugh, KY")
PROPS = {}
for f in sorted(glob.glob(os.path.join(GAME, "media", "*.tiles"))):
    for n, p in read(f):
        PROPS.setdefault(n, {}).update(p)
def roofish(n):
    p = PROPS.get(n)
    if p is None: return False
    if n.startswith("roofs_") or "RoofGroup" in p: return True
    return any(k in p for k in ("WestRoofB", "WestRoofM", "WestRoofT")) and "IsMoveAble" not in p
def blocks(n):
    p = PROPS.get(n); return p is not None and ("solidfloor" in p or "BlockRain" in p)
def floor(n):
    p = PROPS.get(n); return p is not None and "solidfloor" in p

def header(path):
    data = open(path, "rb").read()
    pos = 4 if data[:4] == b"LOTH" else 0
    ver, n = struct.unpack_from("<ii", data, pos); pos += 8
    tiles = []
    for _ in range(n):
        e = data.index(b"\n", pos); tiles.append(data[pos:e].decode("latin-1").strip()); pos = e + 1
    if ver == 0: pos += 1
    w, h = struct.unpack_from("<ii", data, pos); pos += 8
    if ver == 0: lo, hi = 0, struct.unpack_from("<i", data, pos)[0] - 1
    else: lo, hi = struct.unpack_from("<ii", data, pos)
    return tiles, lo, hi

def cell(hp):
    cx, cy = map(int, os.path.basename(hp)[:-10].split("_"))
    tiles, lo, hi = header(hp)
    R = [roofish(t) for t in tiles]; B = [blocks(t) for t in tiles]; F = [floor(t) for t in tiles]
    pk = open(os.path.join(MAPDIR, f"world_{cx}_{cy}.lotpack"), "rb").read()
    ver = struct.unpack_from("<i", pk, 4)[0] if pk[:4] == b"LOTP" else 0
    base = (8 if ver >= 1 else 0) + 4
    out = collections.Counter(); ex = collections.defaultdict(list); tilec = collections.Counter()
    for lwx in range(32):
        for lwy in range(32):
            pos = struct.unpack_from("<i", pk, base + (lwx * 32 + lwy) * 8)[0]
            sq = {}
            skip = 0
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
                        room = struct.unpack_from("<i", pk, pos)[0]; pos += 4
                        ids = struct.unpack_from(f"<{count-1}i", pk, pos); pos += 4 * (count - 1)
                        sq[(x, y, z)] = (room, ids)
            for x in range(8):
                for y in range(8):
                    zs = sorted(z for (a, b, z) in sq if a == x and b == y)
                    if not zs: continue
                    for z in zs:
                        room, ids = sq[(x, y, z)]
                        if not any(F[i] for i in ids): continue  # a square with a floor (ground, pavement, a floor)
                        have_roof = any(z2 > z and z2 >= 1 and any(B[i] for i in sq[(x, y, z2)][1]) for z2 in zs)
                        exterior = room == -1 and not have_roof
                        cover = [i for z2 in (z + 1, z + 2) if (x, y, z2) in sq for i in sq[(x, y, z2)][1] if R[i] or F[i]]
                        if not cover: continue
                        kind = "covered-exterior" if exterior else ("covered-room" if room != -1 else "covered-haveRoof")
                        out[kind] += 1
                        if exterior:
                            names = tuple(sorted(set(tiles[i] for i in cover)))
                            for n in set(tiles[i] for i in cover): tilec[n] += 1
                            if len(ex[names]) < 3: ex[names].append((cx * 256 + lwx * 8 + x, cy * 256 + lwy * 8 + y, z))
    return out, ex, tilec

if __name__ == "__main__":
    hs = sorted(glob.glob(os.path.join(MAPDIR, "*.lotheader")))
    tot = collections.Counter(); ex = {}; tilec = collections.Counter()
    with Pool(14) as p:
        for o, e, t in p.imap_unordered(cell, hs, chunksize=8):
            tot.update(o); tilec.update(t)
            for k, v in e.items():
                ex.setdefault(k, []).extend(v)
    pickle.dump((tot, ex, tilec), open(sys.argv[1], "wb"))
    print(tot)
    for n, c in tilec.most_common(40):
        p = PROPS.get(n, {})
        print(f"{c:7d} {n} {','.join(k for k in p if k in ('WestRoofB','WestRoofM','WestRoofT','RoofGroup','CustomName','GroupName'))} {p.get('CustomName','')} {p.get('GroupName','')}")
