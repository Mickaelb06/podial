#!/usr/bin/env python3
"""Podial — podcasts et radios en direct pour le bureau Linux (GNOME). GTK4 + libadwaita + GStreamer.

Catalogue de podcasts : API iTunes Search publique. Radios : annuaire communautaire radio-browser.info.
Aucune dépendance Python externe."""
import colorsys
import os
import sys
import threading
import time
import zlib

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
gi.require_version("Gst", "1.0")
gi.require_version("Gdk", "4.0")
from gi.repository import Adw, Gdk, GdkPixbuf, Gio, GLib, Gst, Gtk  # noqa: E402

import integration  # noqa: E402
from catalog import (  # noqa: E402
    COUNTRIES, LIB_FILES, Cancelled, art_bytes, auto_download, save_cover, MAX_EPISODES, country_label, VERSION, REPO, check_new_episodes, check_update, delete_download, download_episode, fetch_feed, load_played, mark_seen, new_counts, seen_urls, set_played, fetch_youtube_feed, flag, get_country, get_setting,
    http_get, item_key, radio_browse, radio_brand_groups, radio_by_brand, radio_by_genre, radio_genre, load_downloads, load_list, save_list, search_podcasts, set_country, set_setting, top_podcasts,
    add_quote, cue_index, fmt_clock, load_quotes, load_transcript, quote_id, remove_quote, set_quote_note, share_text,
    youtube_audio_file, youtube_channel_item, youtube_enabled, youtube_search, ytdlp_path,
    COUNTRY_NAMES, filter_episodes, local_flashes, source_groups, source_item, source_podcasts)
from worldmap import AVAILABLE as WORLD_MAP, WorldMap
from filters import CountryChooser, GenreBar, RadioBar  # noqa: E402
from i18n import _, ngettext  # noqa: E402

APP_NAME = "Podial Player"
APP_ID = integration.DESKTOP_ID
EXT_UUID = "podial@podial.app"
FADE_SECONDS = 10   # durée de la baisse progressive du volume avant l'arrêt du minuteur
SPEEDS = [1.0, 1.25, 1.5, 2.0, 0.75]

# Gains en dB des 10 bandes (29, 59, 119, 237, 474, 947 Hz, 1.9, 3.8, 7.5, 15 kHz)
EQ_PRESETS = {
    "Flat": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
    "Voice (clear speech)": [-6, -4, -2, 0, 1, 3, 4, 3, 1, 0],
    "Bass boost": [6, 5, 3, 1, 0, 0, 0, 0, 0, 0],
    "Treble boost": [0, 0, 0, 0, 0, 1, 2, 4, 5, 6],
}

CSS = """
@define-color accent_bg_color #8b5cf6;
@define-color accent_color #a78bfa;
@define-color accent_fg_color #ffffff;
.cue-row { border-radius: 10px; padding: 6px 8px; }
.cue-current { background: alpha(@accent_bg_color, 0.14); }
.cue-quoted { background: alpha(@accent_bg_color, 0.32); }
@keyframes pulse { from { opacity: 0.4; } to { opacity: 1; } }
@keyframes rise { from { opacity: 0; } to { opacity: 1; } }
.rise { animation: rise 520ms ease-out both; }
.cover { border-radius: 18px; background: alpha(currentColor, 0.08); }
.cover-big { border-radius: 26px; box-shadow: 0 16px 44px alpha(black, 0.45), 0 0 60px alpha(#8b5cf6, 0.25); }
.cover-card { box-shadow: 0 6px 20px alpha(black, 0.30); transition: box-shadow 200ms; }
.ph-0 { background: linear-gradient(135deg, #8b5cf6, #ec4899); }
.ph-1 { background: linear-gradient(135deg, #f97316, #ef4444); }
.ph-2 { background: linear-gradient(135deg, #06b6d4, #3b82f6); }
.ph-3 { background: linear-gradient(135deg, #10b981, #06b6d4); }
.ph-4 { background: linear-gradient(135deg, #eab308, #f97316); }
.ph-5 { background: linear-gradient(135deg, #ec4899, #f43f5e); }
.ph-6 { background: linear-gradient(135deg, #6366f1, #8b5cf6); }
.ph-7 { background: linear-gradient(135deg, #14b8a6, #84cc16); }
.ph-letter { color: alpha(white, 0.92); font-weight: 800; font-size: 3.2em; }
.ph-letter-sm { color: alpha(white, 0.92); font-weight: 800; font-size: 1.4em; }
.live-badge { background: #e5484d; color: white; border-radius: 99px; padding: 2px 10px;
              font-weight: 700; font-size: 0.8em; }
.live-dot { background: #e5484d; color: white; border-radius: 99px; padding: 1px 8px; margin: 8px;
            font-weight: 700; font-size: 0.7em; animation: pulse 900ms ease-in-out infinite alternate; }
.logo-dot { min-width: 22px; min-height: 22px; border-radius: 99px; background: linear-gradient(135deg, #8b5cf6, #ec4899);
            box-shadow: inset 0 0 0 7px @window_bg_color, 0 0 14px alpha(#ec4899, 0.55); }
.sidebar-title { font-weight: 800; font-size: 1.05em; }
.chip { background: alpha(@accent_bg_color, 0.22); color: @accent_color; border-radius: 99px;
        padding: 3px 12px; font-size: 0.85em; font-weight: 600; }
.player-bar { border: 1px solid alpha(currentColor, 0.12); border-radius: 26px; margin: 0 14px 14px 14px;
              background: linear-gradient(90deg, alpha(@accent_bg_color, 0.30), alpha(@card_bg_color, 0.92));
              box-shadow: 0 14px 44px alpha(black, 0.40), 0 0 50px alpha(@accent_bg_color, 0.20); }
.card-title { font-weight: 600; }
.rank-badge { background: linear-gradient(135deg, #8b5cf6, #ec4899); color: white; border-radius: 99px;
              min-width: 22px; padding: 2px 8px; margin: 8px; font-weight: 800; font-size: 0.85em; }
.chip-btn { border-radius: 99px; padding: 5px 16px; background: alpha(currentColor, 0.08); font-weight: 600;
            border: 1px solid alpha(currentColor, 0.08); transition: background 180ms, box-shadow 180ms; }
.chip-btn:hover { background: alpha(currentColor, 0.16); }
.chip-btn:checked { background: linear-gradient(135deg, #8b5cf6, #ec4899); color: white; border-color: transparent;
                    box-shadow: 0 6px 18px alpha(#8b5cf6, 0.45); }
.section-title { font-weight: 800; font-size: 1.7em; }
.home-bg { background: radial-gradient(circle at 8% 0%, alpha(#8b5cf6, 0.34), transparent 420px),
                       radial-gradient(circle at 96% 0%, alpha(#ec4899, 0.24), transparent 380px); }
flowboxchild { border-radius: 24px; padding: 8px; transition: background 180ms, box-shadow 220ms; }
flowboxchild:hover { background: alpha(@accent_bg_color, 0.14); box-shadow: 0 0 0 1px alpha(@accent_bg_color, 0.55), 0 14px 36px alpha(@accent_bg_color, 0.28); }
flowboxchild:hover .cover-card { box-shadow: 0 10px 28px alpha(black, 0.45); }
.navigation-sidebar row { border-radius: 12px; margin: 2px 6px; }
.navigation-sidebar row:selected { background: linear-gradient(90deg, alpha(@accent_bg_color, 0.55), alpha(@accent_bg_color, 0.15)); }
.ico-podcast { color: #a78bfa; }
.ico-radio { color: #fb923c; }
.ico-library { color: #facc15; }
.ico-downloads { color: #34d399; }
.new-badge { background: #e5484d; color: white; border-radius: 99px; padding: 1px 8px; margin: 8px;
             font-weight: 800; font-size: 0.75em; }
.new-chip { background: alpha(@accent_bg_color, 0.25); color: @accent_color; border-radius: 99px;
            padding: 0 8px; font-weight: 700; font-size: 0.75em; }
.ep-played { opacity: 0.55; }
button.suggested-action { background: linear-gradient(135deg, #8b5cf6, #ec4899); color: white; border-radius: 99px;
                          box-shadow: 0 6px 20px alpha(#8b5cf6, 0.45); transition: box-shadow 180ms; }
button.suggested-action:hover { box-shadow: 0 10px 28px alpha(#ec4899, 0.55); }
scale highlight { background: linear-gradient(90deg, #8b5cf6, #ec4899); }
"""


