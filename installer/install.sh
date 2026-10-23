#!/bin/bash
# Install C1250 Deck on a NordicTrack treadmill console.
#
# Run from a Mac or Linux computer on the same Wi-Fi as the treadmill, with this script
# and c1250-deck.apk in the same folder:
#
#   bash install.sh <TREADMILL_IP> [PAIRING_PORT PAIRING_CODE]
#
# The first time, pass the pairing port and code shown on the treadmill under
# Settings > Developer options > Wireless debugging > Pair device with pairing code.
#
# What it does (it asks before each change to the treadmill):
#   1. Connects to the treadmill over ADB
#   2. Copies the TLS certificates from the treadmill's own GlassOS motor service
#   3. Optionally switches off iFit's lock-out app and removes the iFit home screen
#   4. Installs C1250 Deck, gives it the certificates, and optionally makes it the home screen
#
# Undo: the factory-reset pinhole on the console restores the stock iFit software.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APK="$HERE/c1250-deck.apk"
PKG="com.massaro.c1250deck"
IP="${1:-}"
PAIR_PORT="${2:-}"
PAIR_CODE="${3:-}"

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
ask()  { local a; read -r -p "$1 [y/N] " a; [[ "$a" =~ ^[Yy]$ ]]; }
die()  { printf '\n\033[31m%s\033[0m\n' "$*"; exit 1; }

[ -n "$IP" ] || die "Usage: bash install.sh <TREADMILL_IP> [PAIRING_PORT PAIRING_CODE]"
[ -f "$APK" ] || die "c1250-deck.apk must be in the same folder as this script."
if ! command -v adb >/dev/null; then
  die "adb isn't installed. On a Mac: brew install android-platform-tools   (Linux: apt install adb)"
fi

say "1/4  Connecting to the treadmill at $IP"
if [ -n "$PAIR_PORT" ] && [ -n "$PAIR_CODE" ]; then
  adb pair "$IP:$PAIR_PORT" "$PAIR_CODE"
fi
# Wireless debugging uses a random port; port 5555 works once 'adb tcpip 5555' has been run.
if ! adb connect "$IP:5555" | grep -q "connected"; then
  echo "Port 5555 didn't answer. Enter the port shown under Wireless debugging (IP address & Port),"
  read -r -p "the number after the colon: " WPORT
  adb connect "$IP:$WPORT" | grep -q "connected" || die "Couldn't connect. Check the IP and that Wireless debugging is on."
  SERIAL="$IP:$WPORT"
else
  SERIAL="$IP:5555"
fi
A() { adb -s "$SERIAL" "$@"; }
echo "Model:   $(A shell getprop ro.product.model | tr -d '\r')"
echo "Android: $(A shell getprop ro.build.version.release | tr -d '\r')"

say "2/4  Copying the motor service certificates"
GLASS_PATHS=$(A shell pm path com.ifit.glassos_service | tr -d '\r' | sed 's/^package://')
[ -n "$GLASS_PATHS" ] || die "This console has no GlassOS service (com.ifit.glassos_service), so C1250 Deck can't drive the motor here."
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
i=0
for p in $GLASS_PATHS; do A pull "$p" "$TMP/glass$i.apk" >/dev/null; i=$((i+1)); done
mkdir -p "$TMP/certs"
for f in glassos_ca.pem glassos_client.pem glassos_client_key.pem; do
  for z in "$TMP"/glass*.apk; do
    entry=$(unzip -Z1 "$z" 2>/dev/null | grep -E "(^|/)$f\$" | head -1 || true)
    if [ -n "$entry" ]; then unzip -p "$z" "$entry" > "$TMP/certs/$f"; break; fi
  done
  [ -s "$TMP/certs/$f" ] || die "Couldn't find $f inside the GlassOS service. This console's firmware may differ from the tested one."
  echo "  found $f"
done

say "3/4  iFit lock-out and home screen"
if A shell pm list packages | grep -q "com.ifit.eru"; then
  if ask "Switch off iFit's lock-out app (com.ifit.eru) so it can't block C1250 Deck?"; then
    A shell pm disable-user --user 0 com.ifit.eru >/dev/null && echo "  switched off"
  fi
fi
if A shell pm list packages | grep -q "com.ifit.launcher"; then
  if ask "Remove the iFit home screen for this user (the motor services stay installed)?"; then
    A shell pm uninstall -k --user 0 com.ifit.launcher >/dev/null && echo "  removed"
  fi
fi

say "4/4  Installing C1250 Deck"
if ! out=$(A install -r "$APK" 2>&1); then
  if echo "$out" | grep -q "UPDATE_INCOMPATIBLE"; then
    echo "  replacing an older build signed with a different key"
    A uninstall "$PKG" >/dev/null; A install "$APK" >/dev/null
  else
    die "Install failed: $out"
  fi
fi
echo "  installed"

# Certificates go into the app's private storage (the app is debuggable, so run-as works).
copied=0
if A shell run-as "$PKG" mkdir -p files/certs >/dev/null 2>&1; then
  copied=1
  for f in glassos_ca.pem glassos_client.pem glassos_client_key.pem; do
    A shell "run-as $PKG sh -c 'cat > files/certs/$f'" < "$TMP/certs/$f" || copied=0
  done
fi
if [ "$copied" != 1 ]; then
  echo "  private copy didn't work; using the app's external folder instead"
  A shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 3
  A shell mkdir -p "/sdcard/Android/data/$PKG/files/certs"
  for f in glassos_ca.pem glassos_client.pem glassos_client_key.pem; do
    A push "$TMP/certs/$f" "/sdcard/Android/data/$PKG/files/certs/$f" >/dev/null
  done
fi
echo "  certificates in place"

# Bluetooth heart rate: grant permissions up front so the console never shows a prompt.
for perm in BLUETOOTH_SCAN BLUETOOTH_CONNECT ACCESS_FINE_LOCATION; do
  A shell pm grant "$PKG" "android.permission.$perm" >/dev/null 2>&1 || true
done
if [ "$(A shell getprop ro.build.version.sdk | tr -d '\r')" -lt 31 ] 2>/dev/null; then
  # Older Android only reports Bluetooth devices while Location is on.
  A shell cmd location set-location-enabled true >/dev/null 2>&1 || A shell settings put secure location_mode 3 >/dev/null 2>&1 || true
fi
echo "  heart rate permissions granted"

# Software updates: let C1250 Deck install its own updates, and record it as its own installer
# so Android 12+ can apply them without an extra confirmation tap.
A shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow >/dev/null 2>&1 || true
A shell pm set-installer "$PKG" "$PKG" >/dev/null 2>&1 || true
echo "  software updates enabled"

if ask "Make C1250 Deck the treadmill's home screen (it opens when the console starts)?"; then
  A shell cmd package set-home-activity "$PKG/.MainActivity" >/dev/null && echo "  set as home screen"
fi

A shell am force-stop "$PKG" >/dev/null 2>&1 || true
A shell am start -n "$PKG/.MainActivity" >/dev/null
say "Done. C1250 Deck is open on the treadmill."
echo "Keep the safety key clipped on. The console's own STOP button always works."
echo "Updates arrive on their own: an \"Update\" button appears in the dock when one is ready."
# Keep ADB reachable on port 5555 for future updates (this ends the current wireless-debugging session).
if [ "$SERIAL" != "$IP:5555" ]; then A tcpip 5555 >/dev/null 2>&1 || true; fi
