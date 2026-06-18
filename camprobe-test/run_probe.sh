#!/usr/bin/env bash
# Drive one camprobe config, sample the privacy LED throughout, collect result JSON + CamX errors.
# Usage: run_probe.sh <config> <w> <h> <dur> [ledprop]
S=1901092544026001
cfg=$1; w=$2; h=$3; dur=$4; ledprop=$5
echo "###### CONFIG=$cfg ${w}x${h} dur=${dur}s ledprop='${ledprop:-<default>}' ######"
adb -s $S shell rm -f /sdcard/Android/data/com.test.camprobe/files/result.json 2>/dev/null
# fully stop so onCreate runs fresh (not onNewIntent race)
adb -s $S shell am force-stop com.test.camprobe 2>/dev/null
adb -s $S shell input keyevent KEYCODE_WAKEUP 2>/dev/null
adb -s $S logcat -c
echo "LED_baseline=$(adb -s $S shell cat /sys/class/leds/white/brightness 2>/dev/null | tr -d '\r')"
LEDARG=""
if [ -n "$ledprop" ]; then LEDARG="--es ledprop $ledprop"; fi
adb -s $S shell am start -n com.test.camprobe/.MainActivity --es config $cfg --ei w $w --ei h $h --ei dur $dur $LEDARG >/dev/null 2>&1
n=$(( (dur+4) / 2 ))
ledtrace=""
ledmax=0
for i in $(seq 1 $n); do
  sleep 2
  b=$(adb -s $S shell cat /sys/class/leds/white/brightness 2>/dev/null | tr -d '\r')
  ledtrace="$ledtrace $b"
  if [ -n "$b" ] && [ "$b" -gt "$ledmax" ] 2>/dev/null; then ledmax=$b; fi
done
echo "LED_during(every2s):$ledtrace  LED_MAX=$ledmax"
sleep 1
echo "RESULT_JSON: $(adb -s $S shell cat /sdcard/Android/data/com.test.camprobe/files/result.json 2>/dev/null | tr -d '\r')"
echo "--- CamX/HAL error summary (counts) ---"
adb -s $S logcat -d 2>&1 | grep -iE "ZSLPreview|buffer error|Reporting a buffer error|RealTimeFeature|onCaptureBufferLost|onCaptureFailed|onConfigureFailed|ERROR_BUFFER|usecase.*fail" | grep -ivE "adbd|daemon_service" | sed -E 's/^.*[0-9]+ [0-9]+ [IWEDV] //' | sort | uniq -c | sort -rn | head -15
echo "--- CamProbe key lines ---"
adb -s $S logcat -d -s CamProbe:I CamProbe:W CamProbe:E 2>&1 | sed -E 's/^.*CamProbe[^:]*: //' | grep -E "RUN|onOpened|onConfigured|setRepeatingRequest|RESULT|threw|failed|Lost|reader" | tail -15
echo ""
