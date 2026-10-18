"""Podial — carte du monde (contours Natural Earth 110m, domaine public) : un clic choisit un pays."""
import json
import math
import os
import time
import zlib

import gi
gi.require_version("Gtk", "4.0")
gi.require_version("Gsk", "4.0")
from gi.repository import Gdk, GLib, Graphene, Gsk, Gtk

# Le dessin passe par Gsk.PathBuilder (GTK 4.14 ou plus récent) : pas besoin de cairo ni de python3-gi-cairo.
AVAILABLE = hasattr(Gsk, "PathBuilder") and hasattr(Gtk.Snapshot, "append_fill")

LAT_TOP, LAT_SPAN, LON_SPAN = 84.0, 142.0, 360.0
TONES = ["#5e9a62", "#6ba36a", "#58935f", "#76a86f", "#639b68", "#82ae74"]


def sun_position(now):
    """(déclinaison, longitude du point subsolaire) en degrés ; now = secondes depuis 1970."""
    n = now / 86400.0 + 2440587.5 - 2451545.0
    mean_lon = (280.460 + 0.9856474 * n) % 360
    g = math.radians((357.528 + 0.9856003 * n) % 360)
    lam = math.radians(mean_lon + 1.915 * math.sin(g) + 0.020 * math.sin(2 * g))
    eps = math.radians(23.439 - 0.0000004 * n)
    dec = math.asin(math.sin(eps) * math.sin(lam))
    ra = math.atan2(math.cos(eps) * math.sin(lam), math.cos(lam))
    gmst = (280.46061837 + 360.98564736629 * n) % 360
    lon = (math.degrees(ra) - gmst) % 360
    return math.degrees(dec), lon - 360 if lon > 180 else lon


def dark_intervals(lon, dec, sub, alt):
    """Latitudes où le Soleil est sous la hauteur `alt` sur le méridien `lon` : intervalles (bas, haut) en degrés."""
    a = math.sin(math.radians(dec))
    b = math.cos(math.radians(dec)) * math.cos(math.radians(lon - sub))
    s0 = math.sin(math.radians(alt))
    r = math.hypot(a, b)
    if r < 1e-12:
        return [(-90.0, 90.0)] if s0 > 0 else []
    if s0 >= r:
        return [(-90.0, 90.0)]
    if s0 <= -r:
        return []
    theta, base, half = math.atan2(b, a), math.asin(s0 / r), math.pi / 2
    cuts = sorted(phi for k in range(-2, 3) for c in (base - theta, math.pi - base - theta)
                  for phi in [c + 2 * math.pi * k] if -half < phi < half)
    edges = [-half] + cuts + [half]
    out = []
    for lo, hi in zip(edges, edges[1:]):
        mid = (lo + hi) / 2
        if a * math.sin(mid) + b * math.cos(mid) < s0:
            out.append((math.degrees(lo), math.degrees(hi)))
    return out


def night_bands(now, alt, step=0.5):
    """Zone où le Soleil est sous `alt` degrés : liste de (lon0, lon1, lat bas, lat haut)."""
    dec, sub = sun_position(now)
    out = []
    for i in range(int(360 / step)):
        lon = -180 + i * step
        for lo, hi in dark_intervals(lon + step / 2, dec, sub, alt):
            out.append((lon, lon + step, lo, hi))
    return out


def load_world():
    """{code pays : [anneau, ...]} ; un anneau est une liste plate [lon, lat, lon, lat, ...] en degrés."""
    try:
        with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "world.json"), encoding="utf-8") as f:
            raw = json.load(f)
    except (OSError, ValueError):
        return {}
    return {code: [[v / 10 for v in ring] for ring in rings] for code, rings in raw.items()}


def _inside(rings, lon, lat):
    """Règle pair-impair sur tous les anneaux (les trous sont gérés)."""
    inside = False
    for r in rings:
        n = len(r) // 2
        j = n - 1
        for i in range(n):
            xi, yi, xj, yj = r[2 * i], r[2 * i + 1], r[2 * j], r[2 * j + 1]
            if (yi > lat) != (yj > lat) and lon < (xj - xi) * (lat - yi) / (yj - yi) + xi:
                inside = not inside
            j = i
    return inside


