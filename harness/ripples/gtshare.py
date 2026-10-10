#!/usr/bin/env python3
"""Share of the game thread's sampled stacks (pzopt-stacks.out, 25 Hz GameThreadProfile) inside pzopt.Ripples, by leaf.

usage: gtshare.py <run> [pattern=Ripples]
"""
import collections
import sys

run = sys.argv[1]
pat = sys.argv[2] if len(sys.argv) > 2 else "Ripples"
names = {}
tot = 0
hit = collections.Counter()
for line in open(run + "/pzopt-stacks.out", errors="replace"):
    line = line.rstrip("\n")
    if line.startswith("f "):
        _, i, name = line.split(" ", 2)
        names[i] = name
        continue
    if not line or line.startswith("#") or line.startswith("t "):
        continue
    stack, _, n = line.rpartition(" ")
    if not n.isdigit():
        continue
    n = int(n)
    tot += n
    fr = [names.get(i, i) for i in stack.split(";")]
    idx = [k for k, f in enumerate(fr) if pat in f]
    if idx:
        k = idx[0]
        hit[" > ".join(fr[k:k + 4])] += n
s = sum(hit.values())
print(f"samples {tot}, in {pat}: {s} ({100 * s / max(1, tot):.2f} % of the game thread)")
for k, v in hit.most_common(15):
    print(f"  {v:5d} {k[:230]}")
