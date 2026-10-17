#!/usr/bin/env bash
# Installe Podial pour l'utilisateur courant (sans sudo) : traductions, extension GNOME, lanceur.
set -euo pipefail
cd "$(dirname "$0")"
UUID="podial@podial.app"
DATA="${XDG_DATA_HOME:-$HOME/.local/share}"

python3 tools/build_translations.py
mkdir -p "extension/$UUID/icons" "$DATA/gnome-shell/extensions/$UUID"
cp icons/hicolor/symbolic/apps/podial-radio-symbolic.svg "extension/$UUID/icons/"
cp -r "extension/$UUID/." "$DATA/gnome-shell/extensions/$UUID/"

# active l'extension (GNOME ne la chargera qu'à la prochaine ouverture de session sous Wayland)
if command -v gsettings >/dev/null && gsettings get org.gnome.shell enabled-extensions >/dev/null 2>&1; then
  python3 - "$UUID" <<'PY'
import ast, subprocess, sys
uuid = sys.argv[1]
cur = ast.literal_eval(subprocess.check_output(["gsettings", "get", "org.gnome.shell", "enabled-extensions"], text=True))
if uuid not in cur:
    cur.append(uuid)
    subprocess.check_call(["gsettings", "set", "org.gnome.shell", "enabled-extensions", str(cur)])
PY
fi

# le lanceur du menu Applications est créé au premier démarrage de l'application
echo "Podial installé. Lancez-le avec : python3 $(pwd)/podial.py"
echo "Sous GNOME, déconnectez-vous puis reconnectez-vous pour voir l'extension dans la barre du haut."
