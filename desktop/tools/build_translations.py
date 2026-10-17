#!/usr/bin/env python3
"""Compile po/*.po en locale/<langue>/LC_MESSAGES/podial.mo (sans les outils gettext).

Usage : python3 tools/build_translations.py [dossier_de_sortie ...]
Sans argument, écrit dans ./locale et ./extension/podial@podial.app/locale."""
import os
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOMAIN = "podial"


def unquote(s):
    s = s.strip()
    assert s.startswith('"') and s.endswith('"'), s
    out, i, body = [], 0, s[1:-1]
    while i < len(body):
        if body[i] == "\\" and i + 1 < len(body):
            out.append({"n": "\n", "t": "\t", '"': '"', "\\": "\\"}.get(body[i + 1], body[i + 1]))
            i += 2
        else:
            out.append(body[i])
            i += 1
    return "".join(out)


def parse_po(path):
    """Renvoie {msgid[\\0msgid_plural]: msgstr[\\0msgstr1...]}, sans les entrées vides ou « fuzzy »."""
    entries, cur, field, fuzzy = [], {}, None, False

    def flush():
        nonlocal cur, fuzzy
        if "msgid" in cur and not fuzzy:
            entries.append(cur)
        cur, fuzzy = {}, False

    with open(path, encoding="utf-8") as f:
        for raw in f:
            line = raw.strip()
            if not line:
                flush()
                field = None
            elif line.startswith("#,") and "fuzzy" in line:
                fuzzy = True
            elif line.startswith("#"):
                continue
            elif line.startswith("msgid_plural"):
                field = "msgid_plural"
                cur[field] = unquote(line[len(field):])
            elif line.startswith("msgid"):
                field = "msgid"
                cur[field] = unquote(line[5:])
            elif line.startswith("msgstr["):
                idx = int(line[7:line.index("]")])
                field = f"msgstr{idx}"
                cur[field] = unquote(line[line.index("]") + 1:])
            elif line.startswith("msgstr"):
                field = "msgstr0"
                cur[field] = unquote(line[6:])
            elif line.startswith('"') and field:
                cur[field] += unquote(line)
        flush()

    catalog = {}
    for e in entries:
        forms = [e[k] for k in sorted(k for k in e if k.startswith("msgstr"))]
        if not forms or (not any(forms) and e["msgid"]):
            continue  # non traduit
        key = e["msgid"] + ("\0" + e["msgid_plural"] if "msgid_plural" in e else "")
        catalog[key] = "\0".join(forms)
    return catalog


def write_mo(catalog, path):
    keys = sorted(catalog, key=lambda k: k.encode("utf-8"))
    ids = b"".join(k.encode("utf-8") + b"\0" for k in keys)
    strs = b"".join(catalog[k].encode("utf-8") + b"\0" for k in keys)
    n = len(keys)
    keystart = 7 * 4 + 16 * n
    valuestart = keystart + len(ids)
    koffsets, voffsets, off = [], [], 0
    for k in keys:
        length = len(k.encode("utf-8"))
        koffsets += [length, keystart + off]
        off += length + 1
    off = 0
    for k in keys:
        length = len(catalog[k].encode("utf-8"))
        voffsets += [length, valuestart + off]
        off += length + 1
    data = struct.pack("Iiiiiii", 0x950412DE, 0, n, 7 * 4, 7 * 4 + 8 * n, 0, 0)
    data += struct.pack(f"{len(koffsets)}i", *koffsets) + struct.pack(f"{len(voffsets)}i", *voffsets) + ids + strs
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, path)  # remplacement atomique : GNOME Shell garde l'ancien fichier en mémoire, on ne doit pas le réécrire en place


def main():
    outs = sys.argv[1:] or [os.path.join(ROOT, "locale"),
                            os.path.join(ROOT, "extension", "podial@podial.app", "locale")]
    po_dir = os.path.join(ROOT, "po")
    count = 0
    for name in sorted(os.listdir(po_dir)):
        if not name.endswith(".po"):
            continue
        lang = name[:-3]
        catalog = parse_po(os.path.join(po_dir, name))
        for out in outs:
            write_mo(catalog, os.path.join(out, lang, "LC_MESSAGES", DOMAIN + ".mo"))
        count += 1
        print(f"{lang}: {len(catalog) - 1} messages")
    return 0 if count else 1


if __name__ == "__main__":
    sys.exit(main())
