"""Intégration au bureau GNOME/Ubuntu, sans bibliothèque externe (D-Bus via Gio) :
- MPRIS2 : contrôles dans le menu de l'heure, écran de verrouillage, touches média ;
- StatusNotifierItem + DBusMenu : icône avec menu dans la barre du haut.

Le contrôleur (la fenêtre) doit fournir : mp_state() -> dict, mp_play(), mp_pause(), mp_toggle(), mp_stop(),
mp_seek(offset_us), mp_set_position(position_us), mp_set_volume(v), mp_skip(secondes), show_window(), quit_app()."""
import gettext
import hashlib
import os
import urllib.request

from gi.repository import Gio, GLib

from i18n import _, DOMAIN, _DIRS

APP_NAME = "Podial"
DESKTOP_ID = "io.github.podial.Podial"  # à adapter au compte GitHub / domaine définitif
ICON_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "icons", "hicolor", "symbolic", "apps")
ART_DIR = os.path.join(GLib.get_user_cache_dir(), "podial", "art")

MPRIS_XML = """<node>
 <interface name="org.mpris.MediaPlayer2">
  <method name="Raise"/><method name="Quit"/>
  <property name="CanQuit" type="b" access="read"/>
  <property name="CanRaise" type="b" access="read"/>
  <property name="HasTrackList" type="b" access="read"/>
  <property name="Identity" type="s" access="read"/>
  <property name="DesktopEntry" type="s" access="read"/>
  <property name="SupportedUriSchemes" type="as" access="read"/>
  <property name="SupportedMimeTypes" type="as" access="read"/>
 </interface>
 <interface name="org.mpris.MediaPlayer2.Player">
  <method name="Next"/><method name="Previous"/><method name="Pause"/>
  <method name="PlayPause"/><method name="Stop"/><method name="Play"/>
  <method name="Seek"><arg direction="in" type="x" name="Offset"/></method>
  <method name="SetPosition"><arg direction="in" type="o" name="TrackId"/><arg direction="in" type="x" name="Position"/></method>
  <method name="OpenUri"><arg direction="in" type="s" name="Uri"/></method>
  <signal name="Seeked"><arg type="x" name="Position"/></signal>
  <property name="PlaybackStatus" type="s" access="read"/>
  <property name="LoopStatus" type="s" access="readwrite"/>
  <property name="Rate" type="d" access="readwrite"/>
  <property name="Shuffle" type="b" access="readwrite"/>
  <property name="Metadata" type="a{sv}" access="read"/>
  <property name="Volume" type="d" access="readwrite"/>
  <property name="Position" type="x" access="read"/>
  <property name="MinimumRate" type="d" access="read"/>
  <property name="MaximumRate" type="d" access="read"/>
  <property name="CanGoNext" type="b" access="read"/>
  <property name="CanGoPrevious" type="b" access="read"/>
  <property name="CanPlay" type="b" access="read"/>
  <property name="CanPause" type="b" access="read"/>
  <property name="CanSeek" type="b" access="read"/>
  <property name="CanControl" type="b" access="read"/>
 </interface>
</node>"""

SNI_XML = """<node>
 <interface name="org.kde.StatusNotifierItem">
  <method name="ContextMenu"><arg type="i" name="x" direction="in"/><arg type="i" name="y" direction="in"/></method>
  <method name="Activate"><arg type="i" name="x" direction="in"/><arg type="i" name="y" direction="in"/></method>
  <method name="SecondaryActivate"><arg type="i" name="x" direction="in"/><arg type="i" name="y" direction="in"/></method>
  <method name="Scroll"><arg type="i" name="delta" direction="in"/><arg type="s" name="orientation" direction="in"/></method>
  <signal name="NewTitle"/><signal name="NewIcon"/><signal name="NewToolTip"/>
  <signal name="NewStatus"><arg type="s" name="status"/></signal>
  <property name="Category" type="s" access="read"/>
  <property name="Id" type="s" access="read"/>
  <property name="Title" type="s" access="read"/>
  <property name="Status" type="s" access="read"/>
  <property name="WindowId" type="u" access="read"/>
  <property name="IconName" type="s" access="read"/>
  <property name="IconThemePath" type="s" access="read"/>
  <property name="IconPixmap" type="a(iiay)" access="read"/>
  <property name="OverlayIconName" type="s" access="read"/>
  <property name="OverlayIconPixmap" type="a(iiay)" access="read"/>
  <property name="AttentionIconName" type="s" access="read"/>
  <property name="AttentionIconPixmap" type="a(iiay)" access="read"/>
  <property name="AttentionMovieName" type="s" access="read"/>
  <property name="ToolTip" type="(sa(iiay)ss)" access="read"/>
  <property name="ItemIsMenu" type="b" access="read"/>
  <property name="Menu" type="o" access="read"/>
 </interface>
</node>"""

