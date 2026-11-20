#!/usr/bin/env bash
# Creates the SendAhead release (upload) keystore on THIS machine and stores the
# signing secrets in GitHub Actions. Nothing secret is written to the repo.
#
#   ./scripts/create-release-keystore.sh [output-dir]
#
# Output dir defaults to ~/Documents/SendAhead-signing.
# Needs: keytool (any JDK), openssl, and optionally gh (logged in) to set the secrets.
set -euo pipefail

REPO="${REPO:-yogeshpaliyal/SendAhead}"
DIR="${1:-$HOME/Documents/SendAhead-signing}"
ALIAS="sendahead"
KS="$DIR/sendahead-release.jks"
PROPS="$DIR/keystore.properties"

command -v keytool >/dev/null || { echo "keytool not found. Install a JDK (e.g. 'brew install openjdk@17')."; exit 1; }
command -v openssl >/dev/null || { echo "openssl not found."; exit 1; }

if [ -e "$KS" ]; then
  echo "A keystore already exists at $KS. Refusing to overwrite it: replacing it breaks app updates."
  exit 1
fi

mkdir -p "$DIR"
chmod 700 "$DIR"

# PKCS12 keystores use one password for both the store and the key.
PASS="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-32)"

keytool -genkeypair \
  -storetype PKCS12 \
  -keystore "$KS" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" \
  -dname "CN=Yogesh Paliyal, O=SendAhead, C=IN" >/dev/null 2>&1
chmod 600 "$KS"

cat > "$PROPS" <<EOF
# SendAhead release signing. Keep this file and the .jks together, private and backed up.
# For local release builds, copy this file to the repo root as keystore.properties (it is git-ignored).
storeFile=$KS
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
EOF
chmod 600 "$PROPS"

keytool -list -v -keystore "$KS" -storepass "$PASS" -alias "$ALIAS" 2>/dev/null \
  | grep -E "SHA1:|SHA256:" > "$DIR/fingerprints.txt" || true

echo "Created:"
echo "  $KS"
echo "  $PROPS   (passwords)"
echo "  $DIR/fingerprints.txt"

if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
  echo "Uploading GitHub Actions secrets to $REPO ..."
  base64 < "$KS" | tr -d '\n' | gh secret set SIGNING_KEYSTORE_BASE64 -R "$REPO"
  printf '%s' "$PASS"  | gh secret set SIGNING_STORE_PASSWORD -R "$REPO"
  printf '%s' "$ALIAS" | gh secret set SIGNING_KEY_ALIAS -R "$REPO"
  printf '%s' "$PASS"  | gh secret set SIGNING_KEY_PASSWORD -R "$REPO"
  echo "Secrets set: SIGNING_KEYSTORE_BASE64, SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD"
else
  echo
  echo "gh is not installed or not logged in, so the secrets were NOT uploaded."
  echo "Add them in GitHub: Settings > Secrets and variables > Actions > New repository secret:"
  echo "  SIGNING_KEYSTORE_BASE64  = output of: base64 < \"$KS\" | tr -d '\\n'"
  echo "  SIGNING_STORE_PASSWORD   = storePassword from $PROPS"
  echo "  SIGNING_KEY_ALIAS        = $ALIAS"
  echo "  SIGNING_KEY_PASSWORD     = keyPassword from $PROPS"
fi

echo
echo "IMPORTANT: back up $DIR to a password manager or an encrypted drive now."
echo "If this key is lost, you can't ship updates to existing installs."
