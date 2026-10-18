# Podial

[English](README.md) · **Français**

Podcasts et radios en direct dans une seule application. Gratuit, sans compte, sans pistage.

<p align="center">
  <a href="https://buymeacoffee.com/mickaelb06"><img src="https://img.shields.io/badge/Offre-moi%20un%20café-FFDD00?style=for-the-badge&logo=buymeacoffee&logoColor=black" alt="Buy Me a Coffee" height="48"></a>
</p>

<p align="center">
  <img src="docs/screenshots/top-bar-player.png" width="780" alt="Podial avec le lecteur animé dans la barre du haut de GNOME">
</p>

| Radios par pays et par ville | Page d'un podcast | Minuteur de sommeil |
|---|---|---|
| <img src="docs/screenshots/radios.png" width="260"> | <img src="docs/screenshots/podcast-detail.png" width="260"> | <img src="docs/screenshots/sleep-timer.png" width="260"> |

Version de bureau multiplateforme (Windows, macOS, Linux) :

<img src="docs/screenshots/desktop-windows-mac.png" width="520" alt="Podial pour Windows, macOS et Linux">

- **Bureau Linux / GNOME :** une application GTK4 + libadwaita, avec une extension GNOME qui place un lecteur animé dans la barre du haut (vinyle qui tourne, spectre, progression et volume).
- **Windows / macOS / Linux (Compose Desktop) :** une application multiplateforme qui partage son code réseau et ses données avec Android. L'audio passe par JavaFX Media : rien d'autre à installer.
- **Android :** une application compagnon avec Android Auto, sans aucune dépendance Google (compatible F-Droid).

## Fonctionnalités

- Podcasts : Top 10 par pays et par genre, recherche, abonnements, téléchargements hors ligne (manuels ou automatiques), pochettes conservées hors ligne.
- Radios en direct du monde entier ([radio-browser.info](https://www.radio-browser.info)), parcourues par pays et par ville, doublons supprimés, favoris.
- Audio YouTube en option sur le bureau (nécessite `yt-dlp`, que tu installes toi-même ; cela peut aller à l'encontre des conditions d'utilisation de YouTube, à tes risques).
- Minuteur de sommeil : en minutes, en heures ou à la fin de l'épisode, avec une baisse douce du volume.
- Égaliseur, volume lissé, vitesse de lecture, commandes MPRIS et touches multimédia sur le bureau Linux.
- Interface en anglais, français, espagnol, allemand, portugais, hébreu et arabe (écriture de droite à gauche gérée) sur le bureau Linux ; anglais et français sur Android.

Les données des podcasts viennent de l'API publique iTunes Search et des classements RSS d'Apple. Rien n'est envoyé à un serveur qui nous appartient : il n'y en a pas.

## Téléchargement

Les installateurs sont joints à chaque [release](https://github.com/Mickaelb06/podial/releases/latest).

| Plateforme | Fichiers |
|---|---|
| Linux (Debian, Ubuntu, Mint) | `podial_*_all.deb` : application GTK avec l'extension GNOME |
| Linux (autres distributions) | `podial-linux-*.tar.gz` (lance `./install.sh`), ou les `.deb` / `.rpm` multiplateformes |
| Windows | installateur `.exe` (ou `.msi`) |
| macOS | `.dmg` |
| Android | `podial-android.apk` |

La compilation depuis les sources est expliquée plus bas. Les releases sont marquées « pre-release » tant que les versions Windows, macOS et Android n'ont pas été davantage testées.

Sur toutes les versions de bureau, tu peux activer « Lancer à l'ouverture de session » dans les réglages : Podial démarre alors en arrière-plan (ou réduit).

## Bureau Linux / GNOME

Prérequis : Python 3, GTK 4, libadwaita 1.x, GStreamer (avec les greffons `good` et `bad`).

```sh
cd desktop
./install.sh      # compile les traductions, installe les icônes et l'extension GNOME
python3 podial.py
```

Déconnecte-toi puis reconnecte-toi une fois pour que GNOME Shell charge l'extension.

## Bureau multiplateforme (Windows, macOS, Linux)

```sh
cd compose-desktop
./gradlew run                              # lancer depuis les sources (JDK 17)
./gradlew packageDistributionForCurrentOS  # installateur du système utilisé (.msi, .dmg, .deb)
```

Les installateurs de chaque système sont construits par le workflow GitHub Actions « Desktop builds ». Les versions Windows et macOS n'ont pas encore été testées ; seule la version Linux l'a été.
Limites connues : pas encore de touches multimédia ni d'intégration « en lecture » du système, et quelques flux de radio (AAC brut, Ogg) peuvent ne pas se lire avec JavaFX Media.

## Android

```sh
cd android
./gradlew assembleDebug     # JDK 17 et SDK Android nécessaires
```

Pour Android Auto avec un APK installé hors du Play Store, active le mode développeur dans l'application Android Auto et coche « Sources inconnues ».

La signature de la version finale utilise un keystore hors du dépôt : `~/.podial-signing/keystore.properties` (ou la variable `PODIAL_KEYSTORE_PROPS`).

## État du projet

Version précoce. L'application Android n'a pas encore été testée sur un vrai appareil ni dans une voiture, et les versions de bureau Windows et macOS n'ont pas été testées sur ces systèmes.

## Feuille de route (prévu, pas encore développé)

- Enregistrer les radios en direct, avec programmation.
- Transcription locale et recherche dans les épisodes (tout se passe sur ton ordinateur).
- Écouter un podcast dans une autre langue, par exemple un épisode anglais en français, grâce à des sous-titres ou une voix traduits.
- Synchronisation entre appareils sans compte.
- Carte du monde pour découvrir les radios en cliquant sur un pays.

## Licence

MIT, voir [LICENSE](LICENSE).