class WorldMap(Gtk.Widget):
    """Carte cliquable ; molette ou boutons pour zoomer, glisser pour déplacer."""

    def __init__(self, on_pick, selected=None):
        super().__init__(hexpand=True, vexpand=True, width_request=420, height_request=260)
        self.world = load_world()
        self.paths = {}
        for code, rings in self.world.items():
            pb = Gsk.PathBuilder.new()
            for r in rings:
                pb.move_to(r[0] + 180, LAT_TOP - r[1])
                for i in range(1, len(r) // 2):
                    pb.line_to(r[2 * i] + 180, LAT_TOP - r[2 * i + 1])
                pb.close()
            self.paths[code] = pb.to_path()
        self.on_pick, self.selected = on_pick, selected
        self.night = []
        self.refresh_night()
        GLib.timeout_add_seconds(60, self._tick)
        self.zoom, self.pan = 1.0, (0.0, 0.0)
        self._drag_start = (0.0, 0.0)
        self._focused = False
        click = Gtk.GestureClick()
        click.connect("released", self._clicked)
        self.add_controller(click)
        drag = Gtk.GestureDrag()
        drag.connect("drag-begin", lambda g, x, y: (setattr(self, "_drag_start", self.pan), setattr(self, "_moved", False)))
        drag.connect("drag-update", self._dragged)
        self.add_controller(drag)
        scroll = Gtk.EventControllerScroll(flags=Gtk.EventControllerScrollFlags.VERTICAL)
        scroll.connect("scroll", self._scrolled)
        self.add_controller(scroll)

    def refresh_night(self):
        """Zone de nuit en temps réel : jour, puis crépuscules empilés pour un dégradé doux."""
        now = time.time()
        self.night = []
        for alt in (0.0, -3.0, -6.0, -9.0, -12.0, -15.0, -18.0):
            pb = Gsk.PathBuilder.new()
            for x0, x1, lo, hi in night_bands(now, alt):
                hi, lo = (400 if hi >= 89.9 else hi), (-400 if lo <= -89.9 else lo)  # recouvre aussi la mer au-delà de la carte
                pb.add_rect(Graphene.Rect().init(x0 + 180, LAT_TOP - hi, x1 - x0, hi - lo))
            self.night.append(pb.to_path())

    def _tick(self):
        self.refresh_night()
        self.queue_draw()
        return True

    # géométrie : écran = origine + pan + (point de la carte) × base × zoom
    def _base(self):
        return max(0.01, min(self.get_width() / LON_SPAN, self.get_height() / LAT_SPAN))

    def _origin(self):
        b = self._base()
        return (self.get_width() - LON_SPAN * b) / 2, (self.get_height() - LAT_SPAN * b) / 2

    def zoom_around(self, cx, cy, factor):
        z = max(1.0, min(14.0, self.zoom * factor))
        b, (ox, oy) = self._base(), self._origin()
        mx, my = (cx - ox - self.pan[0]) / (b * self.zoom), (cy - oy - self.pan[1]) / (b * self.zoom)
        self.pan = (cx - ox - mx * b * z, cy - oy - my * b * z)
        self.zoom = z
        if z == 1.0:
            self.pan = (0.0, 0.0)
        self.queue_draw()

    def zoom_center(self, factor):
        self.zoom_around(self.get_width() / 2, self.get_height() / 2, factor)

    def set_selected(self, code):
        self.selected = code
        self.queue_draw()

    def _focus_selected(self):
        """À l'ouverture : zoom sur le pays sélectionné (son plus grand morceau, sans les territoires lointains)."""
        rings = self.world.get(self.selected)
        if not rings:
            return
        def box(r):
            xs, ys = r[0::2], r[1::2]
            return min(xs), max(xs), min(ys), max(ys)
        x0, x1, y0, y1 = max((box(r) for r in rings), key=lambda b: (b[1] - b[0]) * (b[3] - b[2]))
        b = self._base()
        z = max(1.0, min(7.0, 0.4 * self.get_width() / (max(1.0, x1 - x0) * b), 0.4 * self.get_height() / (max(1.0, y1 - y0) * b)))
        mx, my = (x0 + x1) / 2 + 180, LAT_TOP - (y0 + y1) / 2
        ox, oy = self._origin()
        self.zoom = z
        self.pan = (self.get_width() / 2 - ox - mx * b * z, self.get_height() / 2 - oy - my * b * z)

    def do_snapshot(self, snapshot):
        w, h = self.get_width(), self.get_height()
        if w <= 1 or h <= 1:
            return
        if not self._focused and w > 200 and h > 100:  # la taille définitive n'est connue qu'après l'affichage de la fenêtre
            self._focused = True
            self._focus_selected()
        # la carte garde ses couleurs quel que soit le thème : mer sombre, pays dans les teintes de Podial, pays choisi en rose
        def rgba(hex_color):
            c = Gdk.RGBA()
            c.parse(hex_color)
            return c
        bg, edge, accent, white = rgba("#1b5e8e"), rgba("#2f5f3c"), rgba("#ec4899"), rgba("#ffffff")
        shade = rgba("#050a24")
        shade.alpha = 0.1
        tones = [rgba(c) for c in TONES]
        rect = Graphene.Rect().init(0, 0, w, h)
        snapshot.push_clip(rect)
        snapshot.append_color(bg, rect)
        b, (ox, oy) = self._base(), self._origin()
        snapshot.save()
        snapshot.translate(Graphene.Point().init(ox + self.pan[0], oy + self.pan[1]))
        snapshot.scale(b * self.zoom, b * self.zoom)
        stroke = Gsk.Stroke.new(0.35 / self.zoom)
        for code, path in self.paths.items():
            if code != self.selected:
                snapshot.append_fill(path, Gsk.FillRule.EVEN_ODD, tones[zlib.crc32(code.encode()) % len(tones)])
                snapshot.append_stroke(path, stroke, edge)
        for path in self.night:
            snapshot.append_fill(path, Gsk.FillRule.WINDING, shade)
        if self.selected in self.paths:
            path = self.paths[self.selected]
            snapshot.append_fill(path, Gsk.FillRule.EVEN_ODD, accent)
            snapshot.append_stroke(path, Gsk.Stroke.new(0.7 / self.zoom), white)
        snapshot.restore()
        snapshot.pop()

    def _country_at(self, x, y):
        b, (ox, oy) = self._base(), self._origin()
        mx, my = (x - ox - self.pan[0]) / (b * self.zoom), (y - oy - self.pan[1]) / (b * self.zoom)
        lon, lat = mx - 180, LAT_TOP - my
        for code, rings in self.world.items():
            if _inside(rings, lon, lat):
                return code
        return None

    def _clicked(self, gesture, n, x, y):
        code = None if getattr(self, "_moved", False) else self._country_at(x, y)
        self._moved = False
        if code:
            self.selected = code
            self.queue_draw()
            self.on_pick(code)

    def _dragged(self, _g, dx, dy):
        if abs(dx) + abs(dy) > 6:
            self._moved = True
        if self.zoom > 1.0:
            self.pan = (self._drag_start[0] + dx, self._drag_start[1] + dy)
            self.queue_draw()

    def _scrolled(self, ctrl, dx, dy):
        self.zoom_center(1 / 1.25 if dy > 0 else 1.25)
        return True
