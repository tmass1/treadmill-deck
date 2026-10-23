#!/bin/bash
# Cut a C1250 Deck release for the over-the-air update channel.
#
#   bash release.sh <versionName> "<what's new>"      e.g. bash release.sh 1.3 "Workout history"
#
# Bumps versionCode in AndroidManifest.xml, builds and signs the APK (build.sh), writes
# manifest.json + c1250-deck-<version>.apk to the private folder's releases/, and publishes both
# to the update channel: Cloudflare KV, served by the Worker at /update/ (worker/index.js), so an
# APK never sits in this public repo. Consoles pick it up within 6 hours, or right away from
# Settings > Check for updates.
#
# The same signing key must sign every release: Android refuses an update signed with another key.
# Publishing needs CLOUDFLARE_API_TOKEN and CLOUDFLARE_ACCOUNT_ID (from the environment or secrets.env).
set -euo pipefail

APP="$(cd "$(dirname "$0")" && pwd)"
VNAME="${1:?Usage: bash release.sh <versionName> \"<notes>\"}"
NOTES="${2:-}"
M="$APP/AndroidManifest.xml"
PRIV="${DECK_PRIVATE:-$APP/../../treadmill-deck-private}"
if [ -f "$PRIV/secrets.env" ]; then set -a; . "$PRIV/secrets.env"; set +a; fi
KS="${DECK_KEYSTORE:-$PRIV/keystore/c1250deck.jks}"

[ -f "$KS" ] || { echo "The signing key isn't at $KS; updates must be signed with the original key."; exit 1; }

OLD=$(grep -o 'android:versionCode="[0-9]*"' "$M" | grep -o '[0-9]*')
NEW=$((OLD + 1))
sed -i.bak -E "s/android:versionCode=\"[0-9]+\"/android:versionCode=\"$NEW\"/; s/android:versionName=\"[^\"]+\"/android:versionName=\"$VNAME\"/" "$M" && rm -f "$M.bak"

bash "$APP/build.sh"

OUT="$PRIV/releases"
mkdir -p "$OUT"
APK="c1250-deck-$VNAME.apk"
cp "$APP/c1250-deck.apk" "$OUT/$APK"
cp "$APP/c1250-deck.apk" "$APP/../installer/c1250-deck.apk"
SHA=$( (sha256sum "$OUT/$APK" 2>/dev/null || shasum -a 256 "$OUT/$APK") | cut -d' ' -f1)
SIZE=$(wc -c < "$OUT/$APK" | tr -d ' ')
python3 - "$OUT/manifest.json" "$NEW" "$VNAME" "$APK" "$SHA" "$SIZE" "$NOTES" <<'PY'
import json, sys
out, code, name, apk, sha, size, notes = sys.argv[1:]
json.dump({"versionCode": int(code), "versionName": name, "apk": apk, "sha256": sha,
           "size": int(size), "notes": notes}, open(out, "w"), indent=2)
PY
echo
if [ -n "${CLOUDFLARE_API_TOKEN:-}" ]; then
  cd "$APP/.."
  if npx -y wrangler@4 kv key put "upd:$APK" --path "$OUT/$APK" --binding REPORTS --remote >/dev/null \
     && npx -y wrangler@4 kv key put "upd:manifest.json" --path "$OUT/manifest.json" --binding REPORTS --remote >/dev/null; then
    echo "Published $VNAME to the update channel."
  else
    echo "Publishing failed; the files are in $OUT."
  fi
else
  echo "Set CLOUDFLARE_API_TOKEN and CLOUDFLARE_ACCOUNT_ID to publish; the files are in $OUT."
fi
echo "Release $VNAME (versionCode $NEW). Commit the version bump here, and releases/ in the private repo."
cat "$OUT/manifest.json"
