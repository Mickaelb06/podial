# Podial: guide for Claude Code

Podial = podcasts + live radio in one free app, no account, no tracking. Four apps share one repo.
Owner: Mickael (French speaker; answer in French, keep replies short and plain). GitHub: `Mickaelb06/podial` (MIT).

## Hard rules
- **Never publish anything without the owner's explicit approval**: no `git push`, no tags/releases, no GitHub Pages settings, no Product Hunt, F-Droid, Flathub, extensions.gnome.org. Prepare locally and commit locally; the owner pushes (he uses a classic personal access token with `repo` + `workflow` scopes, typed in his own terminal). Never ask him to paste tokens in chat, never store one.
- **Never put his e-mail anywhere** (code, commits, READMEs, screenshots). Commits use `Mickaelb06 <Mickaelb06@users.noreply.github.com>` (set in the repo config). Public wording uses "Podial contributors", not his name.
- Never commit the signing key: it lives in `~/.podial-signing/` (`podial-release.jks`, `keystore.properties`); `.gitignore` blocks `*.jks`, `keystore.properties`. Never print the password. The owner must back the key up himself.
- Don't claim features that don't exist: the README "Roadmap" lists what is only planned. Windows/macOS/Android builds are "pre-release" (untested on real devices); Linux is the tested one.
- Commit trailer: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.

## Layout
| Path | What |
|---|---|
| `desktop/` | Linux/GNOME app: Python 3.14 + GTK4 + libadwaita + GStreamer, no external Python deps. `podial.py` (UI, player), `catalog.py` (APIs, settings, downloads, new episodes, updates), `filters.py`, `integration.py` (MPRIS, tray, autostart, desktop entry), `i18n.py`, `po/*.po` (en source, fr es de pt he ar), `tools/build_translations.py` (compiles .po→.mo, no gettext tools), `tools/build_deb.sh`, `install.sh`, `extension/podial@podial.app/` (GNOME Shell extension, GNOME 48–50) |
| `compose-desktop/` | Cross-platform desktop (Windows/macOS/Linux): Kotlin + Compose Desktop, audio via JavaFX Media. Packages: dmg, exe, msi, deb, rpm |
| `android/` | Android app: Kotlin + Compose + Media3 (MediaLibraryService → Android Auto), WorkManager hourly new-episode check, no Google dependency (F-Droid friendly) |
| `shared/src/app/podial/` | Kotlin code shared by Android and compose-desktop: `Models.kt`, `Api.kt` (iTunes, RSS via SAX, radio-browser), `Updates.kt`, `Release.kt` (GitHub update check). Included via `srcDir` in both builds |
| `docs/` | GitHub Pages landing page (`index.html`, EN/FR, no external resources) + `screenshots/` |
| `.github/` | `FUNDING.yml` (Buy Me a Coffee: mickaelb06), `workflows/release.yml` (builds all installers, publishes a pre-release on `v*` tags), `workflows/android.yml` |
| `README.md`, `README.fr.md` | Keep both in sync |

The working copy of the Linux app used day to day is `~/podial` (same files as `desktop/`). After editing there, sync: `rsync -a --exclude=__pycache__ --exclude=locale --exclude='*.mo' --exclude=extension/podial@podial.app/locale ~/podial/ ~/podial-repo/desktop/`. (An old prototype `~/podcasts-linux` and `~/podial-android` also exist; they are obsolete. Ask before deleting.)

## Features done
Podcasts (Top 10 by country/genre, search, subscribe), radios by country + city (de-duplicated), YouTube audio (desktop, optional, via yt-dlp), offline downloads with cancel, automatic downloads (Linux, off by default), covers kept offline, sleep timer (minutes/hours/end of episode, fade-out), played-episode tracking, new-episode badges + hourly check + notifications, About dialog, update check (GitHub latest release; opt-in on Linux), preferences dialog (menu ≡), launch at login (all desktop versions), right-click menu on the top-bar pill (open/quit), animated GNOME top-bar player, 7 UI languages incl. RTL on Linux, Android Auto browse tree, redesigned Android radio page/player (now playing from ICY metadata), RTL schedule on Android (`shared/.../Programs.kt`, scraped from rtl.fr; no common source for other stations).

