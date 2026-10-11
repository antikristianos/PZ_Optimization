#!/usr/bin/env python3
"""roofmap.py X Y R: the squares round X,Y on level 0 by the game's exterior rule from the map files (R room, E emptyoutside,
h haveRoof = a rain-blocking tile above, . exterior, T map tree), with the level-1 roof / floor tile as a letter (upper case blocks rain)."""
import sys, os, collections; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from census import header, MAPDIR, PROPS, roofish, blocks
from area import get
X, Y, Rr = map(int, sys.argv[1:4])
cx, cy = X // 256, Y // 256
_, _, _, rooms, empty = header(os.path.join(MAPDIR, f"{cx}_{cy}.lotheader"), cx, cy)
legend = {}
print("level 0: R room, E emptyoutside, h haveRoof, . exterior; then level-1 roof/floor tile code (upper case = blocks rain)")
for y in range(Y - Rr, Y + Rr + 1):
    row = []
    for x in range(X - Rr, X + Rr + 1):
        hr = any(get(x, y, z2) and any(blocks(n) for n in get(x, y, z2)[1]) for z2 in range(1, 6))
        c0 = "R" if (x, y, 0) in rooms else "h" if hr else "E" if (x, y, 0) in empty else "."
        up = get(x, y, 1)
        names = [n for n in (up[1] if up else []) if roofish(n) or "solidfloor" in PROPS.get(n, {})]
        code = ""
        if names:
            k = names[-1]
            if k not in legend: legend[k] = chr(ord('a') + len(legend))
            code = legend[k].upper() if blocks(k) else legend[k]
        g0 = get(x, y, 0)
        if g0 and any(n.startswith("vegetation_trees_01") for n in g0[1]): c0 = "T" if c0 == "." else c0.lower() + "*"
        row.append(f"{c0}{code:<1}")
    print(f"{y:6d} " + " ".join(row))
print("x from", X - Rr)
for k, v in legend.items():
    print(v, k, "BLOCKS" if blocks(k) else "", ",".join(t for t in ("WestRoofB","WestRoofM","WestRoofT","RoofGroup") if t in PROPS.get(k, {})))
