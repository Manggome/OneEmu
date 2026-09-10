#!/usr/bin/env bash
# Create the release signing keystore for OneEmu and show how to upload it to GitHub Actions.
#
#   scripts/make-keystore.sh [path/to/keystore.jks] [alias]
#
# - Prompts for the passwords (they are never written anywhere except keystore.properties, which
#   is gitignored, and the GitHub secrets you upload yourself).
# - Writes keystore.properties next to the project root so local `./gradlew assembleRelease` signs
#   with the same key as CI.
# - Prints the exact `gh secret set` commands for KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS /
#   KEY_PASSWORD. Keep a backup of the .jks: losing it means users must uninstall to update.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE="${1:-$ROOT/keystore.jks}"
ALIAS="${2:-oneemu}"

command -v keytool >/dev/null || { echo "keytool not found (install a JDK, e.g. JDK 17)"; exit 1; }

if [[ -f "$KEYSTORE" ]]; then
	echo "!! $KEYSTORE already exists. Delete it first if you really want a new key (users would need to reinstall)."
	exit 1
fi

read -r -s -p "Keystore password (min 6 chars): " STORE_PASS; echo
read -r -s -p "Confirm keystore password: " STORE_PASS2; echo
[[ "$STORE_PASS" == "$STORE_PASS2" ]] || { echo "passwords differ"; exit 1; }
(( ${#STORE_PASS} >= 6 )) || { echo "password too short"; exit 1; }
read -r -s -p "Key password (Enter = same as keystore): " KEY_PASS; echo
[[ -n "$KEY_PASS" ]] || KEY_PASS="$STORE_PASS"
read -r -p "Your name / org for the certificate (CN) [OneEmu]: " CN
[[ -n "$CN" ]] || CN="OneEmu"

keytool -genkeypair -v \
	-keystore "$KEYSTORE" \
	-storepass "$STORE_PASS" -keypass "$KEY_PASS" \
	-alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
	-dname "CN=$CN, O=$CN, C=KR"

REL="$(python3 -c "import os,sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))" "$KEYSTORE" "$ROOT" 2>/dev/null || basename "$KEYSTORE")"
cat >"$ROOT/keystore.properties" <<PROPS
storeFile=$REL
storePassword=$STORE_PASS
keyAlias=$ALIAS
keyPassword=$KEY_PASS
PROPS
chmod 600 "$ROOT/keystore.properties"

if base64 --help 2>&1 | grep -q -- '-w'; then B64="base64 -w0"; else B64="base64"; fi

cat <<MSG

Created: $KEYSTORE  (alias: $ALIAS)
Wrote:   $ROOT/keystore.properties  (gitignored; used by ./gradlew assembleRelease)

Upload the signing material to GitHub Actions (requires the gh CLI, logged in, in this repo):

  gh secret set KEYSTORE_BASE64  --body "\$($B64 < "$KEYSTORE")"
  gh secret set KEYSTORE_PASSWORD  # paste the keystore password when prompted
  gh secret set KEY_ALIAS          --body "$ALIAS"
  gh secret set KEY_PASSWORD       # paste the key password when prompted

Back up $KEYSTORE somewhere safe. Never commit *.jks or keystore.properties.
MSG
