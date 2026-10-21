#!/usr/bin/env python3
"""Logos des marques de radios (shared/data/radio_catalog.json, champ « logo ») et de toutes leurs webradios (radio_logos.json).
Une webradio sans logo propre prend celui de sa marque. Usage : python3 build_brand_logos.py   (long : une requête par station)."""
import concurrent.futures as cf
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.dirname(__file__))
import build_logos as bl  # noqa: E402
import catalog  # noqa: E402

DATA = os.path.join(os.path.dirname(__file__), "..", "..", "shared", "data")
CAT, LOGOS = os.path.join(DATA, "radio_catalog.json"), os.path.join(DATA, "radio_logos.json")
_home = {}


def home_logo(home):
    if home not in _home:
        _home[home] = bl.find(home) if home.startswith("http") else None
    return _home[home]


def own_logo(s, logos):
    """Logo propre d'une station : adresse de l'annuaire si elle répond, sinon celui du fichier, sinon celui de son site."""
    key = catalog.station_key(s["name"])
    if key in logos and bl.alive(logos[key]):
        return logos[key]
    art = catalog.fix_logo_url(s.get("favicon", ""))
    if art and bl.alive(art):
        return art
    return home_logo(s.get("homepage", ""))


def stations(b):
    out = []
    for q in b["q"].split("|"):
        try:
            rows = catalog.radio_get("stations/search?" + __import__("urllib.parse").parse.urlencode(
                {"hidebroken": "true", "order": "clickcount", "reverse": "true", "limit": 200, "countrycode": b["country"], "name": q.strip()}))
        except OSError:
            continue
        out += [r for r in rows if q.strip().lower() in r["name"].lower() and (r.get("url_resolved") or r.get("url"))]
    seen, uniq = set(), []
    for r in out:
        k = catalog.station_key(r["name"])
        if k not in seen:
            seen.add(k)
            uniq.append(r)
    return uniq


def main():
    cat, logos = json.load(open(CAT)), json.load(open(LOGOS))
    only = set(sys.argv[1:])   # « FR:NRJ » ... : ne refaire que ces marques
    for b in cat["brands"]:
        if only and f'{b["country"]}:{b["name"]}' not in only:
            continue
        rows = stations(b)
        main_key = catalog.station_key(b["name"])
        # logo de la marque : la station du même nom, sinon les plus écoutées
        ordered = sorted(rows, key=lambda r: catalog.station_key(r["name"]) != main_key)[:6]
        brand_logo = None
        for r in ordered:
            brand_logo = own_logo(r, logos)
            if brand_logo:
                break
        if brand_logo:
            b["logo"] = brand_logo
        with cf.ThreadPoolExecutor(32) as ex:
            got = list(ex.map(lambda r: own_logo(r, logos), rows))
        n = 0
        for r, lg in zip(rows, got):
            lg = lg or brand_logo
            if lg:
                logos[catalog.station_key(r["name"])] = lg
                n += 1
        print(b["country"], b["name"], len(rows), "stations,", n, "logos,", "marque:", "oui" if brand_logo else "non", flush=True)
        with open(CAT, "w") as f:
            json.dump(cat, f, ensure_ascii=False, indent=1)
        with open(LOGOS, "w") as f:
            json.dump(dict(sorted(logos.items())), f, ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    main()
