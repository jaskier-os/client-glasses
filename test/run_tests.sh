#!/usr/bin/env bash
# Run glasses UI test suite via ADB + uiautomator2.
#
# Usage:
#   ./run_tests.sh                    # Run all tests
#   ./run_tests.sh test_todo.py       # Run only todo tests
#   ./run_tests.sh -k "toggle"        # Run tests matching "toggle"
#   ./run_tests.sh --no-screenshots   # Clean screenshots before run
#
# Prerequisites:
#   - Glasses connected via USB (set GLASSES_SERIAL or connect one device)
#   - pip install uiautomator2 pytest
#   - python3 -m uiautomator2 init --serial "$GLASSES_SERIAL"
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCREENSHOTS_DIR="$SCRIPT_DIR/screenshots"

# Parse args
CLEAN_SCREENSHOTS=false
PYTEST_ARGS=()
for arg in "$@"; do
    if [[ "$arg" == "--no-screenshots" ]]; then
        CLEAN_SCREENSHOTS=true
    else
        PYTEST_ARGS+=("$arg")
    fi
done

# Check device
SERIAL="${GLASSES_SERIAL:-}"; if [ -z "$SERIAL" ]; then SERIAL="$(adb devices | awk 'NR>1 && $2=="device" {print $1}' | head -1)"; fi
if [ -z "$SERIAL" ]; then
    echo "ERROR: Glasses not connected via ADB"
    echo "Set GLASSES_SERIAL=<serial> or connect exactly one device."
    exit 1
fi

# Clean screenshots if requested
if [[ "$CLEAN_SCREENSHOTS" == "true" ]]; then
    rm -rf "$SCREENSHOTS_DIR"
    mkdir -p "$SCREENSHOTS_DIR"
    echo "Cleaned screenshots directory"
fi

# Ensure app is in foreground
CURRENT_PKG=$(adb -s "$SERIAL" shell dumpsys window | grep -oP 'mCurrentFocus.*?\K(com\.\S+?)/' | head -1 | tr -d '/')
if [[ "$CURRENT_PKG" != "com.repository.glasses.listener" ]]; then
    echo "Starting glasses app..."
    adb -s "$SERIAL" shell am start -n com.repository.glasses.listener/.MainActivity
    sleep 2
fi

echo "Running glasses UI tests..."
cd "$SCRIPT_DIR"
python3 -m pytest "${PYTEST_ARGS[@]}" "$SCRIPT_DIR"
