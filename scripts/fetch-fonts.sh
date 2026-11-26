#!/usr/bin/env sh
# Downloads the exact font binaries bundled with the app (SHA-256 verified
# against the originals). Binary files are not committed to this repository,
# so CI and fresh clones fetch them here once.
set -eu
FONT_DIR="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/res/font"
mkdir -p "$FONT_DIR"

verify() { # file expected-sha256
  if command -v sha256sum >/dev/null 2>&1; then
    got="$(sha256sum "$1" | cut -d' ' -f1)"
    if [ "$got" != "$2" ]; then
      echo "ERROR: SHA-256 mismatch for $1" >&2
      echo "  got:  $got" >&2
      echo "  want: $2" >&2
      exit 1
    fi
  fi
}

# --- Inter (SIL OFL 1.1): static instances from the official rsms/inter v4.1 release ---
if [ ! -s "$FONT_DIR/inter_regular.ttf" ] || [ ! -s "$FONT_DIR/inter_medium.ttf" ] \
   || [ ! -s "$FONT_DIR/inter_semibold.ttf" ] || [ ! -s "$FONT_DIR/inter_bold.ttf" ]; then
  echo "Fetching Inter 4.1 ..."
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT
  curl -fsSL --retry 3 -o "$TMP/inter.zip" \
    "https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip"
  unzip -j -q "$TMP/inter.zip" \
    "extras/ttf/Inter-Regular.ttf" "extras/ttf/Inter-Medium.ttf" \
    "extras/ttf/Inter-SemiBold.ttf" "extras/ttf/Inter-Bold.ttf" \
    -d "$TMP/inter"
  mv "$TMP/inter/Inter-Regular.ttf"  "$FONT_DIR/inter_regular.ttf"
  mv "$TMP/inter/Inter-Medium.ttf"   "$FONT_DIR/inter_medium.ttf"
  mv "$TMP/inter/Inter-SemiBold.ttf" "$FONT_DIR/inter_semibold.ttf"
  mv "$TMP/inter/Inter-Bold.ttf"     "$FONT_DIR/inter_bold.ttf"
fi

# --- Roboto Mono (Apache 2.0 / OFL): googlefonts/RobotoMono repository ---
MONO_BASE="https://raw.githubusercontent.com/googlefonts/RobotoMono/main/fonts/ttf"
for w in Regular Medium Bold; do
  out="$FONT_DIR/roboto_mono_$(echo "$w" | tr 'A-Z' 'a-z').ttf"
  if [ ! -s "$out" ]; then
    echo "Fetching RobotoMono-$w ..."
    curl -fsSL --retry 3 -o "$out" "$MONO_BASE/RobotoMono-$w.ttf"
  fi
done

# --- SHA-256 verification (byte-identical to the bundled originals) ---
verify "$FONT_DIR/inter_regular.ttf"       40d692fce188e4471e2b3cba937be967878f631ad3ebbbdcd587687c7ebe0c82
verify "$FONT_DIR/inter_medium.ttf"        97ad806f526e41546d46365bb3a393145f75b7b1568913db74549ad8b8dba872
verify "$FONT_DIR/inter_semibold.ttf"      78a843fade9d4612a5567302fb595b56976eb5fcebf4fea5a5912d638bafcde3
verify "$FONT_DIR/inter_bold.ttf"          288316099b1e0a47a4716d159098005eef7c0066921f34e3200393dbdb01947f
verify "$FONT_DIR/roboto_mono_regular.ttf" af0bff7599c3df3831755c16e39b3c496df74b8c8d8a1161b14dc8461be17cb4
verify "$FONT_DIR/roboto_mono_medium.ttf"  4acbb2725a260eafff1bcaade3501204ca774e56346e259ee1b61d56996d2611
verify "$FONT_DIR/roboto_mono_bold.ttf"    3ecf35e5e87accc7578b605d1f5f0bc30d88b195d6807bec8a0c57f6aa95c4db

echo "Fonts ready in $FONT_DIR"
