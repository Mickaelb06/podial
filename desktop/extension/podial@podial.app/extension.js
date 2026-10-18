// Podial : lecteur animé dans la barre du haut de GNOME Shell.
// Il se branche sur l'appli par MPRIS (org.mpris.MediaPlayer2.Podial).
import Clutter from 'gi://Clutter';
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import GObject from 'gi://GObject';
import Pango from 'gi://Pango';
import Shell from 'gi://Shell';
import St from 'gi://St';

import {Extension, gettext as _} from 'resource:///org/gnome/shell/extensions/extension.js';
import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import * as PanelMenu from 'resource:///org/gnome/shell/ui/panelMenu.js';
import * as PopupMenu from 'resource:///org/gnome/shell/ui/popupMenu.js';
import {Slider} from 'resource:///org/gnome/shell/ui/slider.js';

const BUS_NAME = 'org.mpris.MediaPlayer2.Podial';
const OBJ_PATH = '/org/mpris/MediaPlayer2';
const PLAYER_IFACE = 'org.mpris.MediaPlayer2.Player';
const APP_DESKTOP_ID = 'io.github.podial.Podial.desktop';

const PlayerProxy = Gio.DBusProxy.makeProxyWrapper(`
<node><interface name="${PLAYER_IFACE}">
  <method name="PlayPause"/><method name="Next"/><method name="Previous"/>
  <method name="SetPosition"><arg direction="in" type="o" name="TrackId"/><arg direction="in" type="x" name="Position"/></method>
  <property name="PlaybackStatus" type="s" access="read"/>
  <property name="Metadata" type="a{sv}" access="read"/>
  <property name="Volume" type="d" access="readwrite"/>
  <property name="CanSeek" type="b" access="read"/>
</interface></node>`);

const PILL_BARS = 4;
const PILL_BAR_MIN = 3;
const PILL_BAR_MAX = 15;
const SPEC_BARS = 26;
const SPEC_MIN = 4;
const SPEC_MAX = 36;
const TITLE_MAX_WIDTH = 190;   // largeur max du titre dans la barre (px logiques)
const DISC_SIZE = 236;
const SPIN_STEP = 1.1;         // degrés par image (~33 ms) : un tour en ~11 s