MENU_XML = """<node>
 <interface name="com.canonical.dbusmenu">
  <method name="GetLayout">
   <arg type="i" name="parentId" direction="in"/><arg type="i" name="recursionDepth" direction="in"/>
   <arg type="as" name="propertyNames" direction="in"/>
   <arg type="u" name="revision" direction="out"/><arg type="(ia{sv}av)" name="layout" direction="out"/>
  </method>
  <method name="GetGroupProperties">
   <arg type="ai" name="ids" direction="in"/><arg type="as" name="propertyNames" direction="in"/>
   <arg type="a(ia{sv})" name="properties" direction="out"/>
  </method>
  <method name="GetProperty">
   <arg type="i" name="id" direction="in"/><arg type="s" name="name" direction="in"/>
   <arg type="v" name="value" direction="out"/>
  </method>
  <method name="Event">
   <arg type="i" name="id" direction="in"/><arg type="s" name="eventId" direction="in"/>
   <arg type="v" name="data" direction="in"/><arg type="u" name="timestamp" direction="in"/>
  </method>
  <method name="EventGroup">
   <arg type="a(isvu)" name="events" direction="in"/><arg type="ai" name="idErrors" direction="out"/>
  </method>
  <method name="AboutToShow"><arg type="i" name="id" direction="in"/><arg type="b" name="needUpdate" direction="out"/></method>
  <method name="AboutToShowGroup">
   <arg type="ai" name="ids" direction="in"/>
   <arg type="ai" name="updatesNeeded" direction="out"/><arg type="ai" name="idErrors" direction="out"/>
  </method>
  <signal name="ItemsPropertiesUpdated"><arg type="a(ia{sv})" name="updatedProps"/><arg type="a(ias)" name="removedProps"/></signal>
  <signal name="LayoutUpdated"><arg type="u" name="revision"/><arg type="i" name="parent"/></signal>
  <signal name="ItemActivationRequested"><arg type="i" name="id"/><arg type="u" name="timestamp"/></signal>
  <property name="Version" type="u" access="read"/>
  <property name="TextDirection" type="s" access="read"/>
  <property name="Status" type="s" access="read"/>
  <property name="IconThemePath" type="as" access="read"/>
 </interface>
</node>"""


def cache_art(url):
    """Télécharge la pochette dans le cache (GNOME affiche les pochettes locales) et renvoie son chemin."""
    if not url:
        return None
    os.makedirs(ART_DIR, exist_ok=True)
    path = os.path.join(ART_DIR, hashlib.sha1(url.encode()).hexdigest() + ".img")
    if not os.path.exists(path):
        req = urllib.request.Request(url, headers={"User-Agent": "Podial/0.4"})
        with urllib.request.urlopen(req, timeout=10) as r:
            data = r.read()
        with open(path, "wb") as f:
            f.write(data)
    return path


def install_desktop_entry(script_path):
    """Crée le lanceur du menu Applications (nécessaire à GNOME pour nommer l'appli dans ses contrôles média)."""
    apps = os.path.join(GLib.get_user_data_dir(), "applications")
    path = os.path.join(apps, DESKTOP_ID + ".desktop")
    comments = "Comment=Podcasts and live radio\n"
    for lang in ("fr", "es", "de", "pt", "he", "ar"):  # une ligne Comment[xx] par langue fournie
        for d in _DIRS:
            try:
                comments += f"Comment[{lang}]={gettext.translation(DOMAIN, d, languages=[lang]).gettext('Podcasts and live radio')}\n"
                break
            except OSError:
                continue
    content = (f"[Desktop Entry]\nType=Application\nName={APP_NAME}\n" + comments +
               f"Exec=python3 {script_path}\nIcon=audio-headphones\nTerminal=false\n"
               "Categories=AudioVideo;Audio;Player;\n")
    try:
        with open(path, encoding="utf-8") as f:
            if f.read() == content:
                return
    except OSError:
        pass
    os.makedirs(apps, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)


