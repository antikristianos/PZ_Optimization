#!/usr/bin/env python3
"""levels.py X0 X1 Y0 Y1: every tile above level 0 (and roof / canopy tiles on it) in that box, with its count and rain flags."""
import os, sys, collections; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from area import get, PROPS, blocks
X0, X1, Y0, Y1 = map(int, sys.argv[1:5])
c = collections.Counter()
for z in range(0, 4):
    for x in range(X0, X1):
        for y in range(Y0, Y1):
            s = get(x, y, z)
            if s:
                for n in s[1]:
                    if z > 0 or "roof" in n or "anopy" in n: c[(z, n)] += 1
for (z, n), k in sorted(c.items()):
    p = PROPS.get(n, {})
    print(z, n, k, "BLOCKS" if blocks(n) else "", ",".join(t for t in ("WestRoofB","WestRoofM","WestRoofT","RoofGroup","solidfloor","BlockRain","Translucent") if t in p), p.get("CustomName",""))
