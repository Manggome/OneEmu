#!/usr/bin/env bash
# Downloads the General MIDI SoundFont the WIPI core uses for background music into cores/wipi/assets/
# (packaged into the APK as a core asset, installed to <system>/wipi/). Pinned to a commit and checked
# against the git blob hash, so the file can't change under us. Safe to run repeatedly.
#
# GeneralUser GS v2.0.3 by S. Christian Collins — free to use and redistribute, see
# cores/wipi/assets/LICENSE-GeneralUser-GS.txt.
set -euo pipefail

REPO="mrbumpy409/GeneralUser-GS"
COMMIT="684543d5e5efaef08d02be50dcda8d552478fa60"
BLOB_SHA="298b552d2e9d1307e03e5c5c99d2c046aaed9ec3"   # git blob hash of GeneralUser-GS.sf2 at COMMIT

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$SCRIPT_DIR/assets/GeneralUser-GS.sf2"

if [[ -f "$OUT" && "$(git hash-object "$OUT")" == "$BLOB_SHA" ]]; then
	echo "[wipi] SoundFont already present"
	exit 0
fi

mkdir -p "$(dirname "$OUT")"
TMP="$OUT.part"
echo "[wipi] downloading GeneralUser GS SoundFont"
curl -fL --retry 3 -o "$TMP" "https://raw.githubusercontent.com/$REPO/$COMMIT/GeneralUser-GS.sf2"
GOT="$(git hash-object "$TMP")"
if [[ "$GOT" != "$BLOB_SHA" ]]; then
	rm -f "$TMP"
	echo "[wipi] ERROR: SoundFont hash mismatch ($GOT)" >&2
	exit 1
fi
mv "$TMP" "$OUT"
echo "[wipi] SoundFont OK -> $OUT"