def shell_extension_active(uuid):
    """Vrai si l'extension GNOME Shell est chargée dans la session en cours."""
    try:
        proxy = Gio.DBusProxy.new_for_bus_sync(
            Gio.BusType.SESSION, Gio.DBusProxyFlags.NONE, None, "org.gnome.Shell.Extensions",
            "/org/gnome/Shell/Extensions", "org.gnome.Shell.Extensions", None)
        info = proxy.call_sync("GetExtensionInfo", GLib.Variant("(s)", (uuid,)), Gio.DBusCallFlags.NONE, 1500, None)
        return info.unpack()[0].get("state") == 1  # 1 = ACTIVE
    except GLib.Error:
        return False


def _interfaces(xml):
    return {i.name: i for i in Gio.DBusNodeInfo.new_for_xml(xml).interfaces}


class Mpris:
    """Service D-Bus MPRIS2 : le lecteur apparaît dans les contrôles média de GNOME."""
    PATH = "/org/mpris/MediaPlayer2"

    def __init__(self, ctl):
        self.ctl = ctl
        self.con = None
        self.ifaces = _interfaces(MPRIS_XML)
        # noms supplémentaires (séparés par des espaces) : permet à une ancienne extension de se reconnecter
        names = ["org.mpris.MediaPlayer2.Podial"] + os.environ.get("PODIAL_EXTRA_MPRIS_NAMES", "").split()
        for name in names:
            Gio.bus_own_name(Gio.BusType.SESSION, name, Gio.BusNameOwnerFlags.NONE, self._on_bus, None, None)

    def _on_bus(self, con, _name):
        if self.con is not None:
            return  # les objets sont déjà publiés sur cette connexion (nom supplémentaire)
        self.con = con
        for iface in self.ifaces.values():
            con.register_object(self.PATH, iface, self._on_call, self._on_get, self._on_set)

    # --- propriétés ---
    def _metadata(self, st):
        if not st["has_media"]:
            return GLib.Variant("a{sv}", {"mpris:trackid": GLib.Variant("o", "/org/mpris/MediaPlayer2/TrackList/NoTrack")})
        md = {
            "mpris:trackid": GLib.Variant("o", "/io/github/podial/Podial/track/" + st["track_id"]),
            "xesam:title": GLib.Variant("s", st["title"]),
            "xesam:artist": GLib.Variant("as", [st["artist"]]),
        }
        if st["art_uri"]:
            md["mpris:artUrl"] = GLib.Variant("s", st["art_uri"])
        if st["length_us"]:
            md["mpris:length"] = GLib.Variant("x", st["length_us"])
        return GLib.Variant("a{sv}", md)

    def _props(self):
        st = self.ctl.mp_state()
        playing = st["has_media"]
        return {
            "CanQuit": GLib.Variant("b", True), "CanRaise": GLib.Variant("b", True),
            "HasTrackList": GLib.Variant("b", False), "Identity": GLib.Variant("s", APP_NAME),
            "DesktopEntry": GLib.Variant("s", DESKTOP_ID),
            "SupportedUriSchemes": GLib.Variant("as", ["http", "https"]),
            "SupportedMimeTypes": GLib.Variant("as", ["audio/mpeg", "audio/aac", "audio/ogg"]),
            "PlaybackStatus": GLib.Variant("s", st["status"]),
            "LoopStatus": GLib.Variant("s", "None"), "Rate": GLib.Variant("d", st["rate"]),
            "Shuffle": GLib.Variant("b", False), "Metadata": self._metadata(st),
            "Volume": GLib.Variant("d", st["volume"]), "Position": GLib.Variant("x", st["position_us"]),
            "MinimumRate": GLib.Variant("d", 0.75), "MaximumRate": GLib.Variant("d", 2.0),
            "CanGoNext": GLib.Variant("b", playing and st["can_seek"]),
            "CanGoPrevious": GLib.Variant("b", playing and st["can_seek"]),
            "CanPlay": GLib.Variant("b", playing), "CanPause": GLib.Variant("b", playing),
            "CanSeek": GLib.Variant("b", playing and st["can_seek"]), "CanControl": GLib.Variant("b", True),
        }

    def _on_get(self, _con, _sender, _path, _iface, prop):
        return self._props().get(prop)

    def _on_set(self, _con, _sender, _path, _iface, prop, value):
        if prop == "Volume":
            self.ctl.mp_set_volume(max(0.0, min(1.0, value.get_double())))
            return True
        return prop in ("LoopStatus", "Rate", "Shuffle")  # acceptés mais sans effet

    def _on_call(self, _con, _sender, _path, iface, method, params, invocation):
        c = self.ctl
        if iface == "org.mpris.MediaPlayer2":
            {"Raise": c.show_window, "Quit": c.quit_app}[method]()
        else:
            args = params.unpack()
            actions = {
                "Play": c.mp_play, "Pause": c.mp_pause, "PlayPause": c.mp_toggle, "Stop": c.mp_stop,
                "Next": lambda: c.mp_skip(30), "Previous": lambda: c.mp_skip(-15),
                "Seek": lambda: c.mp_seek(args[0]), "SetPosition": lambda: c.mp_set_position(args[1]),
                "OpenUri": lambda: None,
            }
            actions[method]()
        invocation.return_value(None)

    def changed(self):
        """À appeler quand l'état de lecture ou le média change."""
        if self.con is None:
            return
        props = self._props()
        keep = ("PlaybackStatus", "Metadata", "Volume", "Rate", "CanGoNext", "CanGoPrevious", "CanPlay",
                "CanPause", "CanSeek")
        self.con.emit_signal(None, self.PATH, "org.freedesktop.DBus.Properties", "PropertiesChanged",
                             GLib.Variant("(sa{sv}as)", ("org.mpris.MediaPlayer2.Player",
                                                         {k: props[k] for k in keep}, [])))


