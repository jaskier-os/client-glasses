#!/bin/bash
# Mirror Rokid glasses screen via scrcpy.
# Usage: bash scrcpy-glasses.sh

GLASSES_SERIAL=""
while IFS= read -r line; do
    serial=$(echo "$line" | awk '$2=="device" {print $1}')
    [ -z "$serial" ] && continue
    model=$(adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
    if [ "$model" = "RG-glasses" ]; then
        GLASSES_SERIAL="$serial"
        break
    fi
done < <(adb devices 2>/dev/null)

if [ -z "$GLASSES_SERIAL" ]; then
    echo "Glasses not found via ADB."
    exit 1
fi

keep_awake() {
    while true; do
        state=$(adb -s "$GLASSES_SERIAL" shell dumpsys power 2>/dev/null | grep -m1 'mWakefulness=' | tr -d '\r')
        if [[ "$state" != *"Awake"* ]]; then
            adb -s "$GLASSES_SERIAL" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
        fi
        sleep 5
    done
}

keep_awake &
KEEP_AWAKE_PID=$!
trap 'kill "$KEEP_AWAKE_PID" 2>/dev/null' EXIT INT TERM

echo "Starting scrcpy on glasses ($GLASSES_SERIAL)..."
# Force software rendering -- this machine's X server rejects scrcpy's default
# OpenGL/GLX context (BadValue X_GLXCreateContext). SDL_RENDER_DRIVER=software
# plus --render-driver=software keeps SDL off GLX entirely. LIBGL_ALWAYS_SOFTWARE
# is a belt-and-suspenders fallback for the mesa path.
export SDL_RENDER_DRIVER=software
export SDL_FRAMEBUFFER_ACCELERATION=0
export LIBGL_ALWAYS_SOFTWARE=1
scrcpy -s "$GLASSES_SERIAL" --max-size 640 --no-audio --render-driver=software
