# Podial Player

**English** · [Français](README.fr.md)

Podcasts and live radio in one app. Free, no account, no tracking.

<p align="center">
  <a href="https://buymeacoffee.com/mickaelb06"><img src="https://img.shields.io/badge/Buy%20me%20a%20coffee-FFDD00?style=for-the-badge&logo=buymeacoffee&logoColor=black" alt="Buy Me a Coffee" height="48"></a>
</p>

<p align="center">
  <img src="docs/screenshots/top-bar-player.png" width="780" alt="Podial with the animated player in the GNOME top bar">
</p>

| Radios by country and city | Podcast page | Sleep timer |
|---|---|---|
| <img src="docs/screenshots/radios.png" width="260"> | <img src="docs/screenshots/podcast-detail.png" width="260"> | <img src="docs/screenshots/sleep-timer.png" width="260"> |

| World map with day and night in real time | Transcript with highlighted quotes |
|---|---|
| <img src="docs/screenshots/world-map.png" width="400"> | <img src="docs/screenshots/transcript.png" width="300"> |

Cross-platform desktop version (Windows, macOS, Linux):

<img src="docs/screenshots/desktop-windows-mac.png" width="520" alt="Podial for Windows, macOS and Linux">

- **Desktop (Linux / GNOME):** a GTK4 + libadwaita app, plus a GNOME Shell extension that puts an animated player in the top bar (spinning vinyl, spectrum, progress and volume).
- **Windows / macOS / Linux (Compose Desktop):** a cross-platform app sharing its network and data code with Android. Audio uses JavaFX Media, so nothing else to install.
- **Android:** a companion app with Android Auto support, built without any Google dependency (F-Droid friendly).

## Features

- Podcasts: top 10 by country and genre, search, subscriptions, offline downloads (manual or automatic, cancellable), cover art kept offline.
- New episodes: a badge on the podcasts you follow, a notification (checked every hour), and a "played" mark on episodes you have finished.
- Live radio from all countries ([radio-browser.info](https://www.radio-browser.info)), browse by country, city or on a world map (tap a country; the map shows day and night in real time; small island states are only in the country list), duplicates removed, favorites.
- Radio pages show the station logo, quality, the title currently playing (when the station sends it) and, for RTL, today's schedule with the current show highlighted (Android). There is no common source for radio schedules, so each station needs its own support.
- Optional YouTube audio on desktop (requires `yt-dlp`, which you install yourself; this may go against YouTube's terms of service, use at your own risk).
- Transcripts and quotes: when a podcast publishes its transcript (Podcasting 2.0, SRT/VTT/JSON), read it while listening, tap a line to jump there and highlight sentences. A "Quote" button also saves the moment you are listening to. "My quotes" lists them with notes, and you can play from there or share them. Works with right-to-left and Asian scripts. Shows without a published transcript have none yet.
- Android home-screen widgets: a compact one (cover and controls) and a vinyl one that spins while playing.
- Sleep timer: in minutes, hours, or at the end of the episode, with a gentle volume fade.
- Equalizer, smooth volume, playback speed, MPRIS controls and media keys on desktop.
- Right-click the GNOME top-bar player to open or quit Podial. "Launch at login" on desktop. An About dialog and an update check (GitHub releases; off by default on Linux; Android warns you once per new version).
- Interface in English, French, Spanish, German, Portuguese, Hebrew and Arabic (right-to-left supported) on desktop; English and French on Android.

Podcast data comes from the public iTunes Search API and Apple RSS charts. RTL's schedule comes from the public page of rtl.fr. Podial is not affiliated with Apple, YouTube or RTL. Nothing is sent to any server of ours: there is none.

## Download

Installers are attached to each [release](https://github.com/Mickaelb06/podial/releases).

| Platform | Files |
|---|---|
| Linux (Debian, Ubuntu, Mint) | [`podial_0.5.14_all.deb`](https://github.com/Mickaelb06/podial/releases/download/v0.5.14/podial_0.5.14_all.deb): GTK app with the GNOME top-bar extension |
| Linux (other distributions) | [`podial-linux-0.5.14.tar.gz`](https://github.com/Mickaelb06/podial/releases/download/v0.5.14/podial-linux-0.5.14.tar.gz) (run `./install.sh`), or the cross-platform `.deb` / `.rpm` |
| Windows | [`Podial-0.5.14.exe`](https://github.com/Mickaelb06/podial/releases/download/v0.5.14/Podial-0.5.14.exe) (or `.msi`) |
| macOS | [`Podial-1.0.14.dmg`](https://github.com/Mickaelb06/podial/releases/download/v0.5.14/Podial-1.0.14.dmg) |
| Android | [`podial-android.apk`](https://github.com/Mickaelb06/podial/releases/download/v0.5.14/podial-android.apk) |

Building from source is explained below. Releases are marked "pre-release" until the Windows, macOS and Android builds have had more testing.

On every desktop version you can turn on "Launch at login" in the settings: Podial then starts in the background (or minimized).

## Desktop (Linux / GNOME)

Requirements: Python 3, GTK 4, libadwaita 1.x, GStreamer (with the `good` and `bad` plugin sets).

```sh
cd desktop
./install.sh      # builds translations, installs icons and the GNOME Shell extension
python3 podial.py
```

Log out and back in once so GNOME Shell loads the extension.

## Cross-platform desktop (Windows, macOS, Linux)

```sh
cd compose-desktop
./gradlew run                              # run from source (JDK 17)
./gradlew packageDistributionForCurrentOS  # installer for the OS you are on (.msi, .dmg, .deb)
```

Installers for each system are built by the GitHub Actions workflow `Desktop builds`. The Windows and macOS builds have not been tested yet; only Linux has.
Known limits: no media keys / system "now playing" integration yet, and a few radio streams (e.g. raw AAC or Ogg) may not play with JavaFX Media.

## Android

Download [`podial-android.apk`](https://github.com/Mickaelb06/podial/releases/download/v0.5.14/podial-android.apk) from the [latest release](https://github.com/Mickaelb06/podial/releases) (allow installs from your browser or file manager when Android asks). Requires Android 8.0 or newer.

```sh
cd android
./gradlew assembleDebug     # needs JDK 17 and the Android SDK
```

For Android Auto with an APK installed outside the Play Store, enable developer mode in the Android Auto app and tick "Unknown sources".

Release signing uses a keystore outside the repository: `~/.podial-signing/keystore.properties` (or the `PODIAL_KEYSTORE_PROPS` variable).

## Status

Early version. The Android app has been tried on one real phone but not yet in a car (Android Auto), and the Windows/macOS desktop builds have not been tested on those systems.

## Roadmap (planned, not built yet)

- Radio schedules for more stations (Radio France and others, one by one).
- Record live radio, with scheduling.
- Local transcription for episodes that publish no transcript, and search inside episodes (runs on your machine).
- Listen to a podcast in another language, for example an English episode in French, through translated subtitles or voice.
- Sync between devices without an account.

## License

MIT, see [LICENSE](LICENSE).
