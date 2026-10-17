"""Traductions de Podial (gettext standard, fichiers po/*.po compilés par tools/build_translations.py).

La langue vient de l'environnement (LANGUAGE, LC_ALL, LC_MESSAGES, LANG). Les textes d'origine sont en anglais :
si aucune traduction n'existe, l'anglais s'affiche."""
import gettext
import os

DOMAIN = "podial"
_HERE = os.path.dirname(os.path.abspath(__file__))
_DIRS = [os.path.join(_HERE, "locale"), os.path.expanduser("~/.local/share/locale"), "/usr/local/share/locale",
         "/usr/share/locale"]


def _load():
    for d in _DIRS:
        try:
            return gettext.translation(DOMAIN, d)  # langues déduites de l'environnement
        except OSError:
            continue
    return gettext.NullTranslations()


_t = _load()
_ = _t.gettext
ngettext = _t.ngettext