class Tray:
    """Icône dans la barre du haut (StatusNotifierItem) avec menu DBusMenu."""
    ITEM_PATH = "/StatusNotifierItem"
    MENU_PATH = "/MenuBar"
    # id du menu -> (étiquette fixe ou None si dynamique)
    ID_TITLE, ID_SEP1, ID_TOGGLE, ID_BACK, ID_FWD, ID_SEP2, ID_OPEN, ID_QUIT = range(1, 9)

    def __init__(self, ctl):
        self.ctl = ctl
        self.con = None
        self.revision = 1
        self.sni = _interfaces(SNI_XML)["org.kde.StatusNotifierItem"]
        self.menu = _interfaces(MENU_XML)["com.canonical.dbusmenu"]
        self.name = f"org.kde.StatusNotifierItem-{os.getpid()}-1"
        Gio.bus_own_name(Gio.BusType.SESSION, self.name, Gio.BusNameOwnerFlags.NONE, self._on_bus, None, None)

    def _on_bus(self, con, _name):
        self.con = con
        con.register_object(self.ITEM_PATH, self.sni, self._item_call, self._item_get, None)
        con.register_object(self.MENU_PATH, self.menu, self._menu_call, self._menu_get, None)
        # le « watcher » est fourni par l'extension d'Ubuntu ; on se réenregistre s'il redémarre
        Gio.bus_watch_name_on_connection(con, "org.kde.StatusNotifierWatcher", Gio.BusNameWatcherFlags.NONE,
                                         self._on_watcher, None)

    def _on_watcher(self, con, _name, _owner):
        con.call("org.kde.StatusNotifierWatcher", "/StatusNotifierWatcher", "org.kde.StatusNotifierWatcher",
                 "RegisterStatusNotifierItem", GLib.Variant("(s)", (self.name,)), None,
                 Gio.DBusCallFlags.NONE, -1, None, None)

    # --- élément de la barre ---
    def _item_get(self, _con, _sender, _path, _iface, prop):
        st = self.ctl.mp_state()
        tip = st["title"] if st["has_media"] else _("Nothing playing")
        icon = "podial-radio-symbolic" if st["live"] else "podial-podcast-symbolic"
        values = {
            "Category": GLib.Variant("s", "ApplicationStatus"), "Id": GLib.Variant("s", "podial"),
            "Title": GLib.Variant("s", APP_NAME), "Status": GLib.Variant("s", "Active"),
            "WindowId": GLib.Variant("u", 0),
            "IconName": GLib.Variant("s", icon), "IconThemePath": GLib.Variant("s", ICON_DIR),
            "IconPixmap": GLib.Variant("a(iiay)", []), "OverlayIconName": GLib.Variant("s", ""),
            "OverlayIconPixmap": GLib.Variant("a(iiay)", []), "AttentionIconName": GLib.Variant("s", ""),
            "AttentionIconPixmap": GLib.Variant("a(iiay)", []), "AttentionMovieName": GLib.Variant("s", ""),
            "ToolTip": GLib.Variant("(sa(iiay)ss)", (icon, [], APP_NAME, tip)),
            "ItemIsMenu": GLib.Variant("b", False), "Menu": GLib.Variant("o", self.MENU_PATH),
        }
        return values.get(prop)

    def _item_call(self, _con, _sender, _path, _iface, method, params, invocation):
        if method == "SecondaryActivate":
            self.ctl.mp_toggle()
        elif method == "Activate":
            self.ctl.show_window()
        elif method == "Scroll":
            delta = params.unpack()[0]
            st = self.ctl.mp_state()
            self.ctl.mp_set_volume(max(0.0, min(1.0, st["volume"] + (0.05 if delta > 0 else -0.05))))
        invocation.return_value(None)

    # --- menu ---
    def _entries(self):
        st = self.ctl.mp_state()
        media = st["has_media"]
        title = st["title"] if media else _("Nothing playing")
        playing = st["status"] == "Playing"
        sep = {"type": GLib.Variant("s", "separator")}

        def item(label, enabled=True):
            return {"label": GLib.Variant("s", label), "enabled": GLib.Variant("b", enabled)}

        return [
            (self.ID_TITLE, item(("♪ " + title)[:60], False)),
            (self.ID_SEP1, sep),
            (self.ID_TOGGLE, item(_("Pause") if playing else _("Play"), media)),
            (self.ID_BACK, item(_("Rewind 15 s"), media and st["can_seek"])),
            (self.ID_FWD, item(_("Forward 30 s"), media and st["can_seek"])),
            (self.ID_SEP2, sep),
            (self.ID_OPEN, item(_("Open window"))),
            (self.ID_QUIT, item(_("Quit"))),
        ]

    def _layout(self):
        children = [GLib.Variant("(ia{sv}av)", (i, p, [])) for i, p in self._entries()]
        return (0, {"children-display": GLib.Variant("s", "submenu")}, children)

    def _menu_get(self, _con, _sender, _path, _iface, prop):
        return {"Version": GLib.Variant("u", 3), "TextDirection": GLib.Variant("s", "ltr"),
                "Status": GLib.Variant("s", "normal"), "IconThemePath": GLib.Variant("as", [])}.get(prop)

    def _activate(self, item_id):
        c = self.ctl
        {self.ID_TOGGLE: c.mp_toggle, self.ID_BACK: lambda: c.mp_skip(-15), self.ID_FWD: lambda: c.mp_skip(30),
         self.ID_OPEN: c.show_window, self.ID_QUIT: c.quit_app}.get(item_id, lambda: None)()

    def _menu_call(self, _con, _sender, _path, _iface, method, params, invocation):
        if method == "GetLayout":
            invocation.return_value(GLib.Variant("(u(ia{sv}av))", (self.revision, self._layout())))
        elif method == "GetGroupProperties":
            ids = params.unpack()[0]
            props = [(i, p) for i, p in self._entries() if not ids or i in ids]
            invocation.return_value(GLib.Variant("(a(ia{sv}))", (props,)))
        elif method == "GetProperty":
            iid, name = params.unpack()
            val = dict(self._entries()).get(iid, {}).get(name, GLib.Variant("s", ""))
            invocation.return_value(GLib.Variant("(v)", (val,)))
        elif method == "Event":
            iid, event, _data, _ts = params.unpack()
            if event == "clicked":
                self._activate(iid)
            invocation.return_value(None)
        elif method == "EventGroup":
            for iid, event, _data, _ts in params.unpack()[0]:
                if event == "clicked":
                    self._activate(iid)
            invocation.return_value(GLib.Variant("(ai)", ([],)))
        elif method == "AboutToShow":
            invocation.return_value(GLib.Variant("(b)", (False,)))
        elif method == "AboutToShowGroup":
            invocation.return_value(GLib.Variant("(aiai)", ([], [])))

    def changed(self):
        """Met à jour le menu (titre, Lecture/Pause) et l'infobulle."""
        if self.con is None:
            return
        self.revision += 1
        self.con.emit_signal(None, self.MENU_PATH, "com.canonical.dbusmenu", "LayoutUpdated",
                             GLib.Variant("(ui)", (self.revision, 0)))
        self.con.emit_signal(None, self.ITEM_PATH, "org.kde.StatusNotifierItem", "NewIcon", None)
        self.con.emit_signal(None, self.ITEM_PATH, "org.kde.StatusNotifierItem", "NewToolTip", None)
