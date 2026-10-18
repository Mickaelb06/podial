#!/usr/bin/env python3
"""Génère world.json (contours des pays) à partir de Natural Earth 110m (domaine public) : tools/make_world.py ne110.json
Format : {"FR": [[x1,y1,x2,y2,...], ...]} avec x = longitude × 10, y = latitude × 10 (anneaux de polygones, trous inclus)."""
import json, math, sys

def rdp(pts, eps):
    if len(pts) < 3: return pts
    (x1, y1), (x2, y2) = pts[0], pts[-1]
    dx, dy = x2 - x1, y2 - y1
    norm = math.hypot(dx, dy) or 1e-9
    idx, dmax = 0, 0
    for i in range(1, len(pts) - 1):
        d = abs(dy * (pts[i][0] - x1) - dx * (pts[i][1] - y1)) / norm
        if d > dmax: idx, dmax = i, d
    if dmax > eps:
        return rdp(pts[:idx + 1], eps)[:-1] + rdp(pts[idx:], eps)
    return [pts[0], pts[-1]]

data = json.load(open(sys.argv[1]))
out = {}
for f in data["features"]:
    p = f["properties"]
    code = p.get("ISO_A2_EH") if p.get("ISO_A2") == "-99" else p.get("ISO_A2")
    if not code or code == "-99" or code == "AQ":
        continue
    g = f["geometry"]
    polys = g["coordinates"] if g["type"] == "MultiPolygon" else [g["coordinates"]]
    rings = []
    for poly in polys:
        for ring in poly:
            raw = [(round(x, 1), round(y, 1)) for x, y in ring]
            if len(raw) > 4:  # anneau fermé : on le coupe en deux pour que la simplification ait une base
                m = len(raw) // 2
                pts = rdp(raw[:m + 1], 0.12)[:-1] + rdp(raw[m:], 0.12)
            else:
                pts = raw
            flat = []
            for x, y in pts:
                flat += [int(round(x * 10)), int(round(y * 10))]
            if len(flat) >= 6:
                rings.append(flat)
    out.setdefault(code, []).extend(rings)
json.dump(out, open(sys.argv[2] if len(sys.argv) > 2 else "world.json", "w"), separators=(",", ":"))
print(len(out), "pays")
