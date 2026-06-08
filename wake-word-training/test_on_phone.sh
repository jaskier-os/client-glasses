#!/bin/bash
# Run OWW tests directly on phone via ADB test_oww command
# Usage: bash test_on_phone.sh [--positive-only] [--negative-only] [--count N]


TRAINING_DIR="$(cd "$(dirname "$0")" && pwd)"
FILESDIR="/data/user/0/com.repository.listener/files"
TMPDIR="/data/local/tmp"
POS_DIR="$TRAINING_DIR/data/recorded_positive"
NEG_DIR="$TRAINING_DIR/data/recorded_negative"
COUNT=10

POS_ONLY=false
NEG_ONLY=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --positive-only) POS_ONLY=true; shift ;;
        --negative-only) NEG_ONLY=true; shift ;;
        --count) COUNT="$2"; shift 2 ;;
        --count=*) COUNT="${1#*=}"; shift ;;
        *) shift ;;
    esac
done

run_test() {
    local wav_file="$1"
    local command_id="$2"
    local basename=$(basename "$wav_file")

    adb push "$wav_file" "$TMPDIR/$basename" > /dev/null 2>&1
    adb shell run-as com.repository.listener cp "$TMPDIR/$basename" "files/$basename" 2>/dev/null

    adb shell "am broadcast -n com.repository.listener/.adb.AdbCommandReceiver -a com.repository.listener.ADB_COMMAND --es type test_oww --es command_id $command_id --es params '{\"wav_file\":\"${FILESDIR}/${basename}\"}'" > /dev/null 2>&1

    # Delete any stale result first
    adb shell run-as com.repository.listener rm -f "files/adb_results/${command_id}.json" 2>/dev/null

    for i in $(seq 1 20); do
        sleep 1
        local json=$(adb shell run-as com.repository.listener cat "files/adb_results/${command_id}.json" 2>/dev/null)
        if echo "$json" | grep -q '"status":"success"'; then
            local max_score=$(echo "$json" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['max_score'])" 2>/dev/null || echo "?")
            local frames=$(echo "$json" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['frames_above_threshold'])" 2>/dev/null || echo "?")
            local detected=$(echo "$json" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['detected'])" 2>/dev/null || echo "?")
            echo "  $basename: max=$max_score frames=$frames detected=$detected"
            # Return detected status via global
            LAST_DETECTED="$detected"
            adb shell run-as com.repository.listener rm -f "files/$basename" 2>/dev/null
            return 0
        fi
        if echo "$json" | grep -q '"status":"error"'; then
            local err=$(echo "$json" | python3 -c "import sys,json; print(json.load(sys.stdin).get('error','?'))" 2>/dev/null || echo "?")
            echo "  $basename: ERROR: $err"
            LAST_DETECTED="False"
            return 1
        fi
    done
    echo "  $basename: TIMEOUT"
    LAST_DETECTED="False"
    return 1
}

# Clean stale results
adb shell run-as com.repository.listener sh -c 'rm -f files/adb_results/oww_*.json' 2>/dev/null

if [ "$NEG_ONLY" = false ] && [ -d "$POS_DIR" ]; then
    POS_FILES=($(ls "$POS_DIR"/*.wav 2>/dev/null | head -n $COUNT))
    echo "=== POSITIVE (${#POS_FILES[@]} files) ==="
    pos_detected=0
    pos_total=0
    for f in "${POS_FILES[@]}"; do
        id="oww_p_$(basename "$f" .wav)"
        run_test "$f" "$id"
        pos_total=$((pos_total + 1))
        if [ "$LAST_DETECTED" = "True" ]; then
            pos_detected=$((pos_detected + 1))
        fi
    done
    echo "  RESULT: $pos_detected/$pos_total detected"
    echo
fi

if [ "$POS_ONLY" = false ] && [ -d "$NEG_DIR" ]; then
    NEG_FILES=($(ls "$NEG_DIR"/*.wav 2>/dev/null | head -n $COUNT))
    echo "=== NEGATIVE (${#NEG_FILES[@]} files) ==="
    neg_fp=0
    neg_total=0
    for f in "${NEG_FILES[@]}"; do
        id="oww_n_$(basename "$f" .wav)"
        run_test "$f" "$id"
        neg_total=$((neg_total + 1))
        if [ "$LAST_DETECTED" = "True" ]; then
            neg_fp=$((neg_fp + 1))
        fi
    done
    echo "  RESULT: $neg_fp/$neg_total false positives"
fi