def fmt_time(ns):
    secs = max(0, int(ns // Gst.SECOND))
    h, rem = divmod(secs, 3600)
    m, s = divmod(rem, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m}:{s:02d}"


def run_async(func, on_done, on_error=None):
    """Exécute func dans un thread, rappelle on_done/on_error dans la boucle GTK."""
    def worker():
        try:
            res = func()
        except Exception as exc:  # réseau, XML invalide, etc.
            if on_error:
                GLib.idle_add(on_error, exc)
            return
        GLib.idle_add(on_done, res)
    threading.Thread(target=worker, daemon=True).start()


def vivid(r, g, b):
    """Rend la couleur moyenne d'une pochette assez vive et claire pour un fond dégradé."""
    h, sat, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    r2, g2, b2 = colorsys.hsv_to_rgb(h, min(1.0, sat * 1.25 + 0.1), max(v, 0.6))
    return int(r2 * 255), int(g2 * 255), int(b2 * 255)


def load_art(url, size, still_wanted, apply):
    """Charge une pochette en arrière-plan (recadrée au carré) puis appelle apply(texture, couleur)."""
    if not url:
        return

    def fetch():
        loader = GdkPixbuf.PixbufLoader()
        try:
            loader.write(art_bytes(url))
        finally:
            try:
                loader.close()  # obligatoire, même si le format est illisible
            except GLib.Error:
                pass
        return loader.get_pixbuf()

    def done(pix):
        if pix is None or not still_wanted():
            return
        w, h = pix.get_width(), pix.get_height()
        side = min(w, h)
        pix = pix.new_subpixbuf((w - side) // 2, (h - side) // 2, side, side)
        px = pix.scale_simple(1, 1, GdkPixbuf.InterpType.BILINEAR).get_pixels()
        pix = pix.scale_simple(size * 2, size * 2, GdkPixbuf.InterpType.BILINEAR)
        apply(Gdk.Texture.new_for_pixbuf(pix), vivid(px[0], px[1], px[2]))

    run_async(fetch, done, lambda e: None)  # logos absents ou illisibles : on garde la tuile colorée


def make_cover(item, size, big=False, card=False, still_wanted=lambda: True, on_color=None):
    """Tuile colorée avec initiale, remplacée par la pochette dès qu'elle est chargée."""
    name = item.get("name", "?")
    box = Gtk.Box(halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER, width_request=size, height_request=size)
    box.add_css_class("cover")
    box.add_css_class(f"ph-{zlib.crc32(name.encode()) % 8}")
    if big:
        box.add_css_class("cover-big")
    if card:
        box.add_css_class("cover-card")
    box.set_overflow(Gtk.Overflow.HIDDEN)
    letter = next((c for c in name if c.isalnum()), "♪").upper()
    box.append(Gtk.Label(label=letter, hexpand=True, vexpand=True,
                         css_classes=["ph-letter" if size >= 80 else "ph-letter-sm"]))

    def apply(texture, color):
        while (child := box.get_first_child()) is not None:
            box.remove(child)
        img = Gtk.Image(pixel_size=size)
        img.set_from_paintable(texture)
        box.append(img)
        if on_color:
            on_color(color)

    load_art(item.get("art"), size, still_wanted, apply)
    return box


class Window(Adw.ApplicationWindow):
    def __init__(self, app):
        super().__init__(application=app, title=APP_NAME, default_width=1180, default_height=780)
        self.section = "podcast"
        self.lists = {k: load_list(k) for k in LIB_FILES}
        self.played = load_played()         # épisodes déjà écoutés
        self.new_counts = new_counts()      # podcasts suivis : adresse du flux -> nombre de nouveaux épisodes
        self.downloads = load_downloads()   # épisodes disponibles hors ligne
        self.dl_cancel = {}
        self.dl_active = {}                 # épisodes en cours de téléchargement : adresse -> avancement (0..1)
        self.ep_dl = {}                     # boutons de téléchargement de la page ouverte : adresse -> (bouton, ligne)
        self.req = 0
        self.grid_items = []
        self.detail_item = None
        self.ep_icons = {}
        self.playing_url = None
        self.playing_item = None
        self.live = False
        self.duration = 0
        self.rate_idx = 0
        self.dragging = False
        self.paused = False
        self.art_path = None

        # icône de poste de radio fournie par Podial (le thème système n'en a pas)
        Gtk.IconTheme.get_for_display(Gdk.Display.get_default()).add_search_path(
            os.path.join(os.path.dirname(os.path.abspath(__file__)), "icons"))
        provider = Gtk.CssProvider()
        provider.load_from_string(CSS)
        Gtk.StyleContext.add_provider_for_display(Gdk.Display.get_default(), provider,
                                                  Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        self.tint_page = Gtk.CssProvider()
        self.player_tint = Gtk.CssProvider()
        for p in (self.tint_page, self.player_tint):
            Gtk.StyleContext.add_provider_for_display(Gdk.Display.get_default(), p,
                                                      Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION + 1)
        self.init_audio()
        integration.install_desktop_entry(os.path.abspath(__file__))
        self.mpris = integration.Mpris(self)  # contrôles dans le menu de l'heure + touches média
        # icône de repli dans la barre ; inutile si l'extension GNOME de Podial est active
        self.tray = None if os.environ.get("PODIAL_NO_TRAY") or integration.shell_extension_active(EXT_UUID) else integration.Tray(self)
        self.get_application().hold()         # l'appli reste active (musique) fenêtre fermée
        self.connect("close-request", self.on_close)

        outer = Adw.ToolbarView()
        split = Adw.NavigationSplitView(min_sidebar_width=200, max_sidebar_width=250, sidebar_width_fraction=0.2)
        split.set_sidebar(self.build_sidebar())
        self.nav = Adw.NavigationView()
        self.nav.add(self.build_browse_page())
        split.set_content(Adw.NavigationPage(title=_("Content"), tag="content", child=self.nav))
        outer.set_content(split)
        outer.add_bottom_bar(self.build_player())
        outer.set_bottom_bar_style(Adw.ToolbarStyle.FLAT)   # le lecteur flotte au-dessus du contenu
        self.set_content(outer)

        key = Gtk.ShortcutController()
        key.add_shortcut(Gtk.Shortcut(trigger=Gtk.ShortcutTrigger.parse_string("<Control>f"),
                                      action=Gtk.CallbackAction.new(lambda *_: self.search.grab_focus() or True)))
        self.add_controller(key)

        self.side_list.select_row(self.side_list.get_row_at_index(0))
        GLib.timeout_add(500, self.tick)
        GLib.timeout_add_seconds(5, lambda: self.run_auto_download() or False)
        GLib.timeout_add_seconds(8, lambda: self.run_check_new() or False)
        GLib.timeout_add_seconds(15, self.startup_update_check)
        GLib.timeout_add_seconds(3600, lambda: self.run_check_new() or True)
        self.refresh_library_badge()
        GLib.timeout_add_seconds(3600, lambda: self.run_auto_download() or True)

    def tint(self, provider, css_class, color, alpha, height):
        """Applique un dégradé de la couleur de la pochette sur les widgets portant css_class."""
        r, g, b = color
        if height:  # page de détail : la teinte s'estompe vers le bas
            rule = (f".{css_class} {{ background: linear-gradient(180deg, rgba({r},{g},{b},{alpha}), "
                    f"rgba({r},{g},{b},0) {height}px); }}")
        else:  # lecteur : dégradé horizontal
            rule = (f".{css_class} {{ background: linear-gradient(90deg, rgba({r},{g},{b},{alpha}), "
                    f"rgba({r},{g},{b},0.08)); }}")
        provider.load_from_string(rule)

    # ---------- audio ----------
    def init_audio(self):
        Gst.init(None)
        self.player = Gst.ElementFactory.make("playbin", "player")
        audio = Gst.parse_bin_from_description(
            "audioconvert ! equalizer-10bands name=eq ! audiodynamic name=dyn mode=compressor "
            "characteristics=soft-knee threshold=0.25 ratio=1.0 ! audioconvert", True)
        self.eq = audio.get_by_name("eq")
        self.dyn = audio.get_by_name("dyn")
        self.player.set_property("audio-filter", audio)
        bus = self.player.get_bus()
        bus.add_signal_watch()
        bus.connect("message", self.on_bus)

    # ---------- barre latérale ----------
    def build_sidebar(self):
        view = Adw.ToolbarView()
        title = Gtk.Box(spacing=9)
        title.append(Gtk.Box(css_classes=["logo-dot"], valign=Gtk.Align.CENTER))
        title.append(Gtk.Label(label=_("Listen"), css_classes=["sidebar-title"]))
        header = Adw.HeaderBar(title_widget=title)
        header.pack_end(self.build_main_menu())
        header.pack_end(self.build_settings_button())
        view.add_top_bar(header)
        self.side_list = Gtk.ListBox(css_classes=["navigation-sidebar"], selection_mode=Gtk.SelectionMode.SINGLE)
        self.sections = [("podcast", _("Podcasts"), "audio-headphones-symbolic"),
                         ("radio", _("Radios"), "podial-radio-symbolic"),
                         ("youtube", "YouTube", "podial-youtube-symbolic"),
                         ("library", _("Library"), "starred-symbolic"),
                         ("downloads", _("Downloads"), "folder-download-symbolic")]
        for _key, label, icon in self.sections:
            row = Gtk.Box(spacing=12, margin_top=8, margin_bottom=8, margin_start=6, margin_end=6)
            row.append(Gtk.Image(icon_name=icon, css_classes=[f"ico-{_key}"]))
            row.append(Gtk.Label(label=label, xalign=0, hexpand=True))
            if _key == "library":
                self.lib_badge = Gtk.Label(label="0", css_classes=["new-chip"], visible=False)
                row.append(self.lib_badge)
            self.side_list.append(row)
        self.side_list.connect("row-selected", self.on_section_selected)
        self.refresh_youtube_row()
        view.set_content(self.side_list)
        return Adw.NavigationPage(title=_("Listen"), tag="sidebar", child=view)

    def build_settings_button(self):
        """Bouton du drapeau : uniquement le choix du pays (partagé avec le filtre des radios)."""
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, margin_top=12, margin_bottom=12,
                      margin_start=12, margin_end=12, width_request=300)
        self.choosers, self.country_popovers = [], []
        chooser = CountryChooser(self.on_country_changed)
        self.choosers.append(chooser)
        box.append(chooser)
        box.append(Gtk.Label(label=_("Charts and radio stations are shown for this country"), xalign=0, wrap=True,
                             css_classes=["dim-label", "caption"]))
        popover = Gtk.Popover(child=box)
        popover.connect("closed", lambda _p: chooser.reset())
        self.country_popovers.append(popover)
        self.settings_btn = Gtk.MenuButton(popover=popover, tooltip_text=_("Country"), css_classes=["flat"])
        self.refresh_country_ui()
        return self.settings_btn

    def build_main_menu(self):
        """Menu principal (≡) : préférences et à propos."""
        for name, cb in (("quotes", self.show_quotes), ("preferences", self.show_preferences), ("about", self.show_about)):
            act = Gio.SimpleAction.new(name, None)
            act.connect("activate", lambda *_a, f=cb: f())
            self.add_action(act)
        menu = Gio.Menu()
        menu.append(_("My quotes"), "win.quotes")
        menu.append(_("Preferences"), "win.preferences")
        menu.append(_("About Podial"), "win.about")
        return Gtk.MenuButton(icon_name="open-menu-symbolic", menu_model=menu, tooltip_text=_("Main menu"),
                              css_classes=["flat"], primary=True)

    def show_preferences(self):
        dlg = Adw.PreferencesDialog(title=_("Preferences"))
        page = Adw.PreferencesPage()
        dlg.add(page)

        gen = Adw.PreferencesGroup(title=_("General"))
        start = Adw.SwitchRow(title=_("Launch at login"), active=integration.autostart_enabled(),
                              subtitle=_("Starts Podial in the background when you log in. Open it from the top bar or the Applications menu."))
        start.connect("notify::active", lambda sw, _p: integration.set_autostart(sw.get_active(), os.path.abspath(__file__)))
        gen.add(start)
        page.add(gen)

        yt = Adw.PreferencesGroup(title="YouTube")
        self.yt_switch = Adw.SwitchRow(
            title=_("YouTube audio"), active=youtube_enabled(), sensitive=ytdlp_path() is not None,
            subtitle=_("Plays the audio of YouTube videos with yt-dlp, which you install yourself. This may go against "
                       "YouTube's terms of service; use it at your own risk.") if ytdlp_path()
            else _("yt-dlp not found. Install it to use YouTube."))
        self.yt_switch.connect("notify::active", self.on_youtube_toggled)
        yt.add(self.yt_switch)
        page.add(yt)

        dl = Adw.PreferencesGroup(title=_("Downloads"))
        self.auto_switch = Adw.SwitchRow(
            title=_("Automatic downloads"), active=bool(get_setting("auto_download", False)),
            subtitle=_("Downloads the 3 latest episodes of the podcasts you follow, when Podial starts and every hour."))
        self.auto_switch.connect("notify::active", self.on_auto_toggled)
        dl.add(self.auto_switch)
        page.add(dl)

        up = Adw.PreferencesGroup(title=_("Updates"))
        self.update_switch = Adw.SwitchRow(
            title=_("Check for updates at startup"), active=bool(get_setting("update_check", False)),
            subtitle=_("Contacts GitHub (github.com) once a day to see if a new version exists. Off by default."))
        self.update_switch.connect("notify::active", lambda s, _p: set_setting("update_check", s.get_active()))
        up.add(self.update_switch)
        self.update_row = Adw.ActionRow(title=_("Check for updates"), subtitle=_("Podial {version}").format(version=VERSION),
                                        activatable=True)
        self.update_row.add_suffix(Gtk.Image(icon_name="view-refresh-symbolic"))
        self.update_row.connect("activated", lambda _r: self.on_check_update())
        self.update_dl = Gtk.Button(label=_("Download"), valign=Gtk.Align.CENTER, visible=False, css_classes=["suggested-action"])
        self.update_dl.connect("clicked", lambda _b: Gtk.UriLauncher.new(self.update_url).launch(self, None, None, None))
        self.update_row.add_suffix(self.update_dl)
        up.add(self.update_row)
        page.add(up)
        dlg.present(self)

    def set_update_status(self, text):
        row = getattr(self, "update_row", None)
        if row is not None:
            row.set_subtitle(GLib.markup_escape_text(text))

    def make_country_popover(self):
        """Second sélecteur de pays, pour la barre de filtres des radios."""
        chooser = CountryChooser(self.on_country_changed)
        self.choosers.append(chooser)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, margin_top=12, margin_bottom=12, margin_start=12,
                      margin_end=12)
        box.append(chooser)
        popover = Gtk.Popover(child=box, margin_top=6)
        popover.connect("closed", lambda _p: chooser.reset())
        self.country_popovers.append(popover)
        return popover

    def on_youtube_toggled(self, switch, _param):
        set_setting("youtube", switch.get_active())
        self.refresh_youtube_row()
        if not switch.get_active() and self.section == "youtube":
            self.side_list.select_row(self.side_list.get_row_at_index(0))

    def startup_update_check(self):
        import time
        last = float(get_setting("update_checked", 0) or 0)
        if get_setting("update_check", False) and time.time() - last > 86400:
            set_setting("update_checked", time.time())
            self.on_check_update(silent=True)
        return False

    def show_about(self):
        about = Adw.AboutDialog(
            application_name=APP_NAME, application_icon="audio-headphones", version=VERSION, developer_name="Podial contributors",
            website=f"https://github.com/{REPO}", issue_url=f"https://github.com/{REPO}/issues",
            license_type=Gtk.License.MIT_X11, copyright="© 2026 Podial contributors",
            comments=_("Podcasts and live radio in one free app. No account, no tracking."))
        about.add_link(_("Support the project"), "https://buymeacoffee.com/mickaelb06")
        about.add_legal_section(_("Data sources"), None, Gtk.License.CUSTOM,
                                _("Podcast data: public iTunes Search API and Apple RSS charts. Radio stations: radio-browser.info. "
                                  "Podial is not affiliated with Apple or YouTube."))
        about.present(self)

    def on_check_update(self, silent=False):
        """Cherche une version plus récente sur GitHub. silent : pas de message si Podial est à jour (vérification automatique)."""
        if not silent:
            self.set_update_status(_("Checking…"))

        def done(res):
            version, url = res
            if version:
                msg = _("Version {version} is available.").format(version=version)
                self.update_url = url
                if getattr(self, "update_dl", None) is not None:
                    self.update_dl.set_visible(True)
                self.set_update_status(msg)
                if silent:
                    note = Gio.Notification.new(APP_NAME)
                    note.set_body(msg)
                    self.get_application().send_notification("update", note)
            elif not silent:
                self.set_update_status(_("You're up to date (version {version}).").format(version=VERSION))

        def failed(err):
            if not silent:
                self.set_update_status(_("Couldn't check. ({error})").format(error=err))

        run_async(check_update, done, failed)

    def on_auto_toggled(self, switch, _param):
        set_setting("auto_download", switch.get_active())
        if switch.get_active():
            self.run_auto_download()

    def run_auto_download(self):
        """Récupère en arrière-plan les nouveaux épisodes des podcasts suivis (un seul passage à la fois)."""
        if getattr(self, "auto_running", False) or not get_setting("auto_download", False) or not self.lists["podcast"]:
            return
        self.auto_running = True

        def done(n):
            self.auto_running = False
            if n:
                self.downloads = load_downloads()
                if self.section == "downloads" and self.nav.get_visible_page_tag() == "browse":
                    self.show_downloads()

        run_async(lambda: auto_download(list(self.lists["podcast"])), done, lambda e: setattr(self, "auto_running", False))

    def refresh_country_ui(self):
        code = get_country()
        self.settings_btn.set_label(f"{flag(code)} {code}")  # le pays actif est toujours visible
        for chooser in self.choosers:
            chooser.refresh()
        if hasattr(self, "radio_bar"):
            self.radio_bar.refresh()

    def show_world_map(self):
        """Carte du monde : on clique un pays, son nom s'affiche, puis « Voir les radios »."""
        from catalog import COUNTRY_NAMES, COUNTRY_CODES
        dlg = Adw.Dialog(title=_("World map"), content_width=760, content_height=520)
        view = Adw.ToolbarView()
        view.add_top_bar(Adw.HeaderBar())
        dlg.set_child(view)
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, margin_start=12, margin_end=12, margin_bottom=12)
        label = Gtk.Label(xalign=0, hexpand=True, css_classes=["title-4"])
        go = Gtk.Button(label=_("Show radios"), css_classes=["suggested-action", "pill"])
        state = {"code": get_country()}

        def picked(code):
            state["code"] = code
            known = code in COUNTRY_CODES
            label.set_text(country_label(code) if known else code)
            go.set_sensitive(known)

        world = WorldMap(picked, get_country())
        picked(get_country())
        go.connect("clicked", lambda *_a: (dlg.close(), self.on_country_changed(state["code"])))
        zoom = Gtk.Box(spacing=4)
        for icon, f in (("zoom-in-symbolic", 1.4), ("zoom-out-symbolic", 1 / 1.4)):
            b = Gtk.Button(icon_name=icon, css_classes=["flat", "circular"])
            b.connect("clicked", lambda _b, f=f: world.zoom_center(f))
            zoom.append(b)
        bar = Gtk.Box(spacing=8)
        for w in (label, zoom, go):
            bar.append(w)
        frame = Gtk.Frame(child=world)
        frame.set_overflow(Gtk.Overflow.HIDDEN)
        box.append(frame)
        box.append(bar)
        view.set_content(box)
        dlg.present(self)

    def surprise_radio(self, button):
        """« Surprends-moi » : une radio au hasard dans un pays au hasard, ouverte et lancée."""
        import random
        from catalog import COUNTRY_CODES, radio_query

        def find():
            codes = list(COUNTRY_CODES)
            random.shuffle(codes)
            for code in codes[:8]:  # beaucoup de petits pays n'ont aucune radio
                try:
                    found = [r for r in radio_query(countrycode=code) if r.get("stream")]
                except OSError:
                    continue
                if found:
                    return random.choice(found)
            return None

        def done(item):
            button.set_sensitive(True)
            if item:
                self.open_item(item)

        button.set_sensitive(False)
        run_async(find, done, lambda _e: button.set_sensitive(True))

    def on_country_changed(self, code):
        for popover in self.country_popovers:
            popover.popdown()
        if code != get_country():
            set_country(code)
            self.radio_bar.country_changed()
            self.refresh_country_ui()
            self.search.set_text("")
            self.nav.pop_to_tag("browse")
            self.show_home()

    def refresh_youtube_row(self):
        """La section YouTube n'apparaît que si elle est activée dans les réglages et que yt-dlp est installé."""
        row = self.side_list.get_row_at_index(2)
        row.set_visible(youtube_enabled())

    def on_section_selected(self, _lb, row):
        if row is None:
            return
        self.section = self.sections[row.get_index()][0]
        self.nav.pop_to_tag("browse")
        self.search.set_text("")
        self.search.set_visible(self.section not in ("library", "downloads"))
        self.filter_stack.set_visible_child_name(self.section if self.section in ("podcast", "radio") else "none")
        self.radio_bar.active = self.section == "radio"
        if self.radio_bar.active:
            self.radio_bar.refresh()
        self.search.set_placeholder_text({"podcast": _("Search podcasts…"), "radio": _("Search radio stations…"),
                                          "youtube": _("Search YouTube…")}.get(self.section, ""))
        self.show_home()

    # ---------- page d'accueil (grilles) ----------
    def build_browse_page(self):
        view = Adw.ToolbarView()
        self.search = Gtk.SearchEntry(hexpand=True, placeholder_text=_("Search podcasts…"))
        self.search.connect("activate", self.on_search)
        self.search.connect("search-changed", self.on_search_changed)
        header = Adw.HeaderBar(title_widget=Adw.Clamp(maximum_size=460, child=self.search))
        view.add_top_bar(header)

        # filtres : genres de podcasts, ou pays / région / ville / tri pour les radios
        self.genre_bar = GenreBar(self.show_home)
        self.radio_bar = RadioBar(self.show_home, self.make_country_popover(), self.show_world_map if WORLD_MAP else None, self.surprise_radio)
        self.radio_genre_bar = GenreBar(self.show_home, kind="radio")
        radio_filters = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0, valign=Gtk.Align.START)
        radio_filters.append(self.radio_genre_bar)
        radio_filters.append(self.radio_bar)
        self.filter_stack = Gtk.Stack(transition_type=Gtk.StackTransitionType.CROSSFADE, vhomogeneous=False,
                                      valign=Gtk.Align.START)
        self.filter_stack.add_named(Gtk.Box(), "none")
        self.filter_stack.add_named(self.genre_bar, "podcast")
        self.filter_stack.add_named(radio_filters, "radio")
        holder = Gtk.Box(margin_top=10, margin_bottom=6, margin_start=18, margin_end=18)
        holder.append(Adw.Clamp(maximum_size=1244, hexpand=True, child=self.filter_stack))
        view.add_top_bar(holder)

        self.stack = Gtk.Stack(transition_type=Gtk.StackTransitionType.CROSSFADE)
        self.stack.add_named(Adw.Spinner(halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER,
                                         width_request=48, height_request=48), "loading")
        self.status_page = Adw.StatusPage(icon_name="dialog-information-symbolic")
        self.stack.add_named(self.status_page, "status")
        self.content_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, margin_top=18,
                                   margin_bottom=24, margin_start=18, margin_end=18)
        sc = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, css_classes=["home-bg"])
        sc.set_child(Adw.Clamp(maximum_size=1280, child=self.content_box))
        self.stack.add_named(sc, "content")
        view.set_content(self.stack)
        return Adw.NavigationPage(title=_("Home"), tag="browse", child=view)

    def show_loading(self):
        self.stack.set_visible_child_name("loading")

    def show_status(self, icon, title, text=""):
        self.status_page.set_icon_name(icon)
        self.status_page.set_title(title)
        self.status_page.set_description(GLib.markup_escape_text(text))  # le texte peut contenir des chevrons
        self.stack.set_visible_child_name("status")

    def show_sections(self, blocks):
        """blocks : liste de (titre, items) ou (titre, items, numéroté). Reconstruit les grilles de pochettes."""
        while (child := self.content_box.get_first_child()) is not None:
            self.content_box.remove(child)
        self.grid_items = []
        for title, items, *opts in blocks:
            ranked = bool(opts and opts[0])
            self.content_box.append(Gtk.Label(label=title, xalign=0, css_classes=["section-title"], margin_top=8, margin_bottom=10))
            flow = Gtk.FlowBox(valign=Gtk.Align.START, homogeneous=True, min_children_per_line=2,
                               max_children_per_line=10, column_spacing=6, row_spacing=6,
                               selection_mode=Gtk.SelectionMode.SINGLE, activate_on_single_click=True)
            token = object()
            self.grid_items.append(token)
            for rank, it in enumerate(items, 1):
                flow.append(self.make_card(it, token, rank if ranked else None))
            flow.connect("child-activated", lambda fb, child, its=items: (fb.unselect_all(), self.open_item(its[child.get_index()])))
            self.content_box.append(flow)
        self.stack.set_visible_child_name("content")

    def make_card(self, item, token, rank=None):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6, width_request=160, css_classes=["rise"])
        cover = make_cover(item, 150, card=True, still_wanted=lambda: token in self.grid_items)
        new = self.new_counts.get(item.get("feed")) if item["kind"] == "podcast" else 0
        if item["kind"] == "radio" or rank or new:
            overlay = Gtk.Overlay(child=cover, halign=Gtk.Align.CENTER)
            if item["kind"] == "radio":
                overlay.add_overlay(Gtk.Label(label=_("● LIVE"), halign=Gtk.Align.END, valign=Gtk.Align.START,
                                              css_classes=["live-dot"]))
            if new:
                overlay.add_overlay(Gtk.Label(label=ngettext("{n} new", "{n} new", new).format(n=new), halign=Gtk.Align.END,
                                              valign=Gtk.Align.START, css_classes=["new-badge"]))
            if rank:
                overlay.add_overlay(Gtk.Label(label=str(rank), halign=Gtk.Align.START, valign=Gtk.Align.START,
                                              css_classes=["rank-badge"]))
            cover = overlay
        box.append(cover)
        box.append(Gtk.Label(label=item["name"], xalign=0, ellipsize=3, lines=2, wrap=True, max_width_chars=16,
                             css_classes=["card-title"]))
        sub = item["artist"]
        box.append(Gtk.Label(label=sub, xalign=0, ellipsize=3, max_width_chars=18, css_classes=["dim-label", "caption"]))
        return box

    def show_home(self):
        self.req += 1
        rid, section = self.req, self.section
        if section == "library":
            self.show_library()
            return
        if section == "downloads":
            self.show_downloads()
            return
        if section == "youtube":
            self.show_youtube_home()
            return
        self.show_loading()
        plain_title = source_open = None  # liste simple, sans « Top 10 » : radio ouverte ou flashs locaux
        if section == "podcast":
            gb = self.genre_bar
            genre, genre_name = gb.genre, gb.genre_name()
            load = lambda: top_podcasts(40, genre)  # noqa: E731
            top_title = _("Top 10 · {name}").format(name=genre_name) if genre else _("Top 10")
            more_title = _("More in {genre}").format(genre=genre_name) if genre else _("More popular podcasts")
            if gb.panel == "radios" and gb.source is None:
                # liste fixe des radios et éditeurs, pays de l'utilisateur d'abord : rien à charger
                blocks = [(f"{flag(cc)} {COUNTRY_NAMES.get(cc, cc)}", [source_item(s) for s in srcs]) for cc, srcs in source_groups()]
                self.show_sections(blocks) if blocks else self.show_status("system-search-symbolic", _("No results"))
                return
            if gb.source is not None:
                artist_id, source_open, plain_title = gb.source["artist_id"], True, gb.source["name"]
                load = lambda: source_podcasts(artist_id, plain_title)  # noqa: E731
            elif gb.panel == "local":
                load, plain_title = local_flashes, _("Local news")
        else:
            rb, rg = self.radio_bar, self.radio_genre_bar
            filters = (rb.state, rb.city_text(), rb.order)
            load = lambda: radio_browse(get_country(), filters[0], filters[1] or None, None, filters[2])  # noqa: E731
            top_title = _("Top 10 · {name}").format(name=rb.place_name())
            more_title = _("More stations")
            if rg.panel == "webradios" and rg.source is None:
                # liste fixe des marques de tous les pays, celui de l'utilisateur d'abord : rien à charger
                blocks = [(f"{flag(cc)} {COUNTRY_NAMES.get(cc, cc)}", items) for cc, items in radio_brand_groups()]
                self.show_sections(blocks) if blocks else self.show_status("system-search-symbolic", _("No results"))
                return
            if rg.source is not None:  # une marque ouverte : toutes ses webradios
                brand = rg.source
                source_open, plain_title = True, brand["name"]
                load = lambda: radio_by_brand(brand)  # noqa: E731
            elif rg.genre:  # genre ou sous-genre : les radios portant ses étiquettes
                genre = radio_genre(rg.genre)
                plain_title = rg.genre_name()
                load = lambda: radio_by_genre(genre)  # noqa: E731

        def done(items):
            if rid != self.req:
                return
            if not items:
                self.show_status("system-search-symbolic", _("No results"))
                return
            if plain_title:
                self.show_sections([(plain_title, items)])
                if source_open:
                    back_bar = self.radio_genre_bar if section == "radio" else self.genre_bar
                    back_box = Gtk.Box(spacing=8)
                    back_box.append(Gtk.Image(icon_name="go-previous-symbolic"))
                    back_box.append(Gtk.Label(label=_("Webradios") if section == "radio" else _("Radios")))
                    back = Gtk.Button(child=back_box, css_classes=["flat"], halign=Gtk.Align.START)
                    back.connect("clicked", lambda *_a: back_bar.close_source())
                    self.content_box.prepend(back)
                return
            if section == "radio" and not self.radio_bar.is_default():
                title = self.radio_bar.place_name()
                self.show_sections([(title, items)])  # filtre actif : liste simple, dans l'ordre de tri choisi
            else:
                blocks = [(top_title, items[:10], True)]
                if items[10:]:
                    blocks.append((more_title, items[10:]))
                self.show_sections(blocks)

        self.fetch(load, done, rid)

    def show_youtube_home(self):
        if self.lists["youtube"]:
            self.show_sections([(_("Followed YouTube channels"), self.lists["youtube"])])
        else:
            self.show_status("podial-youtube-symbolic", _("Search YouTube for shows and podcasts"),
                             _("Type a show or a topic in the search bar above."))

    def show_library(self):
        blocks = []
        if self.lists["podcast"]:
            blocks.append((_("Followed podcasts"), self.lists["podcast"]))
        if self.lists["radio"]:
            blocks.append((_("Favorite radio stations"), self.lists["radio"]))
        if self.lists["youtube"]:
            blocks.append((_("Followed YouTube channels"), self.lists["youtube"]))
        if blocks:
            self.show_sections(blocks)
        else:
            self.show_status("starred-symbolic", _("Your library is empty"),
                             _("Open a podcast or station and press “Subscribe” or “Add to favorites”."))

    def show_downloads(self):
        while (child := self.content_box.get_first_child()) is not None:
            self.content_box.remove(child)
        entries = sorted(self.downloads.items(), key=lambda kv: os.path.getmtime(kv[1]["file"]), reverse=True)
        if not entries:
            self.show_status("folder-download-symbolic", _("No downloaded episodes"),
                             _("Open a podcast and press the download button next to an episode to listen offline."))
            return
        total = sum(v.get("size", 0) for _k, v in entries)
        self.content_box.append(Gtk.Label(label=_("Downloads"), xalign=0, css_classes=["section-title"], margin_top=8))
        self.content_box.append(Gtk.Label(
            label=ngettext("{n} episode · {size} MB", "{n} episodes · {size} MB", len(entries)).format(
                n=len(entries), size=round(total / 1048576)), xalign=0, css_classes=["dim-label"], margin_bottom=10))
        lb = Gtk.ListBox(css_classes=["boxed-list"], selection_mode=Gtk.SelectionMode.NONE)
        for key, v in entries:
            sub = " · ".join(x for x in (v["item"]["name"], v.get("date"), _("{size} MB").format(size=round(v.get("size", 0) / 1048576))) if x)
            row = Adw.ActionRow(title=GLib.markup_escape_text(v["title"]), title_lines=2,
                                subtitle=GLib.markup_escape_text(sub), activatable=True)
            row.add_prefix(Gtk.Image(icon_name="media-playback-start-symbolic"))
            trash = Gtk.Button(icon_name="user-trash-symbolic", valign=Gtk.Align.CENTER, css_classes=["flat"],
                               tooltip_text=_("Remove download"))
            trash.connect("clicked", lambda _b, k=key: self.remove_download(k))
            row.add_suffix(trash)
            row.key = key
            lb.append(row)
        lb.connect("row-activated", lambda _l, row: self.play_downloaded(row.key))
        self.content_box.append(lb)
        self.stack.set_visible_child_name("content")

    def play_downloaded(self, key):
        v = self.downloads.get(key)
        if v:
            self.playing_ep = {"url": key, "title": v["title"], "transcript": v.get("transcript", ""), "transcript_type": v.get("transcript_type", "")}
            self.play("file://" + v["file"], v["title"], v["item"]["name"], False, v["item"], key=key)

    # ---------- téléchargements ----------
    def dl_refresh(self, key):
        """Met à jour le bouton de téléchargement de l'épisode `key` s'il est affiché."""
        if key not in self.ep_dl:
            return
        btn, row = self.ep_dl[key]
        ep_sub = row.ep_sub
        if key in self.dl_active:
            pct = int(self.dl_active[key] * 100)
            box = Gtk.Box(spacing=4)
            box.append(Gtk.Label(label=f"{pct} %" if pct else "…", css_classes=["caption"]))
            box.append(Gtk.Image(icon_name="process-stop-symbolic"))
            btn.set_child(box)
            btn.set_sensitive(True)
            btn.set_tooltip_text(_("Cancel download"))
        elif key in self.downloads:
            btn.set_child(Gtk.Image(icon_name="user-trash-symbolic"))
            btn.set_sensitive(True)
            btn.set_tooltip_text(_("Remove download"))
        else:
            btn.set_child(Gtk.Image(icon_name="folder-download-symbolic"))
            btn.set_sensitive(True)
            btn.set_tooltip_text(_("Download for offline listening"))
        mark = "✓ " + _("Downloaded") if key in self.downloads else ""
        row.set_subtitle(GLib.markup_escape_text(" · ".join(x for x in (mark, ep_sub) if x)))

    def on_dl_clicked(self, item, ep):
        key = ep["url"]
        if key in self.downloads:
            self.remove_download(key)
            return
        if key in self.dl_active:
            self.dl_cancel[key].set()
            return
        self.dl_cancel[key] = cancel = threading.Event()
        self.dl_active[key] = 0.0
        self.dl_refresh(key)

        def progress(frac):
            if int(frac * 100) != int(self.dl_active.get(key, 0) * 100):
                GLib.idle_add(self.on_dl_progress, key, frac)

        def done(_path):
            self.dl_active.pop(key, None)
            self.dl_cancel.pop(key, None)
            self.downloads = load_downloads()
            self.dl_refresh(key)
            if self.section == "downloads":
                self.show_downloads()

        def failed(err):
            self.dl_active.pop(key, None)
            self.dl_cancel.pop(key, None)
            self.dl_refresh(key)
            if isinstance(err, Cancelled):
                return
            self.p_sub.set_text(_("Download failed. ({error})").format(error=err))
            self.revealer.set_reveal_child(True)

        run_async(lambda: download_episode(dict(item), ep, progress, cancel), done, failed)

    def on_dl_progress(self, key, frac):
        if key in self.dl_active:
            self.dl_active[key] = frac
            self.dl_refresh(key)

    def remove_download(self, key):
        if self.playing_key_is(key):
            self.stop_playback()
        delete_download(key)
        self.downloads = load_downloads()
        self.dl_refresh(key)
        if self.section == "downloads" and self.nav.get_visible_page_tag() == "browse":
            self.show_downloads()

    def playing_key_is(self, key):
        return getattr(self, "playing_key", None) == key

    def fetch(self, func, done, rid):
        run_async(func, done, lambda e: rid == self.req and self.show_status(
            "network-error-symbolic", _("Connection failed"),
            _("Check your internet connection. ({error})").format(error=e)))

    def on_search_changed(self, entry):
        if not entry.get_text().strip() and self.section not in ("library", "downloads"):
            self.show_home()

    def on_search(self, entry):
        term = entry.get_text().strip()
        if not term:
            return
        self.nav.pop_to_tag("browse")
        self.req += 1
        rid, section = self.req, self.section
        self.show_loading()

        def done(items):
            if rid != self.req:
                return
            if not items:
                self.show_status("system-search-symbolic", _("No results"),
                                 _("Nothing found for “{term}”.").format(term=term))
            else:
                self.show_sections([(_("Results for “{term}”").format(term=term), items)])

        rb = self.radio_bar
        searches = {"podcast": search_podcasts, "youtube": youtube_search,
                    "radio": lambda t: radio_browse(get_country(), rb.state, rb.city_text() or None, t, rb.order)}
        self.fetch(lambda: searches[section](term), done, rid)

    # ---------- page de détail ----------
    def open_item(self, item):
        if item.get("kind") == "source":  # radio ou éditeur de la liste « Radios » : on affiche ses podcasts
            self.genre_bar.open_source(item)
            return
        if item.get("kind") == "brand":  # marque de la liste « Webradios » : on affiche ses webradios
            self.radio_genre_bar.open_source(item)
            return
        self.detail_item = item
        radio = item["kind"] == "radio"
        video = item["kind"] == "video"
        view = Adw.ToolbarView()
        view.add_top_bar(Adw.HeaderBar())
        body = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=18, margin_top=24, margin_bottom=24,
                       margin_start=24, margin_end=24)

        hero = Gtk.Box(spacing=24)
        hero.append(make_cover(item, 200, big=True, still_wanted=lambda: self.detail_item is item,
                               on_color=lambda c: self.tint(self.tint_page, "tint-page", c, 0.55, 420)))
        info = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8, valign=Gtk.Align.CENTER, hexpand=True)
        if radio:
            info.append(Gtk.Label(label=_("LIVE"), xalign=0, halign=Gtk.Align.START, css_classes=["live-badge"]))
        info.append(Gtk.Label(label=item["name"], xalign=0, wrap=True, css_classes=["title-1"]))
        info.append(Gtk.Label(label=item["artist"], xalign=0, wrap=True, css_classes=["dim-label"]))
        self.desc_lbl = Gtk.Label(label="", xalign=0, wrap=True, lines=4, ellipsize=3, selectable=False)
        if not radio:
            info.append(self.desc_lbl)
        tags = [t.strip() for t in item.get("tags", "").split(",") if t.strip()][:5] if radio else []
        if tags:
            chips = Gtk.Box(spacing=6)
            for t in tags:
                chips.append(Gtk.Label(label=t, css_classes=["chip"]))
            info.append(chips)
        buttons = Gtk.Box(spacing=10, margin_top=6)
        self.main_btn = Gtk.Button(css_classes=["suggested-action", "pill"])
        self.main_btn.set_child(Adw.ButtonContent(
            icon_name="media-playback-start-symbolic",
            label=_("Listen live") if radio else (_("Play") if video else _("Play the latest episode"))))
        self.main_btn.set_sensitive(radio or video)
        self.sub_btn = Gtk.Button(css_classes=["pill"])
        if video:  # une vidéo n'a pas d'abonnement : on propose sa chaîne à la place
            self.sub_btn.set_child(Adw.ButtonContent(icon_name="podial-youtube-symbolic", label=_("Channel")))
            self.sub_btn.set_sensitive(bool(item.get("channel_id")))
            self.sub_btn.connect("clicked", lambda *_: self.open_item(
                youtube_channel_item(item["channel_id"], item["channel"], item["art"])))
        else:
            self.sub_btn.connect("clicked", self.on_subscribe)
        buttons.append(self.main_btn)
        buttons.append(self.sub_btn)
        info.append(buttons)
        hero.append(info)
        body.append(hero)
        if not video:
            self.refresh_sub_button()

        self.ep_icons = {}
        self.ep_dl = {}
        self.ep_rows = {}
        self.ep_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        if radio:
            self.main_btn.connect("clicked", lambda *_: self.play(item["stream"], item["name"], item["artist"], True, item))
        elif video:
            self.main_btn.connect("clicked", lambda *_: self.play_youtube(item["id"], item["name"], item["channel"], item))
        else:
            self.ep_status = Adw.Spinner(halign=Gtk.Align.START, width_request=32, height_request=32)
            self.ep_box.append(self.ep_status)
        body.append(self.ep_box)

        sc = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, css_classes=["tint-page"])
        self.tint(self.tint_page, "tint-page", (139, 92, 246), 0.35, 420)  # violet par défaut, remplacé par la pochette
        sc.set_child(Adw.Clamp(maximum_size=980, child=body))
        view.set_content(sc)
        self.page_count = getattr(self, "page_count", 0) + 1  # tag unique : l'ancienne page peut encore être en transition
        page = Adw.NavigationPage(title=item["name"], tag=f"detail-{self.page_count}", child=view)
        self.nav.push(page)

        if radio:
            self.play(item["stream"], item["name"], item["artist"], True, item)
        elif video:
            pass  # une vidéo n'a rien à charger : le bouton « Lecture » suffit
        else:
            loader = (lambda: fetch_youtube_feed(item["feed"])) if item["kind"] == "youtube" \
                else (lambda: fetch_feed(item["feed"], _("Untitled")))
            run_async(loader, lambda feed: self.fill_episodes(item, feed),
                      lambda e: self.detail_item is item and self.episodes_offline(item, e))

    def episodes_offline(self, item, err):
        """Flux injoignable : on propose au moins les épisodes déjà téléchargés."""
        mine = [dict(v, url=k, art=item.get("art")) for k, v in self.downloads.items()
                if item_key(v["item"]) == item_key(item)]
        if mine:
            self.fill_episodes(item, {"description": _("Offline: showing the downloaded episodes."), "episodes": mine})
        else:
            self.episodes_failed(err)

    def episodes_failed(self, err):
        self.ep_box.remove(self.ep_status)
        self.ep_box.append(Adw.StatusPage(icon_name="network-error-symbolic", title=_("Unreadable feed"),
                                          description=GLib.markup_escape_text(_("Couldn't load the episodes. ({error})").format(error=err))))

    def fill_episodes(self, item, feed):
        if self.detail_item is not item:
            return  # l'utilisateur a quitté cette page entre-temps
        self.ep_box.remove(self.ep_status)
        self.desc_lbl.set_text(feed["description"])
        eps = feed["episodes"]
        if not eps:
            self.ep_box.append(Adw.StatusPage(icon_name="dialog-information-symbolic", title=_("No episodes")))
            return
        self.ep_box.append(Gtk.Label(label=(ngettext("Latest {n} episode", "Latest {n} episodes", len(eps)) if len(eps) >= MAX_EPISODES
                                    else ngettext("{n} episode", "{n} episodes", len(eps))).format(n=len(eps)), xalign=0, css_classes=["title-3"]))
        lb = Gtk.ListBox(css_classes=["boxed-list"], selection_mode=Gtk.SelectionMode.NONE)
        before = seen_urls(item["feed"]) if self.in_library(item) else None  # None : podcast non suivi, rien n'est « nouveau »
        fresh = {e["url"] for e in eps} - before if before else set()
        if self.in_library(item):
            self.mark_seen_async(item["feed"], [e["url"] for e in eps])
        self.ep_all_urls = [e["url"] for e in eps]  # tous les épisodes du flux, même si le filtre en cache
        state = {"newest": True, "filter": "all"}  # tri et filtre de la liste

        def chips(entries, current, on_pick):
            row, first = Gtk.Box(spacing=6, margin_bottom=6), None
            for label, value in entries:
                btn = Gtk.ToggleButton(label=label, css_classes=["chip-btn"], active=value == current)
                if first is None:
                    first = btn
                else:
                    btn.set_group(first)
                btn.connect("toggled", lambda b, v=value: b.get_active() and on_pick(v))
                row.append(btn)
            return row

        def fill_rows():
            while (old := lb.get_first_child()) is not None:
                lb.remove(old)
            self.ep_icons, self.ep_dl, self.ep_rows = {}, {}, {}
            shown = filter_episodes(eps, state["newest"], state["filter"], set(self.downloads), self.played)
            empty.set_visible(not shown)
            for ep in shown:
                lb.append(self.episode_row(item, ep, fresh))
            self.update_play_icons()

        def set_sort(newest):
            state["newest"] = newest
            fill_rows()

        def set_filter(flt):
            state["filter"] = flt
            fill_rows()

        self.ep_box.append(chips([(_("Newest first"), True), (_("Oldest first"), False)], True, set_sort))
        self.ep_box.append(chips([(_("All episodes"), "all"), (_("Downloaded episodes"), "downloaded"), (_("Unfinished"), "unfinished")], "all", set_filter))
        empty = Gtk.Label(label=_("No episode matches this filter."), css_classes=["dim-label"], margin_top=12, margin_bottom=12, visible=False)
        self.ep_box.append(empty)
        lb.connect("row-activated", lambda _l, row: self.play_episode(item, row.ep_data))
        self.ep_box.append(lb)
        fill_rows()
        self.main_btn.set_sensitive(True)
        self.main_btn.connect("clicked", lambda *_: self.play_episode(item, eps[0]))

    def episode_row(self, item, ep, fresh):
        """Ligne d'un épisode : titre, date, durée, coche « écouté » et téléchargement."""
        row = Adw.ActionRow(title=GLib.markup_escape_text(ep["title"]), title_lines=2,
                            subtitle=" · ".join(x for x in (ep["date"], ep["duration"]) if x), activatable=True)
        if ep["url"] in fresh and ep["url"] not in self.played:
            row.add_prefix(Gtk.Label(label=_("NEW"), css_classes=["new-chip"], valign=Gtk.Align.CENTER))
        row.ep_url = ep["url"]
        row.ep_data = ep
        icon = Gtk.Image(icon_name="media-playback-start-symbolic")
        row.add_prefix(icon)
        self.ep_icons[ep["url"]] = icon
        row.ep_sub = " · ".join(x for x in (ep["date"], ep["duration"]) if x)
        dl = Gtk.Button(valign=Gtk.Align.CENTER, css_classes=["flat"])
        dl.connect("clicked", lambda _b, e=ep: self.on_dl_clicked(item, e))
        tick_btn = Gtk.Button(valign=Gtk.Align.CENTER, css_classes=["flat"])
        tick_btn.connect("clicked", lambda _b, e=ep: self.toggle_played(e["url"]))
        row.add_suffix(tick_btn)
        row.add_suffix(dl)
        self.ep_dl[ep["url"]] = (dl, row)
        self.ep_rows[ep["url"]] = (row, tick_btn)
        self.dl_refresh(ep["url"])
        self.refresh_played(ep["url"])
        return row

    # ---------- épisodes écoutés / nouveautés ----------
    def refresh_played(self, url):
        if url not in self.ep_rows:
            return
        row, btn = self.ep_rows[url]
        done = url in self.played
        btn.set_child(Gtk.Image(icon_name="object-select-symbolic" if done else "radio-symbolic"))
        btn.set_tooltip_text(_("Mark as unplayed") if done else _("Mark as played"))
        (row.add_css_class if done else row.remove_css_class)("ep-played")

    def toggle_played(self, url):
        self.set_played(url, url not in self.played)

    def set_played(self, url, flag):
        (self.played.add if flag else self.played.discard)(url)
        set_played(url, flag)
        self.refresh_played(url)

    def mark_seen_async(self, feed, urls):
        def done(_r):
            self.new_counts = new_counts()
            self.refresh_library_badge()
        run_async(lambda: mark_seen(feed, urls), done, lambda e: None)

    def refresh_library_badge(self):
        total = sum(self.new_counts.get(i["feed"], 0) for i in self.lists["podcast"])
        self.lib_badge.set_label(str(total))
        self.lib_badge.set_visible(total > 0)
        if self.section == "library" and self.nav.get_visible_page_tag() == "browse":
            self.show_library()

    def run_check_new(self):
        """Cherche les nouveaux épisodes des podcasts suivis (au démarrage puis toutes les heures)."""
        if getattr(self, "check_running", False) or not self.lists["podcast"]:
            return
        self.check_running = True

        def done(found):
            self.check_running = False
            before = sum(self.new_counts.values())
            self.new_counts = new_counts()
            self.refresh_library_badge()
            if sum(self.new_counts.values()) > before and found:
                names = {i["feed"]: i["name"] for i in self.lists["podcast"]}
                total = sum(len(v) for v in found.values())
                note = Gio.Notification.new(APP_NAME)
                note.set_body(ngettext("{n} new episode: {names}", "{n} new episodes: {names}", total).format(
                    n=total, names=", ".join(names.get(f, "") for f in list(found)[:3])))
                self.get_application().send_notification("new-episodes", note)

        run_async(lambda: check_new_episodes(list(self.lists["podcast"])), done,
                  lambda e: setattr(self, "check_running", False))

    def play_episode(self, item, ep, start_ms=0):
        self.pending_seek_ns = start_ms * Gst.MSECOND if start_ms > 0 else 0
        if ep["url"] in self.downloads:
            self.play_downloaded(ep["url"])
            self.playing_ep = ep
        elif ep.get("yt"):
            self.playing_ep = ep
            self.play_youtube(ep["id"], ep["title"], item["name"], dict(item, art=ep.get("art") or item.get("art")))
        else:
            self.playing_ep = ep
            self.play(ep["url"], ep["title"], item["name"], False, item)

    def play_youtube(self, video_id, title, subtitle, item):
        """YouTube refuse la lecture directe en flux : l'audio est d'abord téléchargé dans le cache puis lu."""
        self.yt_request = getattr(self, "yt_request", 0) + 1
        req = self.yt_request
        key = f"https://www.youtube.com/watch?v={video_id}"
        self.player.set_state(Gst.State.NULL)
        self.playing_url, self.playing_key, self.playing_item, self.live, self.paused = None, key, item, False, False
        self.p_title.set_text(title)
        self.p_sub.set_text(_("Preparing audio…"))
        self.play_btn.set_icon_name("media-playback-pause-symbolic")
        self.revealer.set_reveal_child(True)
        self.update_play_icons()

        def done(path):
            if req == self.yt_request:
                self.play("file://" + path, title, subtitle, False, item, key=key)

        def failed(err):
            if req == self.yt_request:
                self.play_btn.set_icon_name("media-playback-start-symbolic")
                self.p_sub.set_text(_("Couldn't get the audio. ({error})").format(error=err))

        run_async(lambda: youtube_audio_file(video_id), done, failed)

    def update_play_icons(self):
        for url, icon in self.ep_icons.items():
            icon.set_from_icon_name("audio-volume-high-symbolic" if url == getattr(self, "playing_key", None)
                                    else "media-playback-start-symbolic")

    # ---------- abonnements / favoris ----------
    def in_library(self, item):
        return any(item_key(x) == item_key(item) for x in self.lists[item["kind"]])

    def refresh_sub_button(self):
        item = self.detail_item
        radio, fav = item["kind"] == "radio", self.in_library(item)
        label = (_("Remove from favorites") if fav else _("Add to favorites")) if radio else \
                (_("Subscribed") if fav else _("Subscribe"))
        icon = "starred-symbolic" if fav else "non-starred-symbolic"
        self.sub_btn.set_child(Adw.ButtonContent(icon_name=icon, label=label))

    def on_subscribe(self, _btn):
        item = self.detail_item
        kind = item["kind"]
        if self.in_library(item):
            self.lists[kind] = [x for x in self.lists[kind] if item_key(x) != item_key(item)]
        else:
            self.lists[kind].append(item)
            if kind == "podcast":
                run_async(lambda: save_cover(item.get("art")), lambda _r: None, lambda e: None)
                if getattr(self, "ep_all_urls", None):  # on s'abonne depuis la page : ce qu'elle affiche est déjà vu
                    self.mark_seen_async(item["feed"], list(self.ep_all_urls))
        save_list(kind, self.lists[kind])
        self.refresh_sub_button()

    # ---------- lecteur ----------
    def build_player(self):
        self.revealer = Gtk.Revealer(transition_type=Gtk.RevealerTransitionType.SLIDE_UP, reveal_child=False)
        bar = Gtk.Box(spacing=16, margin_top=10, margin_bottom=10, margin_start=16, margin_end=16)
        bar.add_css_class("player-bar")

        left = Gtk.Box(spacing=12, width_request=280)
        self.p_cover_box = Gtk.Box()
        left.append(self.p_cover_box)
        txt = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, valign=Gtk.Align.CENTER, hexpand=True)
        self.p_title = Gtk.Label(label="", xalign=0, ellipsize=3, max_width_chars=26, css_classes=["card-title"])
        self.p_sub = Gtk.Label(label="", xalign=0, ellipsize=3, max_width_chars=28, css_classes=["dim-label", "caption"])
        txt.append(self.p_title)
        txt.append(self.p_sub)
        left.append(txt)
        bar.append(left)

        mid = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, hexpand=True, valign=Gtk.Align.CENTER)
        ctrl = Gtk.Box(spacing=8, halign=Gtk.Align.CENTER)
        self.back_btn = Gtk.Button(icon_name="media-seek-backward-symbolic", tooltip_text=_("Rewind 15 s"), css_classes=["flat", "circular"])
        self.back_btn.connect("clicked", lambda *_: self.skip(-15))
        self.play_btn = Gtk.Button(icon_name="media-playback-start-symbolic", css_classes=["suggested-action", "circular"],
                                   width_request=44, height_request=44)
        self.play_btn.connect("clicked", self.on_play_pause)
        self.fwd_btn = Gtk.Button(icon_name="media-seek-forward-symbolic", tooltip_text=_("Forward 30 s"), css_classes=["flat", "circular"])
        self.fwd_btn.connect("clicked", lambda *_: self.skip(30))
        for w in (self.back_btn, self.play_btn, self.fwd_btn):
            ctrl.append(w)
        mid.append(ctrl)
        prog = Gtk.Box(spacing=10)
        self.pos_lbl = Gtk.Label(label="0:00", css_classes=["numeric", "caption"], width_chars=6, xalign=1)
        self.scale = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 0, 1, 0.001)
        self.scale.set_hexpand(True)
        self.scale.set_draw_value(False)
        self.scale.connect("change-value", self.on_seek)
        self.dur_lbl = Gtk.Label(label="0:00", css_classes=["numeric", "caption"], width_chars=9, xalign=0)
        for w in (self.pos_lbl, self.scale, self.dur_lbl):
            prog.append(w)
        mid.append(prog)
        bar.append(mid)

        right = Gtk.Box(spacing=6, width_request=300, halign=Gtk.Align.END, valign=Gtk.Align.CENTER)
        self.speed_btn = Gtk.Button(label="1×", tooltip_text=_("Playback speed"), css_classes=["flat"])
        self.speed_btn.connect("clicked", self.on_speed)
        self.vol = Gtk.VolumeButton(value=1.0, css_classes=["flat"])
        self.vol.connect("value-changed", lambda b, v: self.player.set_property("volume", v))
        self.quote_btn = Gtk.Button(icon_name="bookmark-new-symbolic", tooltip_text=_("Quote"), css_classes=["flat"], visible=False)
        self.quote_btn.connect("clicked", lambda *_a: self.save_quote("", self.position_ms()))
        self.transcript_btn = Gtk.Button(icon_name="view-list-symbolic", tooltip_text=_("Transcript"), css_classes=["flat"], visible=False)
        self.transcript_btn.connect("clicked", lambda *_a: self.show_transcript())
        right.append(self.quote_btn)
        right.append(self.transcript_btn)
        right.append(self.build_sleep_menu())
        right.append(self.speed_btn)
        right.append(self.vol)
        right.append(self.build_sound_menu())
        bar.append(right)

        self.revealer.set_child(bar)
        return self.revealer

    # ---------- minuteur de sommeil ----------
    def build_sleep_menu(self):
        self.sleep_deadline = None   # instant (monotonic) où la lecture doit s'arrêter
        self.sleep_eop = False       # arrêt à la fin de l'épisode
        self.sleep_fading = False
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2, margin_top=8, margin_bottom=8,
                      margin_start=8, margin_end=8, width_request=230)
        box.append(Gtk.Label(label=_("Sleep timer"), xalign=0, css_classes=["heading"], margin_start=6, margin_bottom=6))
        for label, mins in ((_("15 minutes"), 15), (_("30 minutes"), 30), (_("45 minutes"), 45),
                            (_("1 hour"), 60), (_("2 hours"), 120)):
            b = Gtk.Button(label=label, css_classes=["flat"])
            b.get_child().set_halign(Gtk.Align.START)
            b.connect("clicked", lambda _b, m=mins: self.set_sleep(minutes=m))
            box.append(b)
        self.eop_btn = Gtk.Button(label=_("End of episode"), css_classes=["flat"])
        self.eop_btn.get_child().set_halign(Gtk.Align.START)
        self.eop_btn.connect("clicked", lambda _b: self.set_sleep(eop=True))
        box.append(self.eop_btn)
        custom = Gtk.Box(spacing=6, margin_top=6, margin_start=6)
        self.sleep_spin = Gtk.SpinButton.new_with_range(1, 600, 1)
        self.sleep_spin.set_value(20)
        custom.append(self.sleep_spin)
        custom.append(Gtk.Label(label=_("min"), hexpand=True, xalign=0))
        go = Gtk.Button(label=_("Start"), css_classes=["suggested-action"])
        go.connect("clicked", lambda _b: self.set_sleep(minutes=self.sleep_spin.get_value_as_int()))
        custom.append(go)
        box.append(custom)
        self.sleep_cancel = Gtk.Button(label=_("Cancel timer"), css_classes=["flat", "destructive-action"],
                                       margin_top=6, visible=False)
        self.sleep_cancel.connect("clicked", lambda _b: self.set_sleep())
        box.append(self.sleep_cancel)
        self.sleep_pop = Gtk.Popover(child=box)
        self.sleep_btn = Gtk.MenuButton(popover=self.sleep_pop, direction=Gtk.ArrowType.UP, css_classes=["flat"],
                                        tooltip_text=_("Sleep timer"))
        self.sleep_btn.set_child(Adw.ButtonContent(icon_name="alarm-symbolic"))
        return self.sleep_btn

    def set_sleep(self, minutes=None, eop=False):
        """Programme l'arrêt (dans N minutes ou à la fin de l'épisode) ; sans argument, annule le minuteur."""
        self.sleep_pop.popdown()
        self.stop_fade()
        self.sleep_deadline = time.monotonic() + minutes * 60 if minutes else None
        self.sleep_eop = bool(eop)
        self.refresh_sleep()

    def refresh_sleep(self):
        active = self.sleep_deadline is not None or self.sleep_eop
        if self.sleep_deadline is not None:
            left = max(0, int(self.sleep_deadline - time.monotonic()))
            text = f"{left // 3600}:{left // 60 % 60:02d}:{left % 60:02d}" if left >= 3600 else f"{left // 60}:{left % 60:02d}"
        else:
            text = _("End") if self.sleep_eop else ""
        self.sleep_btn.set_child(Adw.ButtonContent(icon_name="alarm-symbolic", label=text))
        if active:
            self.sleep_btn.add_css_class("accent")
        else:
            self.sleep_btn.remove_css_class("accent")
        self.sleep_cancel.set_visible(active)
        self.eop_btn.set_sensitive(not self.live)

    def sleep_tick(self):
        """Appelé toutes les 500 ms : affiche le temps restant et lance la baisse progressive du volume."""
        if self.sleep_deadline is None:
            return
        self.refresh_sleep()
        if time.monotonic() >= self.sleep_deadline - FADE_SECONDS and not self.sleep_fading and not self.paused:
            self.sleep_fading = True
            GLib.timeout_add(100, self.fade_step)
        elif time.monotonic() >= self.sleep_deadline and self.paused:
            self.set_sleep()  # déjà en pause : le minuteur n'a plus d'objet

    def fade_step(self):
        if not self.sleep_fading or self.sleep_deadline is None:
            return False
        if self.paused:  # l'utilisateur a mis en pause pendant la baisse de volume
            self.stop_fade()
            return False
        left = self.sleep_deadline - time.monotonic()
        self.player.set_property("volume", self.vol.get_value() * max(0.0, min(1.0, left / FADE_SECONDS)))
        if left <= 0:
            self.stop_fade()
            self.sleep_deadline = None
            self.mp_pause()
            self.refresh_sleep()
            return False
        return True

    def stop_fade(self):
        if self.sleep_fading:
            self.sleep_fading = False
            self.player.set_property("volume", self.vol.get_value())

    def build_sound_menu(self):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10, margin_top=12, margin_bottom=12,
                      margin_start=12, margin_end=12, width_request=280)
        box.append(Gtk.Label(label=_("Equalizer"), xalign=0, css_classes=["heading"]))
        dd = Gtk.DropDown.new_from_strings([_(name) for name in EQ_PRESETS])
        dd.connect("notify::selected", self.on_eq_changed)
        box.append(dd)
        row = Gtk.Box(spacing=12, margin_top=6)
        lbl = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, hexpand=True)
        lbl.append(Gtk.Label(label=_("Smooth volume"), xalign=0))
        lbl.append(Gtk.Label(label=_("Reduces the gap between loud and quiet parts"), xalign=0, wrap=True,
                             css_classes=["dim-label", "caption"]))
        row.append(lbl)
        sw = Gtk.Switch(valign=Gtk.Align.CENTER)
        sw.connect("notify::active", lambda s, _p: self.dyn.set_property("ratio", 0.4 if s.get_active() else 1.0))
        row.append(sw)
        box.append(row)
        pop = Gtk.Popover(child=box)
        return Gtk.MenuButton(label=_("Sound"), popover=pop, direction=Gtk.ArrowType.UP, css_classes=["flat"],
                              tooltip_text=_("Equalizer and volume"))

    def on_eq_changed(self, dd, _p):
        for i, g in enumerate(list(EQ_PRESETS.values())[dd.get_selected()]):
            self.eq.set_property(f"band{i}", float(g))

    # ---------- lecture ----------
    def play(self, url, title, subtitle, live, item, key=None):
        self.player.set_state(Gst.State.NULL)
        self.player.set_property("uri", url)
        self.player.set_state(Gst.State.PLAYING)
        self.playing_url, self.playing_item, self.live = url, item, live
        if live:
            self.playing_ep = None
        self.quote_btn.set_visible(not live)
        self.transcript_btn.set_visible(not live)
        self.playing_key = key or url  # identifie l'épisode dans la liste (l'adresse lue peut être un fichier local)
        self.paused, self.art_path, self.live_title = False, None, None
        self.duration, self.rate_idx = 0, 0
        self.speed_btn.set_label("1×")
        self.speed_btn.set_visible(not live)
        self.live_sub = subtitle
        if live and self.sleep_eop:
            self.set_sleep()  # une radio en direct n'a pas de fin d'épisode
        self.refresh_sleep()
        self.p_title.set_text(title)
        self.p_sub.set_text(_("Live · {text}").format(text=subtitle) if live else subtitle)
        while (child := self.p_cover_box.get_first_child()) is not None:
            self.p_cover_box.remove(child)
        self.p_cover_box.append(make_cover(item, 52, still_wanted=lambda: self.playing_item is item,
                                           on_color=lambda c: self.tint(self.player_tint, "player-bar", c, 0.45, None)))
        self.play_btn.set_icon_name("media-playback-pause-symbolic")
        for w in (self.scale, self.back_btn, self.fwd_btn):
            w.set_sensitive(not live)
        self.scale.set_value(0)
        self.pos_lbl.set_text("0:00")
        self.dur_lbl.set_text(_("LIVE") if live else "0:00")
        self.revealer.set_reveal_child(True)
        self.update_play_icons()
        self.sync_desktop()
        run_async(lambda: integration.cache_art(item.get("art")),
                  lambda path: self.playing_item is item and (setattr(self, "art_path", path), self.sync_desktop()),
                  lambda e: None)

    def on_play_pause(self, _btn):
        if not self.playing_url:
            return
        _ok, state, _pending = self.player.get_state(0)
        playing = state == Gst.State.PLAYING
        self.player.set_state(Gst.State.PAUSED if playing else Gst.State.PLAYING)
        self.paused = playing
        self.play_btn.set_icon_name("media-playback-start-symbolic" if playing else "media-playback-pause-symbolic")
        self.sync_desktop()

    def on_speed(self, btn):
        self.rate_idx = (self.rate_idx + 1) % len(SPEEDS)
        rate = SPEEDS[self.rate_idx]
        btn.set_label(f"{rate:g}×")
        self.sync_desktop()
        ok, pos = self.player.query_position(Gst.Format.TIME)
        if ok:
            self.player.seek(rate, Gst.Format.TIME, Gst.SeekFlags.FLUSH | Gst.SeekFlags.ACCURATE,
                             Gst.SeekType.SET, pos, Gst.SeekType.NONE, -1)

    def skip(self, seconds):
        ok, pos = self.player.query_position(Gst.Format.TIME)
        if ok:
            self.seek_to(pos + seconds * Gst.SECOND)

    def seek_to(self, ns):
        ns = max(0, ns)
        if self.duration:
            ns = min(ns, self.duration)
        self.player.seek(SPEEDS[self.rate_idx], Gst.Format.TIME, Gst.SeekFlags.FLUSH | Gst.SeekFlags.KEY_UNIT,
                         Gst.SeekType.SET, ns, Gst.SeekType.NONE, -1)

    def on_seek(self, _scale, _scroll, value):
        if self.duration and not self.live:
            self.seek_to(int(max(0.0, min(1.0, value)) * self.duration))
        return False

    def position_ms(self):
        ok, pos = self.player.query_position(Gst.Format.TIME)
        return int(pos // Gst.MSECOND) if ok else 0

    # ---------- citations et transcriptions ----------
    def save_quote(self, text, start_ms):
        ep = getattr(self, "playing_ep", None)
        if not ep or self.live:
            return
        item = self.playing_item or {}
        add_quote({"id": quote_id(ep["url"], start_ms, text), "text": text, "start": start_ms, "url": ep["url"], "title": ep["title"],
                   "podcast": item.get("name", ""), "feed": item.get("feed", ""), "art": item.get("art", ""), "note": ""})
        if not text:
            self.quote_btn.set_icon_name("emblem-ok-symbolic")
            self.quote_btn.set_tooltip_text(_("Quote saved at {time}").format(time=fmt_clock(start_ms)))
            GLib.timeout_add(1800, lambda: (self.quote_btn.set_icon_name("bookmark-new-symbolic"), self.quote_btn.set_tooltip_text(_("Quote")), False)[-1])

    @staticmethod
    def is_rtl(text):
        import unicodedata
        for ch in text:
            d = unicodedata.bidirectional(ch)
            if d in ("R", "AL"):
                return True
            if d == "L":
                return False
        return False

    def show_transcript(self):
        ep = getattr(self, "playing_ep", None)
        if not ep or self.live:
            return
        dlg = Adw.Dialog(title=_("Transcript"), content_width=640, content_height=680)
        view = Adw.ToolbarView()
        view.add_top_bar(Adw.HeaderBar())
        dlg.set_child(view)
        stack = Gtk.Stack()
        view.set_content(stack)
        spinner = Gtk.Spinner(spinning=True, halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER, width_request=32, height_request=32)
        stack.add_named(spinner, "loading")
        stack.add_named(Adw.StatusPage(icon_name="view-list-symbolic", title=_("Transcript"),
                                       description=_("This episode has no published transcript. Many shows do not provide one.")), "none")
        state = {"cues": [], "rows": [], "cur": -2, "follow": True}
        url = ep["url"]

        def build(cues):
            if not cues:
                stack.set_visible_child_name("none")
                return
            state["cues"] = cues
            box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, margin_start=12, margin_end=12, margin_bottom=12)
            box.append(Gtk.Label(label=_("Click a line to listen from there. Use the quote button to highlight it."), xalign=0,
                                 wrap=True, css_classes=["dim-label", "caption"], margin_top=6, margin_bottom=6))
            lb = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
            for i, c in enumerate(cues):
                row = Gtk.Box(spacing=8, css_classes=["cue-row"])
                if c["start"] >= 0:
                    row.append(Gtk.Label(label=fmt_clock(c["start"]), css_classes=["numeric", "caption", "dim-label"], valign=Gtk.Align.START, width_chars=6, xalign=1))
                lbl = Gtk.Label(label=c["text"], wrap=True, xalign=0, hexpand=True, selectable=True)
                if self.is_rtl(c["text"]):
                    lbl.set_direction(Gtk.TextDirection.RTL)
                row.append(lbl)
                qb = Gtk.Button(icon_name="bookmark-new-symbolic", css_classes=["flat", "circular"], valign=Gtk.Align.START, tooltip_text=_("Quote"))
                qb.connect("clicked", lambda _b, c=c: toggle_quote(c))
                row.append(qb)
                if c["start"] >= 0:
                    g = Gtk.GestureClick()
                    g.connect("released", lambda _g, n, x, y, c=c: self.seek_to(c["start"] * Gst.MSECOND))
                    row.add_controller(g)
                lb.append(row)
                state["rows"].append(row)
            box.append(lb)
            sw = Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER, child=box)
            state["sw"], state["lb"] = sw, lb
            stack.add_named(sw, "list")
            stack.set_visible_child_name("list")
            refresh()

        def toggle_quote(c):
            qid = quote_id(url, c["start"], c["text"])
            if any(q["id"] == qid for q in load_quotes()):
                remove_quote(qid)
            else:
                self.save_quote(c["text"], c["start"])
            refresh(force=True)

        def refresh(force=False):
            if not state["rows"]:
                return True
            cur = cue_index(state["cues"], self.position_ms())
            quoted = {q["id"] for q in load_quotes()} if force or cur != state["cur"] else None
            if cur == state["cur"] and not force:
                return True
            state["cur"] = cur
            for i, row in enumerate(state["rows"]):
                row.remove_css_class("cue-current")
                if i == cur:
                    row.add_css_class("cue-current")
                c = state["cues"][i]
                if quote_id(url, c["start"], c["text"]) in quoted:
                    row.add_css_class("cue-quoted")
                else:
                    row.remove_css_class("cue-quoted")
            if cur >= 0 and state["follow"]:
                pt = state["rows"][max(0, cur - 1)].translate_coordinates(state["lb"], 0, 0)
                if pt:
                    state["sw"].get_vadjustment().set_value(pt[1])
            return True

        timer = GLib.timeout_add(500, refresh)
        dlg.connect("closed", lambda *_a: GLib.source_remove(timer))

        stack.set_visible_child_name("loading")
        run_async(lambda: load_transcript(url, ep.get("transcript", ""), ep.get("transcript_type", "")), build,
                  lambda e: stack.set_visible_child_name("none"))
        dlg.present(self)

    def show_quotes(self):
        dlg = Adw.Dialog(title=_("My quotes"), content_width=640, content_height=680)
        view = Adw.ToolbarView()
        view.add_top_bar(Adw.HeaderBar())
        dlg.set_child(view)
        holder = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        view.set_content(holder)

        def rebuild():
            while (child := holder.get_first_child()) is not None:
                holder.remove(child)
            quotes = load_quotes()
            if not quotes:
                holder.append(Adw.StatusPage(icon_name="bookmark-new-symbolic", title=_("My quotes"), vexpand=True,
                                             description=_("No quotes yet. While listening, press the quote button, or open the transcript and highlight a sentence.")))
                return
            lb = Gtk.ListBox(css_classes=["boxed-list"], selection_mode=Gtk.SelectionMode.NONE, margin_start=12, margin_end=12, margin_top=8, margin_bottom=12)
            for q in quotes:
                sub = " · ".join(x for x in (q["podcast"], q["title"], fmt_clock(q["start"]) if q.get("start", -1) >= 0 else "") if x)
                text = f"“{q['text']}”" if q.get("text") else (q.get("note") or fmt_clock(q["start"]))
                row = Adw.ActionRow(title=GLib.markup_escape_text(text), title_lines=0, subtitle=GLib.markup_escape_text(sub), subtitle_lines=2)
                if self.is_rtl(q.get("text", "")):
                    row.set_direction(Gtk.TextDirection.RTL)
                if q.get("text") and q.get("note"):
                    row.set_subtitle(GLib.markup_escape_text(q["note"] + "\n" + sub))
                for icon, tip, cb in (("media-playback-start-symbolic", _("Play from here"), lambda _b, q=q: (dlg.close(), self.play_quote(q))),
                                      ("document-edit-symbolic", _("Add a note"), lambda _b, q=q: edit_note(q)),
                                      ("edit-copy-symbolic", _("Copy to share"), lambda _b, q=q: self.get_clipboard().set(share_text(q))),
                                      ("user-trash-symbolic", _("Delete"), lambda _b, q=q: (remove_quote(q["id"]), rebuild()))):
                    b = Gtk.Button(icon_name=icon, tooltip_text=tip, valign=Gtk.Align.CENTER, css_classes=["flat"])
                    b.connect("clicked", cb)
                    row.add_suffix(b)
                lb.append(row)
            holder.append(Gtk.ScrolledWindow(vexpand=True, hscrollbar_policy=Gtk.PolicyType.NEVER, child=lb))

        def edit_note(q):
            ad = Adw.AlertDialog(heading=_("Add a note"))
            entry = Gtk.Entry(text=q.get("note", ""), activates_default=True)
            ad.set_extra_child(entry)
            ad.add_response("cancel", _("Cancel"))
            ad.add_response("save", _("Save"))
            ad.set_response_appearance("save", Adw.ResponseAppearance.SUGGESTED)
            ad.set_default_response("save")
            ad.connect("response", lambda _d, r: (set_quote_note(q["id"], entry.get_text().strip()), rebuild()) if r == "save" else None)
            ad.present(dlg)

        rebuild()
        dlg.present(self)

    def play_quote(self, q):
        item = {"kind": "podcast", "name": q["podcast"], "artist": "", "art": q.get("art", ""), "feed": q.get("feed", "")}
        self.play_episode(item, {"title": q["title"], "url": q["url"], "date": "", "duration": ""}, max(0, q.get("start", 0)))

    def tick(self):
        self.sleep_tick()
        if self.playing_url:
            ok_p, pos = self.player.query_position(Gst.Format.TIME)
            if ok_p:
                self.pos_lbl.set_text(fmt_time(pos))
            if not self.live:
                ok_d, dur = self.player.query_duration(Gst.Format.TIME)
                if ok_d and dur > 0:
                    if dur != self.duration:
                        self.duration = dur
                        self.sync_desktop()  # la durée n'est connue qu'après le début de lecture
                        if getattr(self, "pending_seek_ns", 0):
                            self.seek_to(self.pending_seek_ns)
                            self.pending_seek_ns = 0
                    self.dur_lbl.set_text(fmt_time(dur))
                if ok_p and self.duration:
                    self.scale.set_value(min(1.0, pos / self.duration))
                    key = getattr(self, "playing_key", None)
                    if key and pos / self.duration > 0.92 and key not in self.played:
                        self.set_played(key, True)  # écouté à plus de 92 % : on le considère terminé
        return True

    def stop_playback(self, message=None):
        self.player.set_state(Gst.State.NULL)
        self.play_btn.set_icon_name("media-playback-start-symbolic")
        if message:
            self.p_sub.set_text(message)
        self.playing_url = None
        self.paused = False
        self.update_play_icons()
        self.sync_desktop()

    def on_bus(self, _bus, msg):
        if msg.type == Gst.MessageType.EOS:
            if not self.live and getattr(self, "playing_key", None):
                self.set_played(self.playing_key, True)
            if self.sleep_eop:
                self.set_sleep()  # fin de l'épisode atteinte : le minuteur a fait son travail
            self.stop_playback()
        elif msg.type == Gst.MessageType.ERROR:
            err, _dbg = msg.parse_error()
            self.stop_playback(_("Playback error: {message}").format(message=err.message))
        elif msg.type == Gst.MessageType.TAG and self.live:
            ok, title = msg.parse_tag().get_string("title")  # titre en cours envoyé par la radio
            if ok and title:
                self.p_sub.set_text(_("Live · {text}").format(text=title))
                self.live_title = title
                self.sync_desktop()

    # ---------- intégration au bureau (contrôleur pour MPRIS et l'icône de la barre) ----------
    def sync_desktop(self):
        self.mpris.changed()
        if self.tray:
            self.tray.changed()

    def mp_state(self):
        has = self.playing_url is not None
        pos = 0
        if has:
            ok, ns = self.player.query_position(Gst.Format.TIME)
            pos = ns // 1000 if ok else 0
        item = self.playing_item or {}
        title = self.p_title.get_text()
        if self.live and getattr(self, "live_title", None):
            title = f"{title} — {self.live_title}"
        return {
            "has_media": has,
            "status": "Stopped" if not has else ("Paused" if self.paused else "Playing"),
            "title": title, "artist": item.get("name", "") if not self.live else _("Live radio"),
            "track_id": f"{zlib.crc32((self.playing_url or '').encode()):08x}",
            "art_uri": ("file://" + self.art_path) if self.art_path else "",
            "length_us": 0 if self.live else self.duration // 1000,
            "position_us": pos, "can_seek": has and not self.live,
            "volume": self.player.get_property("volume"), "rate": SPEEDS[self.rate_idx], "live": self.live and has,
        }

    def mp_play(self):
        if self.playing_url and self.paused:
            self.on_play_pause(None)

    def mp_pause(self):
        if self.playing_url and not self.paused:
            self.on_play_pause(None)

    def mp_toggle(self):
        self.on_play_pause(None)

    def mp_stop(self):
        self.stop_playback()

    def mp_skip(self, seconds):
        if not self.live:
            self.skip(seconds)

    def mp_seek(self, offset_us):
        if not self.live:
            self.skip(offset_us / 1_000_000)

    def mp_set_position(self, position_us):
        if not self.live:
            self.seek_to(position_us * 1000)

    def mp_set_volume(self, value):
        self.vol.set_value(value)
        self.sync_desktop()

    def show_window(self):
        self.set_visible(True)
        self.present()

    def quit_app(self):
        self.player.set_state(Gst.State.NULL)
        self.get_application().quit()

    def on_close(self, _win):
        self.set_visible(False)  # la lecture continue ; on rouvre via l'icône de la barre
        return True


class App(Adw.Application):
    def __init__(self):
        super().__init__(application_id=APP_ID)

    def do_activate(self):
        # langues écrites de droite à gauche : on force le sens même si la locale système n'est pas installée
        lang = (os.environ.get("LANGUAGE") or os.environ.get("LC_ALL") or os.environ.get("LC_MESSAGES")
                or os.environ.get("LANG") or "").split(":")[0].split("_")[0].split(".")[0]
        if lang in ("he", "ar", "fa", "ur"):
            Gtk.Widget.set_default_direction(Gtk.TextDirection.RTL)
        first = self.props.active_window is None
        win = self.props.active_window or Window(self)
        if first and "--background" in sys.argv:
            return  # lancement automatique à l'ouverture de session : Podial reste en arrière-plan, sans fenêtre
        win.show_window()


if __name__ == "__main__":
    sys.exit(App().run(sys.argv))
