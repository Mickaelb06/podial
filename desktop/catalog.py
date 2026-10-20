"""Podial — données : catalogue de podcasts (API iTunes Search publique), annuaire de radios
(radio-browser.info), flux RSS, réglages et bibliothèque. Aucune dépendance externe, aucun code d'interface."""
import gettext
import html
import json
import locale
import os
import random
import re
import shutil
import subprocess
import threading
import time
import unicodedata
import urllib.parse
import urllib.request
import zlib
import xml.etree.ElementTree as ET
from email.utils import parsedate_to_datetime

from i18n import _

VERSION = "0.5.8"
REPO = "Mickaelb06/podial"
UA = f"Podial/{VERSION} (+https://github.com/{REPO})"
ITUNES_NS = "{http://www.itunes.com/dtds/podcast-1.0.dtd}"
MAX_EPISODES = 200

DATA_DIR = os.path.join(os.environ.get("XDG_DATA_HOME") or os.path.expanduser("~/.local/share"), "podial")
LEGACY_DIR = os.path.join(os.path.dirname(DATA_DIR), "podcasts-linux")  # nom du prototype
LIB_FILES = {"podcast": os.path.join(DATA_DIR, "library.json"), "radio": os.path.join(DATA_DIR, "radios.json"),
             "youtube": os.path.join(DATA_DIR, "youtube.json")}
YT_CACHE = os.path.join(os.environ.get("XDG_CACHE_HOME") or os.path.expanduser("~/.cache"), "podial", "youtube")
SETTINGS_FILE = os.path.join(DATA_DIR, "settings.json")
DL_DIR = os.path.join(DATA_DIR, "downloads")
DL_INDEX = os.path.join(DATA_DIR, "downloads.json")
COVER_DIR = os.path.join(DATA_DIR, "covers")   # pochettes conservées pour l'écoute hors ligne
AUTO_SEEN = os.path.join(DATA_DIR, "auto_seen.json")
PLAYED_FILE = os.path.join(DATA_DIR, "played.json")   # épisodes écoutés
FEEDS_FILE = os.path.join(DATA_DIR, "feeds.json")     # épisodes déjà vus par flux + nombre de nouveautés

# Repli si le paquet « iso-codes » est absent : pays principaux, nom écrit dans la langue du pays.
_FALLBACK_COUNTRIES = [
    ("FR", "France"), ("BE", "Belgique / België"), ("CH", "Suisse / Schweiz"), ("LU", "Luxembourg"),
    ("CA", "Canada"), ("US", "United States"), ("GB", "United Kingdom"), ("IE", "Ireland"),
    ("AU", "Australia"), ("NZ", "New Zealand"), ("ZA", "South Africa"), ("IN", "India"),
    ("DE", "Deutschland"), ("AT", "Österreich"), ("NL", "Nederland"), ("IT", "Italia"),
    ("ES", "España"), ("PT", "Portugal"), ("BR", "Brasil"), ("MX", "México"), ("AR", "Argentina"),
    ("CL", "Chile"), ("CO", "Colombia"), ("PE", "Perú"), ("SE", "Sverige"), ("NO", "Norge"),
    ("DK", "Danmark"), ("FI", "Suomi"), ("PL", "Polska"), ("CZ", "Česko"), ("RO", "România"),
    ("GR", "Ελλάδα"), ("TR", "Türkiye"), ("MA", "Maroc"), ("DZ", "Algérie"), ("TN", "Tunisie"),
    ("SN", "Sénégal"), ("CI", "Côte d'Ivoire"), ("JP", "日本"), ("KR", "한국"),
]
_ISO_JSON = "/usr/share/iso-codes/json/iso_3166-1.json"


def _load_countries():
    """Tous les pays (norme ISO 3166-1), avec leur nom dans la langue du système si « iso-codes » le fournit."""
    try:
        with open(_ISO_JSON, encoding="utf-8") as f:
            entries = json.load(f)["3166-1"]
        try:
            tr = gettext.translation("iso_3166-1", "/usr/share/locale")
        except OSError:
            tr = gettext.NullTranslations()
        found = [(e["alpha_2"], tr.gettext(e.get("common_name") or e["name"])) for e in entries]
    except (OSError, ValueError, KeyError):
        found = list(_FALLBACK_COUNTRIES)
    for category in (locale.LC_COLLATE, locale.LC_TIME):  # tri des pays et format des dates selon la langue
        try:
            locale.setlocale(category, "")
        except locale.Error:
            pass
    return sorted(found, key=lambda c: locale.strxfrm(c[1].lower()))


COUNTRIES = _load_countries()
COUNTRY_NAMES = dict(COUNTRIES)
COUNTRY_CODES = set(COUNTRY_NAMES)


def flag(code):
    return "".join(chr(0x1F1E6 + ord(ch) - 65) for ch in code.upper())


def country_label(code):
    return f"{flag(code)}  {COUNTRY_NAMES.get(code, code)}"


def detect_country():
    """Pays déduit de la langue du système (fr_FR → FR, pt_BR → BR, en → US)."""
    candidates = [locale.getlocale()[0], os.environ.get("LC_ALL"), os.environ.get("LANG")]
    for cand in candidates:
        if cand and "_" in cand:
            code = cand.split("_", 1)[1][:2].upper()
            if code in COUNTRY_CODES:
                return code
    return "US"


# ---------- réglages et bibliothèque ----------
def _migrate_legacy():
    """Reprend la bibliothèque du prototype « podcasts-linux » s'il y en a une."""
    if not os.path.isdir(LEGACY_DIR):
        return
    os.makedirs(DATA_DIR, exist_ok=True)
    for path in LIB_FILES.values():
        old = os.path.join(LEGACY_DIR, os.path.basename(path))
        if os.path.exists(old) and not os.path.exists(path):
            shutil.copy2(old, path)


