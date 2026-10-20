#!/bin/sh
# Copie shared/data/podcast_sources.json (source de vérité : genres Apple, radios et éditeurs) là où chaque appli le lit.
# Un test unitaire Android (SourcesTest) vérifie que les copies sont identiques.
set -e
cd "$(dirname "$0")/../.."
cp shared/data/podcast_sources.json docs/app/podcast_sources.json
cp shared/data/podcast_sources.json desktop/podcast_sources.json
[ -d "$HOME/podial" ] && cp shared/data/podcast_sources.json "$HOME/podial/podcast_sources.json"
echo "podcast_sources.json copié (docs/app, desktop, ~/podial)"
