#!/usr/bin/env bash
# Run one config, capture full logcat, print per-30-frame timeline + stall detection + LED + result.
S=1901092544026001
cfg=$1; w=$2; h=$3; dur=$4; ledprop=$5
LEDARG=""; [ -n "$ledprop" ] && LEDARG="--es ledprop $ledprop"
echo "###### $cfg ${w}x${h} ${dur}s ledprop='${ledprop:-default}' ######"
adb -s $S shell am force-stop com.test.camprobe; adb -s $S shell input keyevent KEYCODE_WAKEUP; sleep 1
adb -s $S shell rm -f /sdcard/Android/data/com.test.camprobe/files/result.json 2>/dev/null
adb -s $S logcat -c
adb -s $S shell am start -n com.test.camprobe/.MainActivity --es config $cfg --ei w $w --ei h $h --ei dur $dur $LEDARG >/dev/null 2>&1
LOG=/tmp/cp_${cfg}_${w}x${h}.log
# LED sampler in background
( for i in $(seq 1 $(( (dur+5)/1 )) ); do b=$(adb -s $S shell cat /sys/class/leds/white/brightness 2>/dev/null | tr -d '\r'); echo "$(date +%s) led=$b"; sleep 1; done ) > /tmp/cp_led.txt &
LEDPID=$!
timeout $((dur+5)) adb -s $S logcat -v time > "$LOG" 2>&1
wait $LEDPID 2>/dev/null
echo "--- frame timeline ---"
grep -E "onCaptureCompleted|setRepeating|onConfigured|RESULT|Lost|Failed|threw|reader" "$LOG" | grep CamProbe | sed -E 's/\.[0-9]+ I\/CamProbe\([0-9]+\)//'
echo "--- LED max ---"
awk -F'led=' '{print $2}' /tmp/cp_led.txt | sort -rn | head -1 | xargs echo "LED_MAX="
echo "LED trace: $(awk -F'led=' '{printf "%s ",$2}' /tmp/cp_led.txt)"
echo "--- buffer/usecase errors (unique) ---"
grep -iE "buffer error|Reporting a buffer|ERROR_BUFFER|onCaptureFailed|Flush|recover" "$LOG" | grep -ivE "adbd|daemon|microphone" | sed -E 's/^.*[0-9]+\): //' | sort | uniq -c | sort -rn | head -8
echo "--- RESULT ---"
adb -s $S shell cat /sdcard/Android/data/com.test.camprobe/files/result.json 2>/dev/null | tr -d '\r'; echo ""
