"""Podial — barres de filtres de la page d'accueil : sélecteur de pays, genres de podcasts, filtres des radios."""
import threading

import gi

gi.require_version("Gtk", "4.0")
from gi.repository import GLib, Gtk  # noqa: E402

from catalog import APPLE_GENRES, COUNTRIES, country_label, flag, get_country  # noqa: E402
from i18n import _  # noqa: E402

RADIO_SORTS = [("clickcount", "Most popular"), ("votes", "Top rated"), ("name", "A–Z"),
               ("lastchangetime", "Recently updated")]


def _async(func, done):
    """Exécute func dans un thread ; done(résultat) est rappelé dans la boucle GTK (None si erreur)."""
    def worker():
        try:
            res = func()
        except Exception:  # réseau indisponible : la liste reste vide
            res = None
        GLib.idle_add(done, res)
    threading.Thread(target=worker, daemon=True).start()


class CountryChooser(Gtk.Box):
    """Champ de recherche + liste des pays ; le pays ne change qu'au clic sur une ligne."""

    def __init__(self, on_pick):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=8, width_request=300)
        self.append(Gtk.Label(label=_("Country"), xalign=0, css_classes=["heading"]))
        self.entry = Gtk.SearchEntry(placeholder_text=_("Search a country…"))
        self.append(self.entry)
        self.list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE, css_classes=["navigation-sidebar"])
        self.rows = []  # (ligne, code, texte recherché, coche)
        for code, name in COUNTRIES:
            line = Gtk.Box(spacing=8)
            line.append(Gtk.Label(label=country_label(code), xalign=0, hexpand=True))
            check = Gtk.Image(icon_name="object-select-symbolic")
            line.append(check)
            row = Gtk.ListBoxRow(child=line)
            self.list.append(row)
            self.rows.append((row, code, f"{name} {code}".lower(), check))
        by_row = {id(r): (c, n) for r, c, n, _chk in self.rows}
        self.list.set_filter_func(lambda row: self.entry.get_text().strip().lower() in by_row[id(row)][1])
        self.entry.connect("search-changed", lambda _e: self.list.invalidate_filter())
        self.list.connect("row-activated", lambda _l, row: on_pick(by_row[id(row)][0]))
        scroll = Gtk.ScrolledWindow(min_content_height=320, max_content_height=320,
                                    hscrollbar_policy=Gtk.PolicyType.NEVER)
        scroll.set_child(self.list)
        self.append(scroll)
        self.refresh()

    def refresh(self):
        current = get_country()
        for _row, code, _text, check in self.rows:
            check.set_opacity(1.0 if code == current else 0.0)

    def reset(self):
        self.entry.set_text("")


class GenreBar(Gtk.ScrolledWindow):
    """Pastilles de genres (Tous, Histoire, Sciences…) sur une ligne qui défile : un clic choisit le classement du genre."""

    def __init__(self, on_change):
        super().__init__(hscrollbar_policy=Gtk.PolicyType.AUTOMATIC, vscrollbar_policy=Gtk.PolicyType.NEVER,
                         propagate_natural_height=True, valign=Gtk.Align.START)
        self.genre = None  # identifiant Apple du genre, None = classement général
        self.on_change = on_change
        row = Gtk.Box(spacing=6, margin_bottom=8)
        first = None
        for gid, name in [(None, "All")] + list(APPLE_GENRES):
            btn = Gtk.ToggleButton(label=_(name), css_classes=["chip-btn"], active=gid is None)
            if first is None:
                first = btn
            else:
                btn.set_group(first)
            btn.connect("toggled", self._toggled, gid, name)
            row.append(btn)
        self.set_child(row)

    def genre_name(self):
        return next((_(n) for g, n in APPLE_GENRES if g == self.genre), None)

    def _toggled(self, btn, gid, _name):
        if btn.get_active() and gid != self.genre:
            self.genre = gid
            self.on_change()


class RadioBar(Gtk.Box):
    """Filtres des radios : pays, ville, tri."""

    def __init__(self, on_change, country_popover, on_map=None, on_surprise=None):
        super().__init__(spacing=8, valign=Gtk.Align.START)
        self.on_change = on_change
        self.order = "clickcount"
        self.state = None        # plus de filtre par région (les données se recoupent) ; conservé à None
        self.active = False
        self._applied_city = ""  # ville actuellement appliquée (évite de recharger deux fois)

        self.country_btn = Gtk.MenuButton(popover=country_popover, css_classes=["chip-btn"])
        self.append(self.country_btn)
        if on_map:
            map_btn = Gtk.Button(icon_name="find-location-symbolic", tooltip_text=_("World map"), css_classes=["flat", "circular"])
            map_btn.connect("clicked", lambda *_a: on_map())
            self.append(map_btn)
        if on_surprise:
            self.surprise_btn = Gtk.Button(icon_name="media-playlist-shuffle-symbolic", tooltip_text=_("Surprise me"), css_classes=["flat", "circular"])
            self.surprise_btn.connect("clicked", lambda *_a: on_surprise(self.surprise_btn))
            self.append(self.surprise_btn)

        # ville : région contenant la ville, nom de radio ou radios à moins de 30 km
        self.city = Gtk.SearchEntry(placeholder_text=_("City…"), width_chars=22, hexpand=True,
                                    tooltip_text=_("Includes stations within 30 km, located with OpenStreetMap"))
        self.city.connect("activate", self._city_applied)
        self.city.connect("search-changed", self._city_changed)
        self.append(self.city)

        # tri
        sort_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        self.sort_popover = Gtk.Popover(child=sort_box)
        for key, label in RADIO_SORTS:
            btn = Gtk.Button(label=_(label), css_classes=["flat"], halign=Gtk.Align.FILL)
            btn.get_child().set_xalign(0)
            btn.connect("clicked", self._sort_picked, key)
            sort_box.append(btn)
        self.sort_btn = Gtk.MenuButton(popover=self.sort_popover, css_classes=["chip-btn"])
        self.append(self.sort_btn)

        self.reset_btn = Gtk.Button(icon_name="edit-clear-all-symbolic", tooltip_text=_("Reset filters"),
                                    css_classes=["flat", "circular"])
        self.reset_btn.connect("clicked", lambda _b: self.reset())
        self.append(self.reset_btn)
        self.refresh()

    # --- état ---
    def city_text(self):
        return self._applied_city

    def is_default(self):
        return not self._applied_city and self.order == "clickcount"

    def place_name(self):
        """Libellé du lieu filtré : la ville, sinon le pays."""
        from catalog import COUNTRY_NAMES
        return self._applied_city or COUNTRY_NAMES.get(get_country(), get_country())

    def refresh(self):
        code = get_country()
        self.country_btn.set_label(f"{flag(code)} {code}")
        self.sort_btn.set_label(_(dict(RADIO_SORTS)[self.order]))

    def clear_city(self):
        self._applied_city = ""
        self.city.set_text("")

    def country_changed(self):
        self.clear_city()
        self.refresh()

    def _sort_picked(self, _btn, key):
        self.order = key
        self.sort_popover.popdown()
        self.refresh()
        self.on_change()

    def _city_applied(self, entry):
        self._applied_city = entry.get_text().strip()
        self.on_change()

    def _city_changed(self, entry):
        if not entry.get_text().strip() and self._applied_city:  # champ vidé : on retire le filtre
            self._applied_city = ""
            self.on_change()

    def reset(self):
        self.order = "clickcount"
        self.clear_city()
        self.refresh()
        self.on_change()