def _load_settings():
    try:
        with open(SETTINGS_FILE, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        data = {}
    if data.get("country") not in COUNTRY_CODES:
        data["country"] = detect_country()
    return data


_migrate_legacy()
settings = _load_settings()


def get_country():
    return settings["country"]


def set_country(code):
    if code in COUNTRY_CODES:
        settings["country"] = code
        os.makedirs(DATA_DIR, exist_ok=True)
        with open(SETTINGS_FILE, "w", encoding="utf-8") as f:
            json.dump(settings, f, ensure_ascii=False, indent=1)


def load_list(kind):
    try:
        with open(LIB_FILES[kind], encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return []


def save_list(kind, items):
    os.makedirs(DATA_DIR, exist_ok=True)
    with open(LIB_FILES[kind], "w", encoding="utf-8") as f:
        json.dump(items, f, ensure_ascii=False, indent=1)


def get_setting(key, default=None):
    return settings.get(key, default)


def set_setting(key, value):
    settings[key] = value
    os.makedirs(DATA_DIR, exist_ok=True)
    with open(SETTINGS_FILE, "w", encoding="utf-8") as f:
        json.dump(settings, f, ensure_ascii=False, indent=1)


def item_key(item):
    if item["kind"] == "radio":
        return item["stream"]
    return item["video"] if item["kind"] == "video" else item["feed"]


# ---------- réseau ----------
def http_get(url, timeout=20):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


PENDING_FEED = "itunes:"  # Apple n'indique plus l'adresse du flux de certains podcasts (France Inter, Radio France…) : identifiant provisoire


def is_podcast(r):
    """Un résultat iTunes qui est un podcast, avec ou sans adresse de flux."""
    return bool(r.get("feedUrl") or (r.get("collectionId") and r.get("kind", "podcast") == "podcast"))


def podcast_item(r):
    return {
        "kind": "podcast", "id": r.get("collectionId"), "name": r.get("collectionName", ""),
        "artist": r.get("artistName", ""), "feed": r.get("feedUrl") or f"{PENDING_FEED}{r['collectionId']}",
        "art": r.get("artworkUrl600") or r.get("artworkUrl100", ""),
    }


def search_podcasts(term):
    q = urllib.parse.urlencode({"term": term, "media": "podcast", "limit": 36, "country": get_country()})
    data = json.loads(http_get("https://itunes.apple.com/search?" + q))
    return [podcast_item(r) for r in data.get("results", []) if is_podcast(r)]


# Genres du classement Apple Podcasts (identifiants vérifiés ; les noms sont traduits à l'affichage)
APPLE_GENRES = [(1301, "Arts"), (1321, "Business"), (1303, "Comedy"), (1304, "Education"), (1483, "Fiction"),
                (1511, "Government"), (1487, "History"), (1512, "Health & Fitness"), (1305, "Kids & Family"),
                (1502, "Leisure"), (1310, "Music"), (1489, "News"), (1526, "Daily News"), (1527, "Politics"), (1490, "Business News"), (1528, "Tech News"), (1314, "Religion & Spirituality"),
                (1533, "Science"), (1324, "Society & Culture"), (1545, "Sports"), (1318, "Technology"),
                (1488, "True Crime"), (1309, "TV & Film")]


def top_podcasts(limit=40, genre=None):
    """Classement des podcasts du pays choisi, général ou par genre (puis États-Unis si le pays n'a pas de classement)."""
    country = get_country()
    ids = None
    for cc in (country, "US"):
        try:
            # service des classements iTunes (rapide, avec ou sans genre) ; pour le classement général, l'ancien service Apple en secours
            # (un genre est plus étroit : on en montre davantage, les journaux des radios sont loin dans le classement)
            n = limit if genre is None else max(limit, 100)
            suffix = "" if genre is None else f"/genre={int(genre)}"
            try:
                top = json.loads(http_get(f"https://itunes.apple.com/{cc.lower()}/rss/toppodcasts/limit={n}{suffix}/json"))
                entries = top["feed"].get("entry", [])
                entries = entries if isinstance(entries, list) else [entries]
                ids = [e["id"]["attributes"]["im:id"] for e in entries]
            except (OSError, ValueError, KeyError):
                if genre is not None:
                    raise
                top = json.loads(http_get(f"https://rss.marketingtools.apple.com/api/v2/{cc.lower()}/podcasts/top/{limit}/podcasts.json"))
                ids = [r["id"] for r in top["feed"]["results"]]
            break
        except (OSError, ValueError, KeyError):
            continue
    if ids is None:
        raise OSError("Podcast charts unavailable")
    data = json.loads(http_get(f"https://itunes.apple.com/lookup?entity=podcast&country={country}&id=" + ",".join(ids)))
    by_id = {str(r.get("collectionId")): r for r in data.get("results", []) if is_podcast(r)}
    return [podcast_item(by_id[i]) for i in ids if i in by_id]


# ---------- catalogue : genres, radios et éditeurs (même fichier que les applications) ----------
def _load_sources():
    try:
        with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "podcast_sources.json"), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None  # fichier absent : on garde la liste de genres intégrée


PODCAST_SOURCES = _load_sources()


def ui_lang():
    for var in ("LANGUAGE", "LC_ALL", "LC_MESSAGES", "LANG"):
        value = os.environ.get(var)
        if value:
            return value.split(":")[0].split("_")[0].split(".")[0].lower() or "en"
    return "en"


def genre_label(g):
    """Nom d'un genre Apple dans la langue de l'interface, sinon en anglais."""
    return g.get(ui_lang()) or g.get("en") or str(g["id"])


def source_genres(parent=0):
    """Genres de premier niveau (parent=0) ou sous-genres d'un genre ; liste vide sans fichier."""
    if not PODCAST_SOURCES:
        return []
    return [g for g in PODCAST_SOURCES["genres"] if g.get("parent", 0) == parent]


def source_genre(gid):
    return next((g for g in (PODCAST_SOURCES or {}).get("genres", []) if g["id"] == gid), None)


def source_groups():
    """Radios et éditeurs groupés par pays : celui de l'utilisateur d'abord, puis les autres par ordre alphabétique."""
    groups = {}
    for s in (PODCAST_SOURCES or {}).get("sources", []):
        groups.setdefault(s["country"], []).append(s)
    mine = get_country()
    return sorted(groups.items(), key=lambda kv: (kv[0] != mine, COUNTRY_NAMES.get(kv[0], kv[0]).lower()))


def source_item(s):
    """Une radio ou un éditeur sous forme de carte (sans pochette : tuile colorée avec initiale)."""
    return {"kind": "source", "name": s["name"], "artist": COUNTRY_NAMES.get(s["country"], s["country"]),
            "art": "", "artist_id": s.get("artistId", 0)}


def _source_results(artist_id, name):
    """Résultats iTunes d'une radio ou d'un éditeur : par identifiant d'éditeur (jusqu'à 200), sinon par son nom exact (jusqu'à 100)."""
    if artist_id:
        data = json.loads(http_get(f"https://itunes.apple.com/lookup?id={int(artist_id)}&entity=podcast&limit=200&country={get_country()}"))
        return [r for r in data.get("results", []) if r.get("kind") == "podcast"]
    q = urllib.parse.urlencode({"term": name, "media": "podcast", "attribute": "artistTerm", "limit": 100, "country": get_country()})
    data = json.loads(http_get("https://itunes.apple.com/search?" + q))
    return [r for r in data.get("results", []) if station_key(r.get("artistName", "")) == station_key(name)]


def _distinct_items(results):
    seen, out = set(), []
    for r in results:
        if r.get("collectionId") not in seen:
            seen.add(r.get("collectionId"))
            out.append(podcast_item(r))
    return out


def source_podcasts(artist_id, name=""):
    """Tous les podcasts d'une radio ou d'un éditeur."""
    return _distinct_items(_source_results(artist_id, name))


def local_flashes():
    """Flashs d'info et journaux locaux : les podcasts « Actus du jour » des radios locales (ICI, Alouette, K6FM…)."""
    spec = (PODCAST_SOURCES or {}).get("local") or {"sources": [], "genre": 1526}
    by_name = {s["name"]: s for s in (PODCAST_SOURCES or {}).get("sources", [])}
    results = []
    for n in spec["sources"]:
        if n in by_name:
            try:
                # « Actus du jour », ou un nom de flash / journal / infos (certaines radios rangent leurs flashs dans « Actualités »)
                results += [r for r in _source_results(by_name[n].get("artistId", 0), n)
                            if str(spec["genre"]) in [str(x) for x in r.get("genreIds", [])]
                            or re.search(r"(?i)flash|journal|journaux|\binfos?\b", r.get("collectionName", ""))]
            except (OSError, ValueError):
                continue
    return _distinct_items(results)


# annuaire de radios : on découvre les serveurs disponibles au lieu d'en figer un seul
_FALLBACK_SERVERS = ["de1.api.radio-browser.info", "de2.api.radio-browser.info", "fi1.api.radio-browser.info",
                     "at1.api.radio-browser.info"]
_servers = None
_servers_lock = threading.Lock()


def radio_servers():
    global _servers
    with _servers_lock:
        if _servers is None:
            try:
                data = json.loads(http_get("https://all.api.radio-browser.info/json/servers", timeout=8))
                _servers = sorted({d["name"] for d in data if d.get("name")}) or list(_FALLBACK_SERVERS)
            except (OSError, ValueError):
                _servers = list(_FALLBACK_SERVERS)
        return list(_servers)


_QUALITY_WORDS = re.compile(r"\b(hd|hq|hifi|hi-fi|aac\+?|mp3|ogg|opus|flac|\d{2,3}\s?k(bps|bit)?|live|stream|direct|web ?radio)\b")


def station_key(name):
    """Clé de comparaison de deux noms de radio : « FIP (HD) », « Fip  » et « FIP 128k » donnent la même clé."""
    s = re.sub(r"[\(\[].*?[\)\]]", " ", name.casefold())
    s = "".join(ch for ch in unicodedata.normalize("NFKD", s) if not unicodedata.combining(ch))
    s = _QUALITY_WORDS.sub(" ", s)
    return re.sub(r"[^a-z0-9]+", "", s) or name.casefold()


def fix_logo_url(url):
    """Wikimedia refuse les miniatures de taille quelconque (« Use thumbnail steps ») : on demande 330 px, une taille standard."""
    if url and "upload.wikimedia.org" in url and "/thumb/" in url:
        return re.sub(r"/((?:lang[a-z-]+-)?)\d+px-", r"/\g<1>330px-", url)
    return url


def radio_item(s):
    codec = s.get("codec") or ""
    bitrate = s.get("bitrate") or 0
    quality = f"{codec} {bitrate} kbit/s" if bitrate else codec
    place = s.get("state") or s.get("country") or ""
    return {
        "kind": "radio", "id": s.get("stationuuid"), "name": s.get("name", "").strip(),
        "artist": " · ".join(x for x in (place, quality) if x),
        "stream": s.get("url_resolved") or s.get("url"), "tags": s.get("tags", ""),
        "art": fix_logo_url(s.get("favicon", "")),
    }


def radio_get(path):
    """GET sur l'annuaire de radios, en essayant jusqu'à trois serveurs (un serveur en panne n'empêche pas d'écouter)."""
    servers, last_error = radio_servers(), None
    random.shuffle(servers)
    for attempt in range(4):  # quatre essais, en changeant de serveur quand il y en a plusieurs
        host = servers[attempt % len(servers)]
        try:
            return json.loads(http_get(f"https://{host}/json/{path}", timeout=15))
        except (OSError, ValueError) as exc:
            last_error = exc
            time.sleep(0.5 * (attempt + 1))
    raise OSError(last_error or "No radio server available")


# Radios absentes de l'annuaire, avec l'adresse de flux publiée par la station elle-même.
# Même liste dans shared/src/app/podial/Extras.kt et docs/app/index.html : à garder identique.
EXTRA_RADIOS = [
    {"kind": "radio", "id": "extra-rmc2", "name": "RMC 2", "artist": "Italia · MP3 128 kbit/s", "tags": "",
     "stream": "https://stream.rcs.revma.com/txdn8d324ucwv",
     "art": "https://www.rmc2.net/wp-content/themes/rmc2-child/apple-icon-180x180.png", "country": "IT"},
]


def extra_radios(params, known):
    """Radios fournies à ajouter : celles du pays affiché, et celles dont le nom correspond à la recherche (tous pays)."""
    q = station_key(params.get("name", "")) if params.get("name") else ""
    out = []
    for r in EXTRA_RADIOS:
        key = station_key(r["name"])
        if key in known or r["stream"] in {k["stream"] for k in known.values()}:
            continue
        if (q in key) if q else (r["country"] == params.get("countrycode")):
            out.append({k: v for k, v in r.items() if k != "country"})
    return out


def radio_query(**params):
    base = {"hidebroken": "true", "order": "clickcount", "reverse": "true", "limit": 48}
    base.update(params)
    data = radio_get("stations/search?" + urllib.parse.urlencode(base))
    by_key = {}
    for s in data:  # l'annuaire contient beaucoup de doublons : on garde le premier, avec le logo d'un doublon s'il n'en a pas
        key = station_key(s.get("name", ""))
        known = by_key.get(key)
        if known is not None:
            if not known["art"] and s.get("favicon"):
                known["art"] = fix_logo_url(s["favicon"])
            continue
        if not (s.get("url_resolved") or s.get("url")):
            continue
        by_key[key] = radio_item(s)
    return list(by_key.values()) + extra_radios(params, by_key)


def search_radios(term):
    return radio_query(name=term, countrycode=get_country())


def top_radios():
    return radio_query(countrycode=get_country())


# ---------- flux RSS ----------
def parse_duration(text):
    if not text:
        return ""
    text = text.strip()
    try:
        if ":" in text:
            secs = 0
            for p in (int(p) for p in text.split(":")):
                secs = secs * 60 + p
        else:
            secs = int(float(text))
    except ValueError:
        return text
    if secs < 60:
        return ""
    h, rem = divmod(secs, 3600)
    m, _s = divmod(rem, 60)
    return _("{h} h {m:02d}").format(h=h, m=m) if h else _("{m} min").format(m=m)


def clean_text(raw):
    return html.unescape(re.sub(r"<[^>]+>", " ", raw or "")).replace("\xa0", " ").strip()


_RESOLVED_FEEDS = {}


def resolve_feed(pending):
    """Adresse du flux lue dans la page web d'Apple du podcast (champ « feedUrl » de ses données), gardée pour la prochaine fois."""
    pid = pending[len(PENDING_FEED):]
    if pid in _RESOLVED_FEEDS:
        return _RESOLVED_FEEDS[pid]
    cache = os.path.join(DATA_DIR, "feeds_resolved.json")
    try:
        with open(cache, encoding="utf-8") as f:
            _RESOLVED_FEEDS.update(json.load(f))
    except (OSError, ValueError):
        pass
    if pid not in _RESOLVED_FEEDS:
        html = http_get(f"https://podcasts.apple.com/{get_country().lower()}/podcast/id{int(pid)}", timeout=30).decode("utf-8", "ignore")
        m = re.search(r'"feedUrl":"(https?:[^"]+)"', html)
        if not m:
            raise OSError(f"feed not found for {pid}")
        _RESOLVED_FEEDS[pid] = m.group(1).replace("\\/", "/").replace("\\u0026", "&")
        try:
            with open(cache, "w", encoding="utf-8") as f:
                json.dump(_RESOLVED_FEEDS, f)
        except OSError:
            pass
    return _RESOLVED_FEEDS[pid]


def filter_episodes(eps, newest_first, flt, downloaded, played):
    """Épisodes à montrer : tous, téléchargés (adresses dans downloaded) ou pas encore terminés (adresses absentes de played),
    triés par date de publication ; si une date manque on garde l'ordre du flux (récents d'abord, comme la plupart des flux)."""
    if flt == "downloaded":
        kept = [e for e in eps if e["url"] in downloaded]
    elif flt == "unfinished":
        kept = [e for e in eps if e["url"] not in played]
    else:
        kept = list(eps)
    if all(e.get("ts") is not None for e in kept):
        kept.sort(key=lambda e: -e["ts"])  # tri stable : à date égale, ordre du flux
    return kept if newest_first else kept[::-1]


def fetch_feed(feed_url, untitled="Untitled"):
    if feed_url.startswith(PENDING_FEED):
        feed_url = resolve_feed(feed_url)
    root = ET.fromstring(http_get(feed_url, timeout=30))
    channel = root.find("channel")
    desc = re.sub(r"\s+", " ", clean_text(channel.findtext("description")))
    eps = []
    for item in channel.findall("item")[:MAX_EPISODES]:
        enc = item.find("enclosure")
        if enc is None or not enc.get("url"):
            continue
        date, ts = "", None  # ts : date de publication en secondes, pour trier
        pub = item.findtext("pubDate")
        if pub:
            try:
                published = parsedate_to_datetime(pub)
                date, ts = published.strftime("%x"), published.timestamp()  # affichage dans le format de la langue du système
            except (TypeError, ValueError):
                date = pub[:16]
        tr_url, tr_type = "", ""
        for child in item:  # balise podcast:transcript (espace de noms Podcasting 2.0) : on garde le format le plus utile
            if child.tag.endswith("}transcript") and child.get("url"):
                t = child.get("type") or ""
                if not tr_url or transcript_rank(t) < transcript_rank(tr_type):
                    tr_url, tr_type = child.get("url"), t
        eps.append({
            "title": (item.findtext("title") or untitled).strip(),
            "url": enc.get("url"), "date": date, "ts": ts,
            "duration": parse_duration(item.findtext(ITUNES_NS + "duration")),
            "transcript": tr_url, "transcript_type": tr_type,
        })
    return {"description": desc, "episodes": eps}


# ---------- YouTube (optionnel : nécessite yt-dlp, installé par l'utilisateur) ----------
def ytdlp_path():
    return shutil.which("yt-dlp") or next(
        (p for p in (os.path.expanduser("~/.local/bin/yt-dlp"), "/usr/local/bin/yt-dlp") if os.access(p, os.X_OK)), None)


def youtube_enabled():
    return bool(get_setting("youtube", False)) and ytdlp_path() is not None


def _fmt_secs(secs):
    secs = int(secs or 0)
    h, rem = divmod(secs, 3600)
    return (_("{h} h {m:02d}").format(h=h, m=rem // 60) if h else _("{m} min").format(m=rem // 60)) if secs >= 60 else ""


def youtube_channel_item(channel_id, name, art=""):
    return {"kind": "youtube", "id": channel_id, "name": name, "artist": "YouTube", "art": art,
            "feed": f"https://www.youtube.com/feeds/videos.xml?channel_id={channel_id}"}


def youtube_search(term):
    """Recherche de vidéos (liste « à plat » : rapide, sans téléchargement)."""
    out = subprocess.run([ytdlp_path(), "--flat-playlist", "-J", "--no-warnings", "--no-update",
                          f"ytsearch24:{term}"], capture_output=True, text=True, timeout=60)
    if out.returncode != 0:
        raise OSError((out.stderr.strip().splitlines() or ["yt-dlp failed"])[-1])
    items = []
    for e in json.loads(out.stdout).get("entries", []):
        if not e.get("id"):
            continue
        channel = e.get("channel") or e.get("uploader") or ""
        items.append({
            "kind": "video", "id": e["id"], "name": e.get("title", ""), "channel": channel,
            "channel_id": e.get("channel_id") or "", "video": f"https://www.youtube.com/watch?v={e['id']}",
            "artist": " · ".join(x for x in (channel, _fmt_secs(e.get("duration"))) if x),
            "art": f"https://i.ytimg.com/vi/{e['id']}/mqdefault.jpg",
        })
    return items


def fetch_youtube_feed(feed_url):
    """Dernières vidéos d'une chaîne, depuis son flux Atom public (15 vidéos)."""
    ns = {"a": "http://www.w3.org/2005/Atom", "yt": "http://www.youtube.com/xml/schemas/2015",
          "m": "http://search.yahoo.com/mrss/"}
    root = ET.fromstring(http_get(feed_url, timeout=20))
    eps = []
    for e in root.findall("a:entry", ns):
        vid = e.findtext("yt:videoId", namespaces=ns)
        if not vid:
            continue
        date = e.findtext("a:published", namespaces=ns) or ""
        try:
            date = parsedate_to_datetime(date).strftime("%x") if "," in date else \
                __import__("datetime").datetime.fromisoformat(date).strftime("%x")
        except (TypeError, ValueError):
            date = date[:10]
        thumb = e.find("m:group/m:thumbnail", ns)
        eps.append({"title": (e.findtext("a:title", namespaces=ns) or "").strip(), "id": vid, "yt": True,
                    "url": f"https://www.youtube.com/watch?v={vid}", "date": date, "duration": "",
                    "art": thumb.get("url").replace("hqdefault", "mqdefault") if thumb is not None else ""})
    return {"description": "", "episodes": eps}


class Cancelled(Exception):
    pass


def youtube_audio_file(video_id, folder=None, cancel=None):
    """Télécharge l'audio d'une vidéo (dans le cache par défaut : YouTube refuse la lecture directe en flux) et renvoie le fichier."""
    if not re.fullmatch(r"[\w-]{6,20}", video_id):
        raise ValueError("invalid video id")
    keep = folder is None  # le cache est purgé ; le dossier des téléchargements ne l'est jamais
    folder = folder or YT_CACHE
    os.makedirs(folder, exist_ok=True)
    for name in os.listdir(folder):
        if name.startswith(video_id + ".") and not name.endswith((".part", ".ytdl")):
            os.utime(os.path.join(folder, name))
            return os.path.join(folder, name)
    proc = subprocess.Popen([ytdlp_path(), "-f", "bestaudio[ext=m4a]/bestaudio", "--no-playlist", "--no-warnings",
                             "--no-update", "--no-progress", "-o", os.path.join(folder, "%(id)s.%(ext)s"),
                             "--print", "after_move:filepath", f"https://www.youtube.com/watch?v={video_id}"],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    waited = 0
    while True:
        try:
            stdout, stderr = proc.communicate(timeout=0.5)
            break
        except subprocess.TimeoutExpired:
            waited += 0.5
            if (cancel and cancel.is_set()) or waited > 600:
                proc.kill()
                proc.communicate()
                for n in os.listdir(folder):
                    if n.startswith(video_id + ".") and n.endswith((".part", ".ytdl")):
                        os.remove(os.path.join(folder, n))
                if cancel and cancel.is_set():
                    raise Cancelled()
                raise OSError("yt-dlp timeout")
    out = subprocess.CompletedProcess(proc.args, proc.returncode, stdout, stderr)
    path = out.stdout.strip().splitlines()[-1] if out.stdout.strip() else ""
    if out.returncode != 0 or not os.path.exists(path):
        raise OSError((out.stderr.strip().splitlines() or ["yt-dlp failed"])[-1])
    if keep:
        files = sorted((os.path.join(YT_CACHE, n) for n in os.listdir(YT_CACHE)), key=os.path.getmtime, reverse=True)
        for old in files[15:]:  # on garde les 15 derniers fichiers
            try:
                os.remove(old)
            except OSError:
                pass
    return path


# ---------- pochettes conservées (visibles hors ligne) ----------
def _cover_file(url):
    return os.path.join(COVER_DIR, f"{zlib.crc32(url.encode()):08x}.img")


def save_cover(url):
    if not url or os.path.exists(_cover_file(url)):
        return
    data = http_get(url, timeout=15)
    os.makedirs(COVER_DIR, exist_ok=True)
    with open(_cover_file(url), "wb") as f:
        f.write(data)


def art_bytes(url):
    """Pochette : copie locale si elle a été conservée, sinon téléchargement."""
    try:
        with open(_cover_file(url), "rb") as f:
            return f.read()
    except OSError:
        return http_get(url, timeout=10)


# ---------- téléchargements (écoute hors ligne) ----------
def load_downloads():
    """{clé de l'épisode (son adresse) : {file, title, item, date, duration, size}} ; oublie les fichiers disparus."""
    try:
        with open(DL_INDEX, encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        return {}
    return {k: v for k, v in data.items() if isinstance(v, dict) and os.path.exists(v.get("file", ""))}


def _save_downloads(data):
    os.makedirs(DATA_DIR, exist_ok=True)
    tmp = DL_INDEX + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
    os.replace(tmp, DL_INDEX)


def download_episode(item, ep, progress=None, cancel=None):
    """Télécharge un épisode (podcast : fichier audio ; YouTube : audio via yt-dlp) et l'enregistre. progress(0..1)."""
    os.makedirs(DL_DIR, exist_ok=True)
    key = ep["url"]
    if ep.get("yt"):
        path = youtube_audio_file(ep["id"], DL_DIR, cancel)
    else:
        ext = os.path.splitext(urllib.parse.urlparse(key).path)[1].lower()
        if not re.fullmatch(r"\.[a-z0-9]{2,4}", ext):
            ext = ".mp3"
        name = f"{zlib.crc32(key.encode()):08x}-{re.sub(r'[^A-Za-z0-9]+', '-', ep['title'])[:40].strip('-')}{ext}"
        path, part = os.path.join(DL_DIR, name), os.path.join(DL_DIR, name + ".part")
        req = urllib.request.Request(key, headers={"User-Agent": UA})
        try:
            with urllib.request.urlopen(req, timeout=30) as r, open(part, "wb") as out:
                total = int(r.headers.get("Content-Length") or 0)
                done = 0
                while chunk := r.read(256 * 1024):
                    if cancel and cancel.is_set():
                        raise Cancelled()
                    out.write(chunk)
                    done += len(chunk)
                    if progress and total:
                        progress(min(done / total, 1.0))
            os.replace(part, path)
        finally:
            if os.path.exists(part):
                os.remove(part)
    for art in (item.get("art"), ep.get("art")):
        try:
            save_cover(art)
        except OSError:
            pass
    data = load_downloads()
    data[key] = {"file": path, "title": ep["title"], "date": ep.get("date", ""), "duration": ep.get("duration", ""),
                 "yt": bool(ep.get("yt")), "id": ep.get("id"), "item": item,
                 "transcript": ep.get("transcript", ""), "transcript_type": ep.get("transcript_type", ""), "size": os.path.getsize(path)}
    _save_downloads(data)
    return path


def delete_download(key):
    data = load_downloads()
    entry = data.pop(key, None)
    if entry:
        try:
            os.remove(entry["file"])
        except OSError:
            pass
        _save_downloads(data)


# ---------- radios : navigation par pays, région et ville ----------
RADIO_ORDERS = {"clickcount": True, "votes": True, "name": False, "lastchangetime": True}  # tri -> ordre décroissant ?
_radio_country_names = None
_geocode_cache = {}


def radio_country_name(code):
    """Nom du pays tel que l'annuaire de radios le connaît (en anglais)."""
    global _radio_country_names
    if _radio_country_names is None:
        _radio_country_names = {c["iso_3166_1"]: c["name"] for c in radio_get("countries") if c.get("iso_3166_1")}
    return _radio_country_names.get(code, "")


def radio_states(code):
    """Régions / états d'un pays, nettoyés (doublons fusionnés), avec le nombre de radios : [(nom, nombre)]."""
    name = radio_country_name(code)
    if not name:
        return []
    raw = radio_get(f"states/{urllib.parse.quote(name)}/?hidebroken=true&order=stationcount&reverse=true")
    merged = {}
    for r in raw:
        label = re.sub(r"\s*\(\s*[^)]*\)\s*$", "", r.get("name", ""))   # « Ain (France) » -> « Ain »
        label = re.sub(r"^\s*\d+\s*[-–]\s*", "", label).strip(" —-")       # « 74 - Haute-Savoie » -> « Haute-Savoie »
        if len(label) < 2 or len(label) > 40 or label.lower() in ("aucun", "none", "unknown", "n/a"):
            continue
        key = "".join(ch for ch in unicodedata.normalize("NFKD", label.casefold()) if not unicodedata.combining(ch))
        shown, count = merged.get(key, (label, 0))
        merged[key] = (shown, count + int(r.get("stationcount") or 0))
    return sorted(merged.values(), key=lambda x: (-x[1], x[0].casefold()))[:300]


def geocode_city(city, code):
    """Coordonnées d'une ville (OpenStreetMap Nominatim, usage léger : une requête par recherche)."""
    key = (city.casefold(), code)
    if key not in _geocode_cache:
        q = urllib.parse.urlencode({"q": city, "format": "json", "limit": 1, "countrycodes": code.lower()})
        try:
            hits = json.loads(http_get("https://nominatim.openstreetmap.org/search?" + q, timeout=10))
        except (OSError, ValueError):
            hits = []
        _geocode_cache[key] = (float(hits[0]["lat"]), float(hits[0]["lon"])) if hits else None
    return _geocode_cache[key]


def radio_browse(code=None, state=None, city=None, name=None, order="clickcount", limit=96):
    """Radios filtrées par pays, région, ville et nom. Une ville regroupe : région contenant la ville, nom de la
    radio contenant la ville, et radios situées à moins de 30 km (position fournie par l'annuaire)."""
    code = code or get_country()
    common = {"order": order, "reverse": "true" if RADIO_ORDERS.get(order, True) else "false", "limit": limit}
    if city:
        queries = [dict(common, countrycode=code, state=city), dict(common, countrycode=code, name=city)]
        pos = geocode_city(city, code)
        if pos:
            queries.append(dict(common, geo_lat=pos[0], geo_long=pos[1], geo_distance=30000))
        merged, seen, failures = [], set(), 0
        for q in queries:
            try:
                found = radio_query(**q)
            except OSError:  # une des trois requêtes peut échouer sans condamner les autres
                failures += 1
                continue
            for item in found:
                key = station_key(item["name"])
                if item["id"] not in seen and key not in seen:
                    seen.update((item["id"], key))
                    merged.append(item)
        if failures == len(queries):
            raise OSError("No radio server available")
        if name:
            merged = [i for i in merged if name.casefold() in i["name"].casefold()]
        return merged[:limit]
    params = dict(common, countrycode=code)
    if state:
        params["state"] = state
    if name:
        params["name"] = name
    return radio_query(**params)


def auto_download(items, per_feed=3, progress=None, cancel=None):
    """Télécharge les derniers épisodes (non encore traités) des podcasts suivis. Renvoie le nombre d'épisodes récupérés."""
    try:
        with open(AUTO_SEEN, encoding="utf-8") as f:
            seen = set(json.load(f))
    except (OSError, ValueError):
        seen = set()
    count = 0
    for item in items:
        if cancel and cancel.is_set():
            break
        try:
            save_cover(item.get("art"))
            eps = fetch_feed(item["feed"])["episodes"][:per_feed]
        except Exception:
            continue
        for ep in eps:
            if ep["url"] in seen or ep["url"] in load_downloads():
                continue
            try:
                download_episode(item, ep, None, cancel)
                count += 1
            except Cancelled:
                return count
            except Exception:
                continue
            seen.add(ep["url"])
            os.makedirs(DATA_DIR, exist_ok=True)
            with open(AUTO_SEEN, "w", encoding="utf-8") as f:
                json.dump(sorted(seen), f)
    return count


# ---------- épisodes écoutés et nouveautés ----------
def _read_json(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def _write_json(path, data):
    os.makedirs(DATA_DIR, exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    os.replace(tmp, path)


def load_played():
    return set(_read_json(PLAYED_FILE, []))


def set_played(url, played=True):
    data = load_played()
    (data.add if played else data.discard)(url)
    _write_json(PLAYED_FILE, sorted(data)[-5000:])


def feed_state():
    """{adresse du flux : {"known": [adresses d'épisodes vus], "new": nombre de nouveaux épisodes}}"""
    return _read_json(FEEDS_FILE, {})


def mark_seen(feed_url, episode_urls):
    """L'utilisateur a vu ces épisodes (ouverture de la page ou abonnement) : plus de nouveautés pour ce flux."""
    state = feed_state()
    known = set(state.get(feed_url, {}).get("known", [])) | set(episode_urls)
    state[feed_url] = {"known": sorted(known)[-600:], "new": 0}
    _write_json(FEEDS_FILE, state)


def seen_urls(feed_url):
    return set(feed_state().get(feed_url, {}).get("known", []))


def check_new_episodes(items):
    """Compare les flux des podcasts suivis aux épisodes déjà vus. Renvoie {adresse du flux : [titres des nouveaux]}.
    Un flux jamais vu est simplement mémorisé (pas de nouveautés fantômes à l'abonnement)."""
    state, played, found = feed_state(), load_played(), {}
    for item in items:
        try:
            eps = fetch_feed(item["feed"])["episodes"]
        except Exception:
            continue
        entry = state.get(item["feed"])
        if entry is None:
            state[item["feed"]] = {"known": sorted(e["url"] for e in eps)[-600:], "new": 0}
            continue
        known = set(entry["known"])
        fresh = [e["title"] for e in eps if e["url"] not in known and e["url"] not in played]
        entry["new"] = len(fresh)
        if fresh:
            found[item["feed"]] = fresh
    _write_json(FEEDS_FILE, state)
    return found


def new_counts():
    return {k: v.get("new", 0) for k, v in feed_state().items() if v.get("new")}


# ---------- mises à jour ----------
def _version_tuple(v):
    return tuple(int(x) for x in re.findall(r"\d+", v.split("-")[0])[:4]) or (0,)


def check_update():
    """Interroge GitHub (dernière « release » publiée). Renvoie (version, adresse) si une version plus récente existe,
    ("", "") si Podial est à jour ou si aucune version n'a encore été publiée."""
    try:
        releases = json.loads(http_get(f"https://api.github.com/repos/{REPO}/releases?per_page=5", timeout=15))
    except urllib.error.HTTPError as exc:
        if exc.code == 404:      # aucune release publiée pour l'instant
            return "", ""
        raise
    # « latest » ignore les pré-versions : on prend la plus récente qui n'est pas un brouillon
    data = next((r for r in releases if not r.get("draft")), {})
    tag = data.get("tag_name", "")
    if tag and _version_tuple(tag) > _version_tuple(VERSION):
        return tag.lstrip("v"), data.get("html_url", f"https://github.com/{REPO}/releases")
    return "", ""


# ---------- transcriptions et citations ----------
def transcript_rank(mime):
    """Plus petit = préféré : le JSON et les formats minutés passent avant le texte brut."""
    if "json" in mime: return 0
    if "vtt" in mime: return 1
    if "subrip" in mime or "srt" in mime: return 2
    if "html" in mime: return 4
    return 5 if not mime else 3


def _stamp_ms(text):
    secs = 0.0
    for part in text.replace(",", ".").split(":"):
        secs = secs * 60 + float(part)
    return int(secs * 1000)


_TIME_LINE = re.compile(r"((?:\d+:)?\d+:\d+(?:[.,]\d+)?)\s*-->")


def parse_transcript(body, mime=""):
    """Texte de transcription -> liste de {"start": ms ou -1, "text": phrase}. JSON, WebVTT, SRT, HTML ou texte."""
    t = body.lstrip("\ufeff \n\r\t")
    cues = []
    if t.startswith(("{", "[")) or "json" in mime:
        try:
            data = json.loads(t)
            segs = data if isinstance(data, list) else data.get("segments", [])
            for seg in segs:
                text = str(seg.get("body", "")).strip()
                if text:
                    cues.append({"start": int(float(seg.get("startTime", -0.001)) * 1000), "text": text})
            return cues
        except (ValueError, AttributeError):
            return []
    if "-->" in t:
        for block in re.split(r"\n\s*\n", t.replace("\r", "")):
            lines = block.split("\n")
            idx = next((i for i, ln in enumerate(lines) if "-->" in ln), None)
            m = _TIME_LINE.search(lines[idx]) if idx is not None else None
            if not m:
                continue
            text = " ".join(re.sub(r"<[^>]+>", "", ln).strip() for ln in lines[idx + 1:]).strip()
            if text:
                try:
                    cues.append({"start": _stamp_ms(m.group(1)), "text": text})
                except ValueError:
                    cues.append({"start": -1, "text": text})
        return cues
    if "html" in mime or t.startswith("<"):
        t = re.sub(r"(?is)<(script|style).*?</\1>", "", t)
        t = re.sub(r"(?i)</p>|<br\s*/?>|</div>|</h\d>", "\n\n", t)
        t = html.unescape(re.sub(r"<[^>]+>", "", t))
    return [{"start": -1, "text": p.strip()} for p in re.split(r"\n", t.replace("\r", "")) if p.strip()]


def load_transcript(episode_url, url, mime=""):
    """Transcription d'un épisode : lue depuis le cache local, sinon téléchargée puis gardée pour l'écoute hors ligne."""
    folder = os.path.join(DATA_DIR, "transcripts")
    os.makedirs(folder, exist_ok=True)
    path = os.path.join(folder, f"{zlib.crc32(episode_url.encode()):08x}.txt")
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            body = f.read()
    else:
        if not url:
            raise ValueError("no transcript")
        body = http_get(url, timeout=20)
        if isinstance(body, bytes):
            body = body.decode("utf-8", "replace")
        if body.strip():
            with open(path, "w", encoding="utf-8") as f:
                f.write(body)
    return parse_transcript(body, mime)


def cue_index(cues, pos_ms):
    """Index de la phrase en cours (-1 si le texte n'est pas minuté)."""
    if not cues or cues[0]["start"] < 0:
        return -1
    ans = 0
    for i, c in enumerate(cues):
        if c["start"] <= pos_ms:
            ans = i
        else:
            break
    return ans


QUOTES_FILE = os.path.join(DATA_DIR, "quotes.json")


def quote_id(episode_url, start, text):
    return f"{zlib.crc32(f'{episode_url}|{start}|{text}'.encode()):08x}"


def load_quotes():
    return _read_json(QUOTES_FILE, [])


def add_quote(q):
    data = load_quotes()
    if not any(x["id"] == q["id"] for x in data):
        _write_json(QUOTES_FILE, [q] + data)


def remove_quote(qid):
    _write_json(QUOTES_FILE, [x for x in load_quotes() if x["id"] != qid])


def set_quote_note(qid, note):
    _write_json(QUOTES_FILE, [dict(x, note=note) if x["id"] == qid else x for x in load_quotes()])


def share_text(q):
    at = f" ({fmt_clock(q['start'])})" if q.get("start", -1) >= 0 else ""
    return f"“{q.get('text') or q.get('note', '')}”\n— {q['podcast']}, {q['title']}{at}"


def fmt_clock(ms):
    s = max(0, int(ms // 1000))
    return f"{s // 3600}:{s // 60 % 60:02d}:{s % 60:02d}" if s >= 3600 else f"{s // 60}:{s % 60:02d}"
