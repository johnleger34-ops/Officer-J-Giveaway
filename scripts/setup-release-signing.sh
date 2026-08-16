#!/usr/bin/env bash
set -euo pipefail

# One-time setup for permanent Android release signing.
# Run from the repository root inside GitHub Codespaces.

if ! command -v gh >/dev/null 2>&1; then
  echo "GitHub CLI (gh) is required in this Codespace."
  exit 1
fi
if ! gh auth status >/dev/null 2>&1; then
  echo "GitHub CLI is not authenticated. Run: gh auth login"
  exit 1
fi
if ! command -v keytool >/dev/null 2>&1; then
  echo "keytool is required. The Codespace Java setup should provide it."
  exit 1
fi

BACKUP_DIR="${PWD}/signing-backup"
KEYSTORE="${BACKUP_DIR}/officer-j-release.jks"
CREDS="${BACKUP_DIR}/SIGNING-CREDENTIALS.txt"
ALIAS="officerj-release"
mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

if [[ -f "$KEYSTORE" ]]; then
  echo "Refusing to overwrite existing permanent signing key: $KEYSTORE"
  echo "If this key has already signed a release, KEEP IT. Never generate a replacement."
  exit 1
fi

STORE_PASS="$(openssl rand -base64 36 | tr -d '\n/=+' | cut -c1-32)"
KEY_PASS="$(openssl rand -base64 36 | tr -d '\n/=+' | cut -c1-32)"

keytool -genkeypair \
  -keystore "$KEYSTORE" \
  -storepass "$STORE_PASS" \
  -keypass "$KEY_PASS" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=Officer J Auto Spa, OU=Android, O=Officer J Auto Spa, L=Church Point, ST=Louisiana, C=US"

{
  echo "OFFICER J AUTO SPA - PERMANENT ANDROID SIGNING BACKUP"
  echo "Keep this file and officer-j-release.jks together somewhere safe."
  echo "Do NOT commit either file to GitHub. Losing this key prevents future APKs from updating the installed release app."
  echo
  echo "Key alias: $ALIAS"
  echo "Keystore password: $STORE_PASS"
  echo "Key password: $KEY_PASS"
} > "$CREDS"
chmod 600 "$KEYSTORE" "$CREDS"

BASE64_KEYSTORE="$(base64 -w 0 "$KEYSTORE")"
printf '%s' "$BASE64_KEYSTORE" | gh secret set RELEASE_KEYSTORE_BASE64
printf '%s' "$STORE_PASS" | gh secret set RELEASE_KEYSTORE_PASSWORD
printf '%s' "$ALIAS" | gh secret set RELEASE_KEY_ALIAS
printf '%s' "$KEY_PASS" | gh secret set RELEASE_KEY_PASSWORD

cat <<MSG

Release signing is configured in GitHub Secrets.

IMPORTANT: Download and safely keep BOTH files in:
  signing-backup/officer-j-release.jks
  signing-backup/SIGNING-CREDENTIALS.txt

The signing-backup folder is ignored by Git and must NEVER be committed.
After you have a safe backup, future release builds can update the app in place.
MSG