function fmtTime(us) {
    const s = Math.max(0, Math.floor(us / 1e6));
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const sec = String(s % 60).padStart(2, '0');
    return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`;
}

function hsl([h, s, l]) {
    h = ((h % 360) + 360) % 360 / 360;
    const f = n => {
        const k = (n + h * 12) % 12;
        const a = s * Math.min(l, 1 - l);
        return Math.round(255 * (l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1))));
    };
    return `rgb(${f(0)}, ${f(8)}, ${f(4)})`;
}

const PodcastsIndicator = GObject.registerClass(
class PodcastsIndicator extends PanelMenu.Button {
    _init(path) {
        super._init(0.5, 'Podial', false);
        this._path = path;
        this._radioIcon = false;
        this._proxy = null;
        this._meta = {};
        this._dragging = false;
        this._timers = new Set();
        this._marqueeGen = 0;
        this._lastTitle = null;
        this._lastStatus = null;
        this._phase = 0;
        this._angle = 0;

        this._buildPill();
        this._buildMenu();
        this._buildContextMenu();
        this.menu.connect('open-state-changed', (_m, open) => this._onMenuToggled(open));
        this.connect('destroy', () => { this._cleanup(); this._ctx?.destroy(); this._destroyed = true; });
        this.connect('scroll-event', (_a, event) => this._onScroll(event));
        this.visible = false;
    }

    // ---------- pastille de la barre ----------
    _buildPill() {
        this._pill = new St.BoxLayout({style_class: 'pb-pill pb-pill-idle', y_align: Clutter.ActorAlign.CENTER});
        this._pillIcon = new St.Icon({gicon: new Gio.ThemedIcon({name: 'audio-headphones-symbolic'}),
            style_class: 'pb-icon', y_align: Clutter.ActorAlign.CENTER});
        this._pill.add_child(this._pillIcon);
        this._eq = new St.BoxLayout({style_class: 'pb-eq', y_align: Clutter.ActorAlign.CENTER, visible: false});
        this._bars = [];
        for (let i = 0; i < PILL_BARS; i++) {
            const bar = new St.Widget({style_class: 'pb-eq-bar', y_align: Clutter.ActorAlign.END, height: PILL_BAR_MIN});
            this._bars.push(bar);
            this._eq.add_child(bar);
        }
        this._pill.add_child(this._eq);

        // titre qui défile s'il est trop long
        this._clip = new St.Widget({clip_to_allocation: true, layout_manager: new Clutter.BinLayout(),
            y_align: Clutter.ActorAlign.CENTER, visible: false});
        this._label = new St.Label({style_class: 'pb-label', x_align: Clutter.ActorAlign.START,
            y_align: Clutter.ActorAlign.CENTER});
        this._label.clutter_text.ellipsize = Pango.EllipsizeMode.NONE;
        this._clip.add_child(this._label);
        this._pill.add_child(this._clip);
        this.add_child(this._pill);
        // après un verrouillage d'écran la pastille réapparaît : on relance le défilement du titre
        this._pill.connect('notify::mapped', () => {
            if (this._pill.mapped && this._playing())
                this._startMarquee();
        });
    }

    _scale() {
        return St.ThemeContext.get_for_stage(global.stage).scale_factor;
    }

    _startMarquee() {
        const gen = ++this._marqueeGen;
        this._label.remove_all_transitions();
        this._label.translation_x = 0;
        this._label.opacity = 255;
        const max = TITLE_MAX_WIDTH * this._scale();
        const [, natural] = this._label.get_preferred_width(-1);
        this._clip.width = Math.min(natural, max);
        if (natural <= max)
            return;
        const dist = natural - max;
        const run = () => {
            if (gen !== this._marqueeGen)
                return;
            this._label.ease({
                translation_x: -dist, duration: Math.round(dist * 30), delay: 1400,
                mode: Clutter.AnimationMode.LINEAR,
                onComplete: () => {
                    if (gen !== this._marqueeGen)
                        return;
                    this._label.ease({opacity: 0, duration: 250, onComplete: () => {
                        if (gen !== this._marqueeGen)
                            return;
                        this._label.translation_x = 0;
                        this._label.ease({opacity: 255, duration: 250, onComplete: () => this._later(1000, run)});
                    }});
                },
            });
        };
        run();
    }

    /** Relance `fn` plus tard depuis la boucle principale : évite qu'une animation déjà terminée se relance sans fin (récursion). */
    _later(ms, fn) {
        const id = GLib.timeout_add(GLib.PRIORITY_DEFAULT, ms, () => {
            this._timers.delete(id);
            fn();
            return GLib.SOURCE_REMOVE;
        });
        this._timers.add(id);
    }

    _popInTitle() {
        this._label.remove_all_transitions();
        this._label.opacity = 0;
        this._label.translation_y = 10;
        this._label.ease({opacity: 255, translation_y: 0, duration: 380,
            mode: Clutter.AnimationMode.EASE_OUT_BACK,
            onComplete: () => this._startMarquee()});
    }

    _animatePill() {
        this._phase += 0.35;
        // barres d'égaliseur : mouvement fluide, chacune à son rythme
        this._bars.forEach((bar, i) => {
            const v = Math.abs(Math.sin(this._phase * (0.9 + i * 0.37) + i * 1.7));
            const h = PILL_BAR_MIN + Math.round((PILL_BAR_MAX - PILL_BAR_MIN) * (0.25 + 0.75 * v * (0.7 + Math.random() * 0.3)));
            bar.ease({height: h, duration: 210, mode: Clutter.AnimationMode.EASE_IN_OUT_QUAD});
        });
        // le dégradé de la pastille glisse lentement entre violet, rose et orange
        const t = this._phase * 0.25;
        const start = hsl([262 + 22 * Math.sin(t), 0.82, 0.62]);
        const end = hsl([325 + 30 * Math.sin(t + 1.4), 0.85, 0.60]);
        this._pill.style = `background-gradient-start: ${start}; background-gradient-end: ${end};`;
    }

    _calmPill() {
        for (const bar of this._bars)
            bar.ease({height: PILL_BAR_MIN, duration: 250, mode: Clutter.AnimationMode.EASE_OUT_QUAD});
        this._pill.style = '';
    }

    // ---------- menu déroulant ----------
    _buildMenu() {
        this._content = new St.BoxLayout({vertical: true, style_class: 'pb-content', x_align: Clutter.ActorAlign.FILL});
        // fond sombre quel que soit le thème du système (le texte de Podial est clair)
        this.menu._boxPointer?.add_style_class_name('pb-popup');
        this.menu.box.add_style_class_name('pb-popup-box');
        this.menu.box.add_child(this._content);

        // scène : halo + disque vinyle + trou central
        this._stage = new St.Widget({layout_manager: new Clutter.BinLayout(), x_align: Clutter.ActorAlign.CENTER,
            width: DISC_SIZE + 44, height: DISC_SIZE + 24});
        this._glow = new St.Widget({style_class: 'pb-glow', width: DISC_SIZE - 6, height: DISC_SIZE - 6,
            x_align: Clutter.ActorAlign.CENTER, y_align: Clutter.ActorAlign.CENTER});
        this._disc = new St.Widget({style_class: 'pb-disc-empty', width: DISC_SIZE, height: DISC_SIZE,
            x_align: Clutter.ActorAlign.CENTER, y_align: Clutter.ActorAlign.CENTER});
        this._disc.set_pivot_point(0.5, 0.5);
        this._hole = new St.Widget({style_class: 'pb-hole', x_align: Clutter.ActorAlign.CENTER,
            y_align: Clutter.ActorAlign.CENTER});
        this._stage.add_child(this._glow);
        this._stage.add_child(this._disc);
        this._stage.add_child(this._hole);
        this._content.add_child(this._stage);

        this._live = new St.Label({text: `● ${_('LIVE')}`, style_class: 'pb-live', x_align: Clutter.ActorAlign.CENTER,
            visible: false});
        this._content.add_child(this._live);
        this._title = new St.Label({style_class: 'pb-title', text: _('Nothing playing')});
        this._title.clutter_text.line_wrap = true;
        this._title.clutter_text.ellipsize = Pango.EllipsizeMode.END;
        this._title.clutter_text.line_wrap_mode = Pango.WrapMode.WORD_CHAR;
        this._content.add_child(this._title);
        this._sub = new St.Label({style_class: 'pb-sub', text: ''});
        this._sub.clutter_text.ellipsize = Pango.EllipsizeMode.END;
        this._content.add_child(this._sub);

        // spectre animé
        this._spectrum = new St.BoxLayout({style_class: 'pb-spectrum', x_align: Clutter.ActorAlign.CENTER});
        this._specBars = [];
        for (let i = 0; i < SPEC_BARS; i++) {
            const bar = new St.Widget({style_class: 'pb-spec-bar', y_align: Clutter.ActorAlign.END, height: SPEC_MIN});
            this._specBars.push(bar);
            this._spectrum.add_child(bar);
        }
        this._content.add_child(this._spectrum);

        // progression
        this._progressRow = new St.BoxLayout({style: 'spacing: 8px;'});
        this._posLabel = new St.Label({text: '0:00', style_class: 'pb-time', y_align: Clutter.ActorAlign.CENTER});
        this._progress = new Slider(0);
        this._progress.add_style_class_name('pb-menu-slider');
        this._progress.connect('drag-begin', () => { this._dragging = true; });
        this._progress.connect('drag-end', () => {
            this._dragging = false;
            this._seekTo(this._progress.value);
        });
        this._durLabel = new St.Label({text: '0:00', style_class: 'pb-time', y_align: Clutter.ActorAlign.CENTER});
        this._progressRow.add_child(this._posLabel);
        this._progressRow.add_child(this._progress);
        this._progressRow.add_child(this._durLabel);
        this._content.add_child(this._progressRow);

        // boutons
        const controls = new St.BoxLayout({style: 'spacing: 16px;', x_align: Clutter.ActorAlign.CENTER});
        controls.add_child(this._button('media-seek-backward-symbolic', 'pb-btn', 18, () => this._call('Previous')));
        this._playBtn = this._button('media-playback-start-symbolic', 'pb-play', 28, () => this._call('PlayPause'));
        controls.add_child(this._playBtn);
        controls.add_child(this._button('media-seek-forward-symbolic', 'pb-btn', 18, () => this._call('Next')));
        this._content.add_child(controls);

        // volume
        const volRow = new St.BoxLayout({style: 'spacing: 10px;'});
        volRow.add_child(new St.Icon({icon_name: 'audio-volume-medium-symbolic', icon_size: 16,
            y_align: Clutter.ActorAlign.CENTER, style: 'color: rgba(255,255,255,0.7);'}));
        this._volume = new Slider(1);
        this._volume.add_style_class_name('pb-menu-slider');
        this._volume.connect('notify::value', () => {
            if (this._proxy && !this._settingVolume)
                this._proxy.Volume = this._volume.value;
        });
        volRow.add_child(this._volume);
        this._content.add_child(volRow);

        const open = new St.Button({label: _('Open the app'), style_class: 'pb-open',
            x_align: Clutter.ActorAlign.CENTER, can_focus: true});
        open.connect('clicked', () => {
            this.menu.close();
            Shell.AppSystem.get_default().lookup_app(APP_DESKTOP_ID)?.activate();
        });
        this._content.add_child(open);
    }

    // Clic droit sur la pastille : ouvrir ou quitter l'application
    _buildContextMenu() {
        this._ctx = new PopupMenu.PopupMenu(this, 0.5, St.Side.TOP);
        Main.uiGroup.add_child(this._ctx.actor);
        this._ctx.actor.hide();
        this._ctx.addAction(_('Open window'), () =>
            Shell.AppSystem.get_default().lookup_app(APP_DESKTOP_ID)?.activate());
        this._ctx.addAction(_('Quit'), () =>
            Gio.DBus.session.call(BUS_NAME, OBJ_PATH, 'org.mpris.MediaPlayer2', 'Quit', null, null,
                Gio.DBusCallFlags.NONE, -1, null, null));
    }

    vfunc_event(event) {
        if (event.type() === Clutter.EventType.BUTTON_PRESS && event.get_button() === Clutter.BUTTON_SECONDARY) {
            this.menu.close();
            if (!this._ctxAdded) {
                Main.panel.menuManager.addMenu(this._ctx);
                this._ctxAdded = true;
            }
            this._ctx.toggle();
            return Clutter.EVENT_STOP;
        }
        try {
            return super.vfunc_event(event);
        } catch (e) {
            return Clutter.EVENT_PROPAGATE;
        }
    }

    _button(iconName, styleClass, size, onClick) {
        const btn = new St.Button({style_class: styleClass, can_focus: true, reactive: true, track_hover: true,
            child: new St.Icon({icon_name: iconName, icon_size: size})});
        btn.set_pivot_point(0.5, 0.5);
        btn.connect('clicked', onClick);
        // les boutons grossissent au survol et « s'enfoncent » au clic
        const grow = scale => btn.ease({scale_x: scale, scale_y: scale, duration: 140,
            mode: Clutter.AnimationMode.EASE_OUT_QUAD});
        btn.connect('notify::hover', () => grow(btn.hover ? 1.12 : 1));
        btn.connect('notify::pressed', () => grow(btn.pressed ? 0.92 : (btn.hover ? 1.12 : 1)));
        return btn;
    }

    _onMenuToggled(open) {
        this._menuTimerStop();
        if (!open)
            return;
        this._pollPosition();
        // entrée en scène : le contenu glisse, le disque « pop »
        this._content.opacity = 0;
        this._content.translation_y = 14;
        this._content.ease({opacity: 255, translation_y: 0, duration: 280, mode: Clutter.AnimationMode.EASE_OUT_QUAD});
        this._disc.set_scale(0.8, 0.8);
        this._disc.ease({scale_x: 1, scale_y: 1, duration: 520, mode: Clutter.AnimationMode.EASE_OUT_BACK});
        // boucle d'animation (≈30 images/s) : seulement tant que le menu est ouvert
        this._spinTick = 0;
        this._menuTimerId = GLib.timeout_add(GLib.PRIORITY_DEFAULT, 33, () => {
            this._menuFrame();
            return GLib.SOURCE_CONTINUE;
        });
    }

    _menuTimerStop() {
        if (this._menuTimerId) {
            GLib.source_remove(this._menuTimerId);
            this._menuTimerId = 0;
        }
    }

    _menuFrame() {
        const playing = this._playing();
        this._spinTick++;
        if (playing) {
            this._angle = (this._angle + SPIN_STEP) % 360;
            this._disc.rotation_angle_z = this._angle;
            // le halo « respire »
            this._glow.opacity = Math.round(150 + 90 * Math.sin(this._spinTick * 0.09));
            // le bouton lecture bat au rythme de la musique
            const s = 1 + 0.045 * Math.sin(this._spinTick * 0.18);
            this._playBtn.set_scale(s, s);
        } else {
            this._glow.opacity = Math.max(60, this._glow.opacity - 6);
            this._playBtn.set_scale(1, 1);
        }
        if (this._spinTick % 5 === 0)
            this._animateSpectrum(playing);
    }

    _animateSpectrum(playing) {
        const t = this._spinTick * 0.05;
        this._specBars.forEach((bar, i) => {
            let h = SPEC_MIN;
            if (playing) {
                // courbe en cloche (graves et aigus plus bas) + vagues + un peu de hasard
                const bell = Math.sin((i / (SPEC_BARS - 1)) * Math.PI) * 0.5 + 0.5;
                const wave = Math.abs(Math.sin(t * (1.1 + (i % 5) * 0.23) + i * 0.9));
                h = SPEC_MIN + (SPEC_MAX - SPEC_MIN) * Math.min(1, bell * wave * (0.65 + Math.random() * 0.45));
            }
            bar.ease({height: Math.round(h), duration: 170, mode: Clutter.AnimationMode.EASE_OUT_QUAD});
        });
    }

    // ---------- connexion à l'appli ----------
    attach() {
        if (this._proxy)
            return;
        this._proxy = new PlayerProxy(Gio.DBus.session, BUS_NAME, OBJ_PATH, (_p, error) => {
            if (error) {
                logError(error, 'Podial');
                this._proxy = null;
                return;
            }
            this._proxy.connectObject('g-properties-changed', () => this._refresh(), this);
            this._refresh();
            this.visible = true;
            this._timer(1000, () => this._tick());
            this._timer(230, () => {
                if (this._playing())
                    this._animatePill();
                return true;
            });
        });
    }

    _cleanup() {
        this._menuTimerStop();
        for (const id of this._timers)
            GLib.source_remove(id);
        this._timers.clear();
        this._marqueeGen++;  // invalide les animations de défilement en cours
    }

    detach() {
        if (this._destroyed)
            return;
        this._cleanup();
        this._proxy?.disconnectObject(this);
        this._proxy = null;
        this.menu.close();
        this.visible = false;
    }

    _timer(ms, fn) {
        const id = GLib.timeout_add(GLib.PRIORITY_DEFAULT, ms, () => {
            if (!fn()) {
                this._timers.delete(id);
                return GLib.SOURCE_REMOVE;
            }
            return GLib.SOURCE_CONTINUE;
        });
        this._timers.add(id);
    }

    _playing() {
        return this._proxy?.PlaybackStatus === 'Playing';
    }

    _call(method) {
        this._proxy?.[`${method}Async`]().catch(logError);
    }

    _readMeta() {
        const meta = {};
        const raw = this._proxy?.Metadata ?? {};
        for (const key in raw)
            meta[key] = raw[key].deepUnpack();
        return meta;
    }

    _refresh() {
        if (!this._proxy)
            return;
        const meta = this._meta = this._readMeta();
        const status = this._proxy.PlaybackStatus ?? 'Stopped';
        const hasTrack = typeof meta['xesam:title'] === 'string';
        const title = hasTrack ? meta['xesam:title'] : _('Nothing playing');
        const artist = Array.isArray(meta['xesam:artist']) ? meta['xesam:artist'][0] : '';
        const length = Number(meta['mpris:length'] ?? 0);
        const live = hasTrack && length === 0;
        this._trackId = meta['mpris:trackid'] ?? '/';
        this._length = length;

        // barre : un casque pour les podcasts, un poste de radio pour les radios en direct
        if (live !== this._radioIcon) {
            this._radioIcon = live;
            this._pillIcon.gicon = live
                ? new Gio.FileIcon({file: Gio.File.new_for_path(`${this._path}/icons/podial-radio-symbolic.svg`)})
                : new Gio.ThemedIcon({name: 'audio-headphones-symbolic'});
        }
        // barre : pastille pleine en lecture, plus pâle sinon
        const playing = status === 'Playing';
        this._clip.visible = hasTrack;
        this._eq.visible = playing;
        this._pill.set_style_class_name(playing ? 'pb-pill' : 'pb-pill pb-pill-idle');
        if (!playing)
            this._calmPill();
        if (hasTrack && title !== this._lastTitle) {
            this._label.text = title;
            this._popInTitle();
        } else if (hasTrack && this._lastStatus !== status) {
            this._label.ease({opacity: playing ? 255 : 170, duration: 300});
        }
        this._lastTitle = hasTrack ? title : null;
        this._lastStatus = status;

        // menu
        this._title.text = title;
        this._sub.text = artist;
        this._live.visible = live;
        this._progressRow.visible = hasTrack && !live;
        this._playBtn.child.icon_name = playing ? 'media-playback-pause-symbolic' : 'media-playback-start-symbolic';
        this._durLabel.text = fmtTime(length);

        const art = meta['mpris:artUrl'];
        if (typeof art === 'string' && art !== '') {
            if (art !== this._art) {
                this._art = art;
                const px = DISC_SIZE - 16;
                this._disc.set_style_class_name('pb-disc');
                this._disc.style = `background-image: url("${art}"); background-size: ${px}px ${px}px;`;
            }
        } else if (this._art !== null) {
            this._art = null;
            this._disc.set_style_class_name('pb-disc-empty');
            this._disc.style = '';
        }

        const vol = this._proxy.Volume;
        if (typeof vol === 'number') {
            this._settingVolume = true;
            this._volume.value = Math.max(0, Math.min(1, vol));
            this._settingVolume = false;
        }
    }

    _tick() {
        if (this.menu.isOpen)
            this._pollPosition();
        return true;
    }

    _pollPosition() {
        if (!this._proxy || this._dragging)
            return;
        Gio.DBus.session.call(BUS_NAME, OBJ_PATH, 'org.freedesktop.DBus.Properties', 'Get',
            new GLib.Variant('(ss)', [PLAYER_IFACE, 'Position']), new GLib.VariantType('(v)'),
            Gio.DBusCallFlags.NONE, 500, null, (conn, res) => {
                try {
                    const pos = Number(conn.call_finish(res).deepUnpack()[0].deepUnpack());
                    this._posLabel.text = fmtTime(pos);
                    if (this._length > 0 && !this._dragging)
                        this._progress.value = Math.max(0, Math.min(1, pos / this._length));
                } catch (e) {
                    // l'appli a peut-être quitté entre-temps
                }
            });
    }

    _seekTo(fraction) {
        if (!this._proxy || !this._length)
            return;
        this._proxy.SetPositionAsync(this._trackId, Math.floor(fraction * this._length)).catch(logError);
    }

    _onScroll(event) {
        if (!this._proxy)
            return Clutter.EVENT_PROPAGATE;
        const dir = event.get_scroll_direction();
        const step = dir === Clutter.ScrollDirection.UP ? 0.05 : dir === Clutter.ScrollDirection.DOWN ? -0.05 : 0;
        if (step === 0)
            return Clutter.EVENT_PROPAGATE;
        this._proxy.Volume = Math.max(0, Math.min(1, (this._proxy.Volume ?? 1) + step));
        return Clutter.EVENT_STOP;
    }
});

export default class PodcastsBarExtension extends Extension {
    enable() {
        this._indicator = new PodcastsIndicator(this.path);
        Main.panel.addToStatusArea(this.uuid, this._indicator, 0, 'right');
        this._watchId = Gio.bus_watch_name(Gio.BusType.SESSION, BUS_NAME, Gio.BusNameWatcherFlags.NONE,
            () => this._indicator?.attach(),
            () => this._indicator?.detach());
    }

    disable() {
        if (this._watchId) {
            Gio.bus_unwatch_name(this._watchId);
            this._watchId = 0;
        }
        this._indicator?.detach();
        this._indicator?.destroy();
        this._indicator = null;
    }
}
