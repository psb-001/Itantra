#!/usr/bin/env bash
#
# Pull the iTantra logs off a connected phone into this project folder.
#
#   ./pull_logs.sh              # the only connected device
#   ./pull_logs.sh <serial>     # a specific one, from `adb devices`
#
# Produces, in output/option_a_stt/logs/:
#   itantra_<serial>_<timestamp>.log       the app's own event log (file logger)
#   logcat_<serial>_<timestamp>.txt        the full system logcat, filtered to what matters
#
# WHY BOTH. The app log tells us what OUR code decided. The system logcat tells us what ANDROID
# did about it — Bluetooth stack errors, permission denials, GATT/bond failures. Debugging a
# connection problem with only one of the two is guesswork.
#
# If you have no USB cable, use the DEVICES screen's log instead, or install Logcat Reader
# (F-Droid: com.dp.logcatapp) on the phone — see BITCHAT_COMPARISON.md for the note on it.

set -uo pipefail

ADB="${ADB:-$HOME/android-dev/android-sdk/platform-tools/adb}"
APP_ID="com.bjp.itantra"

OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/logs"
mkdir -p "$OUT_DIR"

STAMP="$(date +%Y%m%d_%H%M%S)"

# ---------------------------------------------------------------- pick a device
SERIAL="${1:-}"
if [ -z "$SERIAL" ]; then
  SERIAL="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi

if [ -z "$SERIAL" ]; then
  echo "No device connected."
  echo
  echo "  Connect a phone by USB, enable USB debugging, then run this again."
  echo "  Check with:  $ADB devices"
  echo
  echo "If you cannot use USB, the app's own log is on the phone at:"
  echo "  /sdcard/Android/data/$APP_ID/files/logs/itantra.log"
  exit 1
fi

echo "device: $SERIAL"
echo

# ---------------------------------------------------------------- 1. app log file
APP_LOG="$OUT_DIR/itantra_${SERIAL}_${STAMP}.log"
echo "pulling the app's own log..."
if "$ADB" -s "$SERIAL" pull \
     "/sdcard/Android/data/$APP_ID/files/logs/itantra.log" \
     "$APP_LOG" 2>/dev/null; then
  echo "  -> $APP_LOG  ($(wc -l < "$APP_LOG" | tr -d ' ') lines)"
else
  echo "  not found. The app writes it on launch — open the app once, then retry."
  echo "  (Also note: /sdcard/Android/data is not readable on Android 11+ from some file"
  echo "   managers. adb can read it; the Files app usually cannot.)"
fi

# ---------------------------------------------------------------- 2. system logcat
# Only the tags that matter. An unfiltered logcat is 95% noise and buries the signal.
SYSTEM_LOG="$OUT_DIR/logcat_${SERIAL}_${STAMP}.txt"
echo
echo "capturing system logcat (Bluetooth, Wi-Fi P2P, permissions)..."
"$ADB" -s "$SERIAL" logcat -d -v threadtime \
  iTantra:V iTantra/BT:V iTantra/Tts:V iTantra/WiFiDirect:V iTantra/FileLog:V \
  BluetoothAdapter:V BluetoothSocket:V BluetoothManagerService:V bt_btif:V bt_stack:V \
  WifiP2pManager:V WifiP2pService:V wpa_supplicant:V \
  ActivityManager:I AndroidRuntime:E System.err:W \
  > "$SYSTEM_LOG" 2>/dev/null

echo "  -> $SYSTEM_LOG  ($(wc -l < "$SYSTEM_LOG" | tr -d ' ') lines)"

# ---------------------------------------------------------------- 3. quick summary
echo
echo "=============================================================="
echo " connect attempts and outcomes"
echo "=============================================================="
if [ -f "$APP_LOG" ]; then
  grep -E "CONNECTED to|attempt .* failed|LISTENING|accepted an incoming|accept loop died" \
    "$APP_LOG" | tail -20 || echo "  (no connection events yet)"
else
  echo "  (no app log)"
fi

echo
echo "=============================================================="
echo " errors worth reading"
echo "=============================================================="
# Only OUR app and the Bluetooth/Wi-Fi stacks. An unfiltered error grep returns Google service
# noise, DNS probe failures and Play Store sync errors — all irrelevant and all drowning the signal.
{
  [ -f "$APP_LOG" ] && grep -hE "FAIL|ERROR" "$APP_LOG"
  [ -f "$SYSTEM_LOG" ] && grep -hE "iTantra|Bluetooth|bt_|btif|WifiP2p|wpa_supplicant|AndroidRuntime|FATAL" \
    "$SYSTEM_LOG" | grep -E " E | F |W/.*(fail|denied|refused)"
} 2>/dev/null | grep -viE "BatteryExternalStats|DnsResolver|PROBE_DNS|NETWORK_STATUS|AiAi|VoiceData|WM-Worker|config_new|gservices" \
  | tail -25 || echo "  (none)"

echo
echo "=============================================================="
echo " full app log"
echo "=============================================================="
if [ -f "$APP_LOG" ]; then
  cat "$APP_LOG"
else
  echo "  (no app log pulled)"
fi

echo
echo "done. logs are in $(cd "$OUT_DIR" && pwd)"
echo "  app log : $APP_LOG"
echo "  logcat  : $SYSTEM_LOG"
