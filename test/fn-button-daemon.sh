#!/system/bin/sh
#
# fn-button-daemon -- global function-button capture for Rokid AR Lite.
#
# Reads /dev/input/event0 (qpnp_pon) via `getevent -lr` and converts button press events
# into ACTION_FN_KEY broadcasts so the listener app's FunctionButtonHandler can run the
# short-press/long-press state machine regardless of which activity has foreground.
#
# qpnp_pon on this firmware emits KEY_MENU (Linux 139) for the function button.
# Short press: KEY_MENU DOWN -> KEY_MENU UP within ~0.3s
# Long press:  KEY_MENU DOWN held, UP comes after >= 1s
#
# Deployed with:
#   adb push fn-button-daemon.sh /data/local/tmp/
#   adb shell chmod 755 /data/local/tmp/fn-button-daemon.sh
#   adb shell "nohup /data/local/tmp/fn-button-daemon.sh >/data/local/tmp/fn-daemon.log 2>&1 &"
#
# For autostart on boot, deploy-to-glasses.sh installs a trampoline into /system/bin/.
#
# ROOT required so getevent can open the raw input device and `am broadcast` can reach
# the listener app (different UID).

set -u
PKG="com.repository.glasses.listener"
ACTION="com.repository.glasses.listener.ACTION_FN_KEY"
DEV="/dev/input/event0"
TARGET_KEY="KEY_MENU"

echo "fn-button-daemon start: dev=$DEV key=$TARGET_KEY pkg=$PKG" >&2

/system/bin/getevent -lrq "$DEV" 2>/dev/null | while IFS= read -r line; do
    # Example lines we care about:
    #   [ time  seqn ]  EV_KEY       KEY_MENU             DOWN
    #   [ time  seqn ]  EV_KEY       KEY_MENU             UP
    case "$line" in
        *"$TARGET_KEY"*"DOWN"*)
            /system/bin/am broadcast -p "$PKG" -a "$ACTION" \
                --es ev_action DOWN --ei ev_repeat 0 >/dev/null 2>&1
            ;;
        *"$TARGET_KEY"*"UP"*)
            /system/bin/am broadcast -p "$PKG" -a "$ACTION" \
                --es ev_action UP --ei ev_repeat 0 >/dev/null 2>&1
            ;;
    esac
done
