#!/usr/bin/env bash
# Failure matrix for the AR-stream A2DP suppression lease.
#
# Each case starts a real AR stream session, ends it in a specific abnormal way, and
# measures how long the phone's A2DP Sink link takes to come back CONNECTED. The point
# of the lease design is that EVERY one of these recovers without manual intervention,
# so a case that never recovers is a leak, not a slow path.
#
# Usage: bash a2dp_suppression_matrix.sh [case-number ...]   (default: all)
set -uo pipefail

G=1901092544026001
PHONE_MAC=48:7E:25:A1:4F:BF
OTHER_MAC=14:16:9E:EE:CA:9A     # Honor earbuds -- must never be affected
LISTENER=com.repository.glasses.listener
BTMGR=com.repository.glasses.btmanager
LOG=/sdcard/Download/glasses-client.log

g() { adb -s $G shell "$@"; }

# A2DP Sink connection state for an address, from the profile state machine.
a2dp_state() {
  g dumpsys bluetooth_manager 2>/dev/null \
    | grep -A200 "Profile: A2dpSinkService" \
    | grep "$1" | grep -o "name=A2DPSinkStateMachine state=[A-Za-z]*" \
    | head -1 | sed 's/.*state=//'
}

# A suppressed link does not report "Disconnected" -- the whole state-machine entry
# disappears from the dump. So "down" means Connected-is-absent, not a specific string.
is_up() { [ "$(a2dp_state "$1")" = "Connected" ]; }

wait_up() { # addr timeout_s -> prints elapsed seconds, rc 0 if it came up
  local t0 now; t0=$(date +%s)
  while :; do
    now=$(( $(date +%s) - t0 ))
    is_up "$1" && { echo "$now"; return 0; }
    [ "$now" -ge "$2" ] && { echo "$now"; return 1; }
    sleep 1
  done
}

wait_down() { # addr timeout_s
  local t0 now; t0=$(date +%s)
  while :; do
    now=$(( $(date +%s) - t0 ))
    is_up "$1" || { echo "$now"; return 0; }
    [ "$now" -ge "$2" ] && { echo "$now"; return 1; }
    sleep 1
  done
}

cmd() { g am broadcast -a com.repository.glasses.listener.ADB_COMMAND --es type "$1" -p $LISTENER >/dev/null 2>&1; }

start_session() {
  cmd start_ar_stream
  # start_ar_stream is deferred 1.5s then does WiFi-Direct group formation (~6s).
  local n
  n=$(wait_down $PHONE_MAC 45) \
    && echo "    session up, A2DP down after ${n}s" \
    || { echo "    !! session never suppressed A2DP (${n}s)"; return 1; }
}

check_other_unaffected() {
  local st; st=$(a2dp_state $OTHER_MAC)
  echo "    other device ($OTHER_MAC) A2DP state: ${st:-<not present>}"
}

recover() { # label timeout_s
  local n rc
  n=$(wait_up $PHONE_MAC "$2"); rc=$?
  if [ $rc -eq 0 ]; then echo "    PASS $1: recovered in ${n}s (limit $2s)"
  else echo "    FAIL $1: still $(a2dp_state $PHONE_MAC) after ${n}s (limit $2s)"; fi
  return $rc
}

reset_clean() {
  cmd stop_ar_stream; sleep 2
  g am force-stop $LISTENER >/dev/null 2>&1
  sleep 2
  g am start -n $LISTENER/.MainActivity >/dev/null 2>&1
  wait_up $PHONE_MAC 40 >/dev/null
  sleep 3
}

case1() { echo "[1] listener force-stop mid-session"; start_session || return 1
  g am force-stop $LISTENER; recover "case1" 10; }

case2() { echo "[2] backend process kill -9"; start_session || return 1
  local pid; pid=$(g pidof $LISTENER:backend | tr -d '\r')
  g kill -9 "$pid"
  recover "case2" 10; }

# Killing bt-manager mid-session must NOT release the hold: the session is still
# running and the echo loop is still real, so the bridge has to re-take the lease on
# the new instance. The release is then owed to the session ending, not to the kill.
case3() { echo "[3] bt-manager force-stop mid-session"; start_session || return 1
  g am force-stop $BTMGR
  sleep 12
  is_up $PHONE_MAC && echo "    !! hold LOST after bt-manager restart" \
    || echo "    hold correctly re-asserted on the new bt-manager instance"
  cmd stop_ar_stream; recover "case3" 15; }

case4() { echo "[4] reboot mid-session"; start_session || return 1
  adb -s $G reboot; sleep 20
  for i in $(seq 1 40); do [ "$(g getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; sleep 5; done
  echo "    boot_completed"; recover "case4" 60; }

case5() { echo "[5] phone app force-stop (no stop message ever sent)"; start_session || return 1
  adb -s 65TKQWDIEIL7W8LF shell am force-stop com.repository.phone 2>/dev/null
  # The peer watchdog needs its grace window before it gives up on the phone.
  recover "case5" 45; }

case6() { echo "[6] BT disable/enable"; start_session || return 1
  g svc bluetooth disable; sleep 8; g svc bluetooth enable
  recover "case6" 90; }

case7() { echo "[7] start->start->stop re-entrancy"; start_session || return 1
  cmd start_ar_stream; sleep 8
  is_up $PHONE_MAC && echo "    !! after 2nd start A2DP is UP (hold lost)" \
    || echo "    after 2nd start A2DP still held down (correct)"
  cmd stop_ar_stream; recover "case7" 15; }

case8() { echo "[8] start failure injection (camera busy)"
  cmd record_ar_screen; sleep 3
  cmd start_ar_stream; sleep 12
  local st; st=$(a2dp_state $PHONE_MAC)
  echo "    A2DP after refused start: $st"
  cmd stop_recording; recover "case8" 20; }

case9() { echo "[9] bt-manager wedged (SIGSTOP) then stop, then SIGCONT"; start_session || return 1
  local pid; pid=$(g pidof $BTMGR | tr -d '\r')
  g kill -STOP "$pid"
  local t0=$(date +%s); cmd stop_ar_stream; local dt=$(( $(date +%s) - t0 ))
  echo "    stop_ar_stream returned in ${dt}s while bt-manager wedged (ANR would be >5s)"
  g kill -CONT "$pid"
  recover "case9" 15; }

CASES=${*:-"1 2 3 4 5 6 7 8 9"}
for c in $CASES; do
  echo "=== case $c ==="
  reset_clean
  "case$c"
  check_other_unaffected
  echo
done
