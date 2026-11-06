#!/usr/bin/env bash
# Downloads the three SIL-OFL Arabic fonts into app/src/main/assets/fonts.
# If a download fails the app still builds and falls back to Android's system Arabic font.
set -u
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/fonts"
BASE="https://github.com/google/fonts/raw/main/ofl"
mkdir -p "$DEST"
get() { # folder file
  if [ -s "$DEST/$2" ]; then echo "have $2"; return; fi
  curl -fL --retry 3 -o "$DEST/$2" "$BASE/$1/$2" && echo "ok   $2" || { echo "WARN could not fetch $2"; rm -f "$DEST/$2"; }
}
get amiri Amiri-Regular.ttf
get amiri Amiri-Bold.ttf
get ibmplexsansarabic IBMPlexSansArabic-Regular.ttf
get ibmplexsansarabic IBMPlexSansArabic-Bold.ttf
get scheherazadenew ScheherazadeNew-Regular.ttf
get scheherazadenew ScheherazadeNew-Bold.ttf
