#!/usr/bin/env bash
# Construit un paquet .deb (Debian, Ubuntu, Mint…) et une archive .tar.gz générique pour Podial (version GTK / GNOME).
# Usage : tools/build_deb.sh [dossier_de_sortie]
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$(realpath -m "${1:-dist}")"
VERSION="$(python3 -c 'import catalog; print(catalog.VERSION)')"
UUID="podial@podial.app"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

python3 tools/build_translations.py >/dev/null
mkdir -p "$OUT"

# ----- .deb -----
ROOT="$STAGE/podial_${VERSION}_all"
LIB="$ROOT/usr/lib/podial"
mkdir -p "$LIB" "$ROOT/usr/bin" "$ROOT/usr/share/applications" "$ROOT/usr/share/doc/podial" \
         "$ROOT/usr/share/gnome-shell/extensions/$UUID" "$ROOT/usr/share/icons/hicolor/symbolic/apps" "$ROOT/DEBIAN"
cp podial.py catalog.py filters.py i18n.py integration.py worldmap.py world.json "$LIB/"
cp -r icons locale "$LIB/"
cp -r "extension/$UUID/." "$ROOT/usr/share/gnome-shell/extensions/$UUID/"
cp icons/hicolor/symbolic/apps/*.svg "$ROOT/usr/share/icons/hicolor/symbolic/apps/"
printf '#!/bin/sh\nexec python3 /usr/lib/podial/podial.py "$@"\n' > "$ROOT/usr/bin/podial"
chmod 755 "$ROOT/usr/bin/podial"
cat > "$ROOT/usr/share/applications/io.github.podial.Podial.desktop" <<DESK
[Desktop Entry]
Type=Application
Name=Podial Player
Comment=Podcasts and live radio
Comment[fr]=Podcasts et radios en direct
Exec=podial
Icon=audio-headphones
Terminal=false
Categories=AudioVideo;Audio;Player;
DESK
cat > "$ROOT/usr/share/doc/podial/copyright" <<COPY
Podial, MIT License. Copyright (c) 2026 Podial contributors.
Source and full license text: https://github.com/Mickaelb06/podial
COPY
SIZE=$(du -sk "$ROOT" | cut -f1)
cat > "$ROOT/DEBIAN/control" <<CTRL
Package: podial
Version: $VERSION
Section: sound
Priority: optional
Architecture: all
Maintainer: Podial contributors <noreply@users.noreply.github.com>
Installed-Size: $SIZE
Depends: python3 (>= 3.10), python3-gi, gir1.2-gtk-4.0, gir1.2-adw-1, gir1.2-gstreamer-1.0, gstreamer1.0-plugins-base, gstreamer1.0-plugins-good
Recommends: gstreamer1.0-plugins-bad, gstreamer1.0-libav, gstreamer1.0-pipewire | gstreamer1.0-pulseaudio
Suggests: yt-dlp
Homepage: https://github.com/Mickaelb06/podial
Description: Podcasts and live radio in one free app
 Podial plays podcasts and live radio stations from every country, with
 offline downloads, a sleep timer and an animated GNOME top-bar player.
 No account, no tracking.
CTRL
find "$ROOT" -type d -exec chmod 755 {} +
find "$ROOT/usr" -type f ! -path '*/bin/*' -exec chmod 644 {} +
dpkg-deb --root-owner-group -Zxz --build "$ROOT" "$OUT/podial_${VERSION}_all.deb" >/dev/null
echo "→ $OUT/podial_${VERSION}_all.deb"

# ----- archive générique (autres distributions) -----
TAR="$STAGE/podial-$VERSION"
mkdir -p "$TAR"
cp -r podial.py catalog.py filters.py i18n.py integration.py worldmap.py world.json install.sh icons extension po tools "$TAR/"
tar -C "$STAGE" -czf "$OUT/podial-linux-${VERSION}.tar.gz" "podial-$VERSION"
echo "→ $OUT/podial-linux-${VERSION}.tar.gz"
