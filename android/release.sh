#!/bin/bash
# Cut a C1250 Deck release for the over-the-air update channel.
#
#   bash release.sh <versionName> "<what's new>"      e.g. bash release.sh 1.3 "Workout history"
#
# Bumps versionCode in AndroidManifest.xml, builds and signs the APK (build.sh), checks it holds
# nothing private, writes manifest.json + c1250-deck-<version>.apk to the private folder's releases/,
# and publishes both to the update channels: Cloudflare KV, served by the Worker at /app/ and, for
# consoles from before the public channel, /update/ (worker/index.js), so an APK never sits in this
# public repo. Consoles pick it up within 6 hours, or right away from Settings > Check for updates.
#
# The same signing key must sign every release: Android refuses an update signed with another key.
# Publishing uses wrangler's own login (npx wrangler@4 login) or CLOUDFLARE_API_TOKEN if set, and
# CLOUDFLARE_ACCOUNT_ID (from the environment or secrets.env).
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

unset DECK_DEBUG
bash "$APP/build.sh"

# Anyone can download this APK, so stop here if it holds the site key or the account id. grep -c
# reads everything: with pipefail, grep -q stopping early would make a match look like no match.
if [ "$(unzip -p "$APP/c1250-deck.apk" | LC_ALL=C grep -acF 'com.massaro.c1250deck' || true)" = 0 ]; then
  echo "STOP: couldn't read the APK to check it, so nothing was published. The version bump in AndroidManifest.xml is still there."
  exit 1
fi
for secret in "${DECK_KEY:-}" "${CLOUDFLARE_ACCOUNT_ID:-}"; do
  [ -n "$secret" ] || continue
  found=$(unzip -p "$APP/c1250-deck.apk" | LC_ALL=C grep -acF -- "$secret" || true)
  if [ "$found" != 0 ]; then
    echo "STOP: the APK holds something private, so nothing was published. The version bump in AndroidManifest.xml is still there."
    exit 1
  fi
done

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
cd "$APP/.."
put() { npx -y wrangler@4 kv key put "$1" --path "$2" --binding REPORTS --remote >/dev/null; }
if put "pub:$APK" "$OUT/$APK" && put "pub:manifest.json" "$OUT/manifest.json" \
   && put "upd:$APK" "$OUT/$APK" && put "upd:manifest.json" "$OUT/manifest.json"; then
  echo "Published $VNAME to the update channels."
else
  echo "Publishing failed (log in with npx wrangler@4 login, or set CLOUDFLARE_API_TOKEN); the files are in $OUT."
fi
echo "Release $VNAME (versionCode $NEW). Commit the version bump here, and releases/ in the private repo."
cat "$OUT/manifest.json"
