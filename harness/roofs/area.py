#!/usr/bin/env python3
"""area.py find TILE... | area.py show X Y R : per square of level 0 the game's exterior rule and the tiles one level up."""
import sys, os, struct, glob, collections
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from covered import header, MAPDIR, PROPS, roofish, blocks
def decode(cx, cy):
    tiles, lo, hi = header(os.path.join(MAPDIR, f"{cx}_{cy}.lotheader"))
    pk = open(os.path.join(MAPDIR, f"world_{cx}_{cy}.lotpack"), "rb").read()
    ver = struct.unpack_from("<i", pk, 4)[0] if pk[:4] == b"LOTP" else 0
    base = (8 if ver >= 1 else 0) + 4
    sq = {}
    for lwx in range(32):
        for lwy in range(32):
            pos = struct.unpack_from("<i", pk, base + (lwx * 32 + lwy) * 8)[0]; skip = 0
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
                        sq[(cx*256+lwx*8+x, cy*256+lwy*8+y, z)] = (room, [tiles[i] for i in ids])
    return sq
CACHE = {}
def get(x, y, z):
    k = (x // 256, y // 256)
    if k not in CACHE:
        CACHE[k] = decode(*k) if os.path.exists(os.path.join(MAPDIR, f"{k[0]}_{k[1]}.lotheader")) else {}
    return CACHE[k].get((x, y, z))
def exterior(x, y, z):
    s = get(x, y, z)
    if s and s[0] != -1: return "R"
    for z2 in range(max(z + 1, 1), 9):
        t = get(x, y, z2)
        if t and any(blocks(n) for n in t[1]): return "h"
    return "."
if __name__ != "__main__":
    pass
elif sys.argv[1] == "find":
    want = set(sys.argv[2:])
    for hp in glob.glob(os.path.join(MAPDIR, "*.lotheader")):
        tiles, lo, hi = header(hp)
        if not want & set(tiles): continue
        cx, cy = map(int, os.path.basename(hp)[:-10].split("_"))
        for (x, y, z), (r, ns) in decode(cx, cy).items():
            if want & set(ns): print(x, y, z, [n for n in ns if n in want])
else:
    X, Y, R = map(int, sys.argv[2:5])
    legend = {}
    print("level 0: R room, h haveRoof (rain-blocking tile above), . exterior;  then the level-1 tile code")
    for y in range(Y - R, Y + R + 1):
        row = []
        for x in range(X - R, X + R + 1):
            up = get(x, y, 1)
            names = [n for n in (up[1] if up else []) if roofish(n) or "solidfloor" in PROPS.get(n, {}) or "BlockRain" in PROPS.get(n, {}) or "anopy" in PROPS.get(n, {}).get("CustomName", "")]
            code = ""
            if names:
                key = names[0]
                if key not in legend: legend[key] = chr(ord('a') + len(legend) % 26) + ("" if len(legend) < 26 else str(len(legend) // 26))
                code = legend[key]
            row.append(f"{exterior(x, y, 0)}{code:<2}")
        print(f"{y:6d} " + " ".join(row))
    for k, v in legend.items():
        p = PROPS.get(k, {})
        print(v, k, "BLOCKS" if blocks(k) else "no-block", ",".join(t for t in ("WestRoofB","WestRoofM","WestRoofT","RoofGroup","solidfloor","BlockRain") if t in p))
