#!/bin/sh
# Copie shared/data/podcast_sources.json (source de vérité : genres Apple, radios et éditeurs) là où chaque appli le lit.
# Un test unitaire Android (SourcesTest) vérifie que les copies sont identiques.
set -e
cd "$(dirname "$0")/../.."
cp shared/data/podcast_sources.json docs/app/podcast_sources.json
cp shared/data/podcast_sources.json desktop/podcast_sources.json
[ -d "$HOME/podial" ] && cp shared/data/podcast_sources.json "$HOME/podial/podcast_sources.json"
cp shared/data/radio_catalog.json docs/app/radio_catalog.json
cp shared/data/radio_catalog.json desktop/radio_catalog.json
[ -d "$HOME/podial" ] && cp shared/data/radio_catalog.json "$HOME/podial/radio_catalog.json"
cp shared/data/radio_logos.json docs/app/radio_logos.json
cp shared/data/radio_logos.json desktop/radio_logos.json
[ -d "$HOME/podial" ] && cp shared/data/radio_logos.json "$HOME/podial/radio_logos.json"
echo "podcast_sources.json + radio_logos.json + radio_catalog.json copié (docs/app, desktop, ~/podial)"
