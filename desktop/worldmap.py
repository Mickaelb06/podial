"""Podial — carte du monde (contours Natural Earth 110m, domaine public) : un clic choisit un pays."""
import json
import os

import gi
gi.require_version("Gtk", "4.0")
gi.require_version("Gsk", "4.0")
from gi.repository import Gdk, Graphene, Gsk, Gtk

# Le dessin passe par Gsk.PathBuilder (GTK 4.14 ou plus récent) : pas besoin de cairo ni de python3-gi-cairo.
AVAILABLE = hasattr(Gsk, "PathBuilder") and hasattr(Gtk.Snapshot, "append_fill")

LAT_TOP, LAT_SPAN, LON_SPAN = 84.0, 142.0, 360.0


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
        fg = self.get_color()
        bg, land, edge, accent = Gdk.RGBA(), Gdk.RGBA(), Gdk.RGBA(), Gdk.RGBA()
        bg.red, bg.green, bg.blue, bg.alpha = fg.red, fg.green, fg.blue, 0.05
        land.red, land.green, land.blue, land.alpha = fg.red, fg.green, fg.blue, 0.22
        edge.red, edge.green, edge.blue, edge.alpha = fg.red, fg.green, fg.blue, 0.45
        accent.parse("#8b5cf6")
        rect = Graphene.Rect().init(0, 0, w, h)
        snapshot.push_clip(rect)
        snapshot.append_color(bg, rect)
        b, (ox, oy) = self._base(), self._origin()
        snapshot.save()
        snapshot.translate(Graphene.Point().init(ox + self.pan[0], oy + self.pan[1]))
        snapshot.scale(b * self.zoom, b * self.zoom)
        stroke = Gsk.Stroke.new(0.4 / self.zoom)
        for code, path in self.paths.items():
            snapshot.append_fill(path, Gsk.FillRule.EVEN_ODD, accent if code == self.selected else land)
            snapshot.append_stroke(path, stroke, edge)
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
