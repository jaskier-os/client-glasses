#!/usr/bin/env bash
# Enable WiFi ADB on the Rokid AR glasses so subsequent adb commands can
# target them without the USB cable.
#
# Flow:
#   1. Require the glasses to be present on USB (serial GLASSES_SERIAL,
#      defaults to the first attached adb device). If they're not attached, bail.
#   2. If WiFi isn't already associated and credentials are supplied,
#      issue `cmd wifi connect-network <SSID> wpa2 <PSK>`.
#   3. Wait until wlan0 has an IPv4 address.
#   4. `adb tcpip 5555` on the USB device.
#   5. `adb connect <ip>:5555` so the host keeps a WiFi handle.
#
# Usage:
#   enable-glasses-wifi-adb.sh                          # assumes WiFi is already joined
#   enable-glasses-wifi-adb.sh <SSID> <PSK>              # joins first, then enables
#   SSID=... PSK=... enable-glasses-wifi-adb.sh          # env-var form
#
# Env overrides: GLASSES_SERIAL, ADB_WIFI_PORT (default 5555), WAIT_IP_S (default 15).
#
# Known network for the lab/home glasses (the device does NOT reliably auto-join
# saved networks -- pass these explicitly if `cmd wifi list-scan-results` is empty
# or wlan0 never gets an IP):
#   SSID: 5G-Vaccination-SlimyBirb   PSK: SlimyBirb
#   i.e.  enable-glasses-wifi-adb.sh "5G-Vaccination-SlimyBirb" "SlimyBirb"
# Glasses typically land at 192.168.0.100:5555 on this network.
set -euo pipefail

SERIAL="${GLASSES_SERIAL:-}"
if [ -z "$SERIAL" ]; then SERIAL="$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}' | head -1)"; fi
PORT="${ADB_WIFI_PORT:-5555}"
WAIT_IP_S="${WAIT_IP_S:-45}"
SSID="${1:-${SSID:-}}"
PSK="${2:-${PSK:-}}"

ADB=(adb -s "$SERIAL")

say() { printf '%s\n' "$*" >&2; }
die() { say "ERROR: $*"; exit 1; }

# 1. Confirm glasses on USB.
if ! adb devices | awk -v s="$SERIAL" '$1==s && $2=="device"{found=1} END{exit !found}'; then
    die "glasses $SERIAL not on USB -- plug the cable in and run again"
fi

# 2. Always enable WiFi radio. Android auto-reconnects to any saved network
#    once the radio is up, so explicit SSID/PSK is only needed for first-time
#    join on this device.
say "enabling WiFi radio ..."
"${ADB[@]}" shell "svc wifi enable" >/dev/null 2>&1 || true
"${ADB[@]}" shell "cmd wifi set-wifi-enabled enabled" >/dev/null 2>&1 || true

if [ -n "$SSID" ]; then
    say "joining WiFi '$SSID' ..."
    "${ADB[@]}" shell "cmd wifi connect-network '$SSID' wpa2 '$PSK'" >/dev/null 2>&1 || true
else
    # No SSID supplied: try to reconnect to the first saved network.
    saved="$("${ADB[@]}" shell "cmd wifi list-networks 2>/dev/null" | tr -d '\r')"
    saved_count="$(echo "$saved" | grep -cE '^[[:space:]]*[0-9]+' || true)"
    if [ -z "$saved_count" ] || [ "$saved_count" -eq 0 ] 2>/dev/null; then
        die "WiFi has no saved networks; rerun with: $0 <SSID> <PSK>"
    fi
    # Check if already connected
    connected="$("${ADB[@]}" shell "cmd wifi status 2>/dev/null | grep -c 'connected' || true" | tr -d '\r')"
    if [ "$connected" = "0" ] 2>/dev/null; then
        # Force reconnect: disable then re-enable triggers auto-join
        say "WiFi not connected, toggling radio to trigger reconnect ..."
        "${ADB[@]}" shell "svc wifi disable" >/dev/null 2>&1 || true
        sleep 2
        "${ADB[@]}" shell "svc wifi enable" >/dev/null 2>&1 || true
        sleep 3
    fi
    say "WiFi has $saved_count saved network(s); waiting for connection ..."
fi

# 3. Wait for an IPv4 on wlan0.
ip=""
for _ in $(seq 1 "$WAIT_IP_S"); do
    ip="$("${ADB[@]}" shell "ip -4 addr show wlan0 2>/dev/null | awk '/inet /{print \$2}' | cut -d/ -f1" | tr -d '\r')"
    [ -n "$ip" ] && break
    sleep 1
done
[ -z "$ip" ] && die "wlan0 did not acquire an IPv4 within ${WAIT_IP_S}s; is WiFi actually joined?"

say "glasses wlan0 IP: $ip"

# 4. Flip to TCP/IP.
"${ADB[@]}" tcpip "$PORT" >/dev/null
sleep 2

# 5. Connect from host. Retry briefly -- adbd needs ~1s after `tcpip` to
#    re-bind on the new port.
ok=0
for _ in 1 2 3 4 5; do
    if adb connect "$ip:$PORT" 2>&1 | grep -qE "^connected to|already connected"; then
        ok=1
        break
    fi
    sleep 1
done
[ "$ok" = 1 ] || die "adb connect $ip:$PORT failed"

say "OK -- WiFi ADB ready at $ip:$PORT (you can unplug the cable)"
printf '%s:%s\n' "$ip" "$PORT"
