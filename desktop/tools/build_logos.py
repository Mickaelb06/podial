#!/usr/bin/env python3
"""Construit shared/data/radio_logos.json : logo de radio (clé = nom normalisé) pour les stations dont l'annuaire n'a pas de logo
ou dont l'adresse est morte. Le logo est lu sur le site de la station (apple-touch-icon / icon), puis vérifié (c'est bien une image).
Usage : python3 build_logos.py [pays ...]   (sans argument : les pays de la liste ci-dessous)."""
import concurrent.futures as cf
import json
import os
import re
import sys
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import catalog  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "..", "..", "shared", "data", "radio_logos.json")
COUNTRIES = "FR BE CH CA LU MC US GB IE DE AT ES PT IT NL SE NO DK FI PL BR MX AR CL CO JP AU NZ IN ZA MA DZ TN SN CI TR GR RO HU CZ RU UA IL".split()
UA = {"User-Agent": "Mozilla/5.0 (compatible; Podial)", "Accept-Language": "fr,en;q=0.8"}


def get(url, n=300_000, rng=None):
    h = dict(UA)
    if rng:
        h["Range"] = rng
    with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=6) as r:
        return r.headers.get("content-type", ""), r.read(n)


def alive(url):
    try:
        ct, b = get(url, 32, "bytes=0-31")
        return ct.startswith("image/") or b[:4] in (b"\x89PNG", b"GIF8", b"RIFF") or b[:3] == b"\xff\xd8\xff" or b[:4] == b"\0\0\1\0"
    except Exception:
        return False


def pick(html, base):
    icons = []
    for tag in re.findall(r"<link\b[^>]*>", html, re.I):
        def attr(n):
            m = re.search(r"\b" + n + r"\s*=\s*[\"']([^\"']+)[\"']", tag, re.I)
            return m.group(1) if m else None
        rel, href = (attr("rel") or "").lower(), attr("href")
        if "icon" not in rel or not href or href.startswith("data:") or len(href) > 500:
            continue
        m = re.search(r"(\d+)x\d+", attr("sizes") or "")
        icons.append(("apple-touch-icon" in rel, int(m.group(1)) if m else 0, href))
    pool = [i for i in icons if i[0]] or [i for i in icons if not i[2].lower().endswith(".svg")] or icons
    if not pool:
        return None
    u = urllib.parse.urljoin(base, max(pool, key=lambda i: i[1])[2])
    return u if u.startswith("http") and not u.lower().endswith(".svg") else None


def find(home):
    try:
        ct, b = get(home)
        return pick(b.decode("utf-8", "replace"), home)
    except Exception:
        return None


def work(s):
    art = catalog.fix_logo_url(s.get("favicon", ""))
    if art and alive(art):
        return None
    home = s.get("homepage", "")
    if not home.startswith("http"):
        return None
    logo = find(home)
    if logo and alive(logo):
        return catalog.station_key(s["name"]), logo
    return None


def main():
    out = json.load(open(OUT)) if os.path.exists(OUT) else {}
    for cc in (sys.argv[1:] or COUNTRIES):
        try:
            data = catalog.radio_get("stations/search?" + urllib.parse.urlencode(
                {"hidebroken": "true", "order": "clickcount", "reverse": "true", "limit": 200, "countrycode": cc}))
        except OSError as e:
            print(cc, "erreur", e)
            continue
        seen, todo = set(), []
        for s in data:
            k = catalog.station_key(s.get("name", ""))
            if k in seen or not (s.get("url_resolved") or s.get("url")):
                continue
            seen.add(k)
            todo.append(s)
        with cf.ThreadPoolExecutor(24) as ex:
            res = [r for r in ex.map(work, todo) if r]
        out.update(dict(res))
        print(cc, len(todo), "stations,", len(res), "logos trouvés", flush=True)
    with open(OUT, "w") as f:
        json.dump(dict(sorted(out.items())), f, ensure_ascii=False, separators=(",", ":"))
    print(len(out), "logos au total")


if __name__ == "__main__":
    main()