## Not done (ideas the owner liked)
Radio recording with scheduling; local transcription + search in episodes; translated subtitles for foreign-language podcasts (Whisper + translation locally, or optional Gemini API with the user's own key, which would send audio to Google); sync between devices without an account; world map for radios; Android/desktop translations beyond en/fr; real icons for installers; trademark/domain check for the name "Podial"; test Windows/macOS/Android on real devices and Android Auto (or the Desktop Head Unit); first release (`git tag v0.5.0 && git push origin v0.5.0`); GitHub Pages (Settings → Pages → `main` / `/docs`); GitHub Sponsors was abandoned as too complex; Buy Me a Coffee is used. Translate the French code comments to English before wider promotion. Product Hunt launch kit is in `~/podial-launch/product-hunt.md` (not in the repo).
Versions: all apps are 0.5.0 (`catalog.VERSION`, android `versionName`, compose-desktop `APP_VERSION`/`packageVersion`); the macOS package alone uses 1.0.0 because jpackage requires a major version >= 1.

## How to build and test
- Linux app: `cd ~/podial && ./install.sh` (builds translations, installs the extension atomically) then `python3 podial.py`. `--background` starts without a window.
- **Tool/OS pitfalls on this machine (7 GB RAM)**: run Gradle in the background and poll (`setsid nohup … &`), never two builds at once, `--no-daemon`; a foreground build can get the shell OOM-killed (exit 137). Gradle: `~/Android/gradle-8.10.2/bin/gradle` or each project's `./gradlew`, `ANDROID_HOME=~/Android`, JDK 17 (sdkman).
- Android: `cd android && ANDROID_HOME=~/Android ./gradlew :app:assembleDebug` (or `assembleRelease`: signs with `~/.podial-signing/keystore.properties`).
- compose-desktop: `./gradlew :compileKotlin`, `./gradlew run`. Headless run for screenshots: `xvfb-run -s "-screen 0 1280x800x24 -fbdir DIR" env SKIKO_RENDER_API=SOFTWARE ./gradlew run`, then convert `DIR/Xvfb_screen0` (XWD) with a small PIL script.
- GNOME screenshots: run an isolated headless shell: `dbus-run-session -- gnome-shell --headless --wayland --wayland-display wayland-pbtest --virtual-monitor 1366x768 --no-x11 --unsafe-mode`, drive the app with a Python script (GLib timeouts calling Window methods), screenshot via `org.gnome.Shell.Screenshot`, clear notifications first with `Main.messageTray.getSources().forEach(s => s.destroy())` through `org.gnome.Shell.Eval` (a real notification shows the owner's e-mail). Use `XDG_DATA_HOME=<temp>` so tests don't touch the real library. Screenshots of the real session are denied.
- Landing page preview: `google-chrome --headless=new --no-sandbox --screenshot=out.png --window-size=1280,3900 file://…/docs/index.html`.
- `pkill -f 'podial[.]py$'` kills the app; a plain `pkill -f podial.py` inside a Bash command kills the Bash call itself (exit 144), so use the bracket trick and keep it in its own call.
- Never overwrite `.mo` files in place while GNOME Shell runs (it mmaps them and shows garbage); copy to a temp name then `mv` (install.sh and build_translations.py already do).
- New UI texts: add the msgid to every `po/*.po` and `po/podial.pot`, run `tools/build_translations.py`.

## Resuming after a PC restart
1. On login GNOME loads the `podial@podial.app` extension (it is in `enabled-extensions`); the top-bar pill appears when Podial plays. This also fixes the garbled texts seen before the restart.
2. Start the app: `cd ~/podial && python3 podial.py` (the old env vars `PODIAL_EXTRA_MPRIS_NAMES` / `PODIAL_NO_TRAY` were only a workaround for the previous extension; no longer needed). Or enable "Launch at login" in Preferences.
3. Open Claude Code in `~/podial-repo` so this file is read. `git status` / `git log origin/main..` shows what is not pushed yet.
4. Background Gradle/Xvfb/Chrome processes from earlier sessions disappear on reboot; nothing else to clean up.
