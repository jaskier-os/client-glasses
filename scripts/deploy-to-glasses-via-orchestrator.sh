#!/bin/bash
set -euo pipefail

# Deploy glasses app + bt-manager + capture + filesync THROUGH THE ORCHESTRATOR
# instead of through the phone's LAN server. Routes: desktop -> orchestrator
# (Hetzner) -> phone (Android) -> glasses (WiFi-Direct root exec).
#
# This script mirrors deploy-to-glasses-via-phone.sh step-for-step, but replaces
# every phone-LAN HTTP call with an orchestrator HTTP + WS primitive.
#
# Usage:
#   bash deploy-to-glasses-via-orchestrator.sh [ORCH_URL]
#   ORCH_URL=https://65.108.225.44:10001 ORCH_API_KEY=<key> bash deploy-to-glasses-via-orchestrator.sh
#
# Required env:
#   ORCH_API_KEY  -- API key for the orchestrator (x-api-key header)
# Args or env:
#   ORCH_URL      -- orchestrator base URL (e.g. https://65.108.225.44:10001)

# --- Config ---
ORCH_URL="${1:-${ORCH_URL:-}}"
ORCH_API_KEY="${ORCH_API_KEY:-}"

usage() {
    echo "Usage: bash $(basename "$0") [ORCH_URL]"
    echo "   or: ORCH_URL=<url> ORCH_API_KEY=<key> bash $(basename "$0")"
    echo
    echo "Deploys the glasses APKs through the orchestrator -> phone -> glasses"
    echo "(WiFi-Direct). ORCH_URL e.g. https://65.108.225.44:10001."
    echo "ORCH_API_KEY must be set in the environment."
}

if [ -z "$ORCH_URL" ]; then
    echo "ERROR: ORCH_URL not provided." >&2
    usage >&2
    exit 1
fi
if [ -z "$ORCH_API_KEY" ]; then
    echo "ERROR: ORCH_API_KEY not set in environment." >&2
    usage >&2
    exit 1
fi

for tool in curl jq node; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "ERROR: required tool '$tool' not found on PATH. Install it and retry." >&2
        exit 1
    fi
done

# Strip trailing slash from ORCH_URL for consistency.
ORCH_URL="${ORCH_URL%/}"

# Derive WS URL from ORCH_URL (https -> wss, http -> ws).
ORCH_WS_URL="${ORCH_URL/https:/wss:}"
ORCH_WS_URL="${ORCH_WS_URL/http:/ws:}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GLASSES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
AI_DIR="$(cd "$GLASSES_DIR/../.." && pwd)"

# The inline Node.js WS helper needs the 'ws' module. It is installed in the
# orchestrator's node_modules; point NODE_PATH there so `require('ws')` works
# from any cwd.
ORCH_NODE_MODULES="$AI_DIR/orchestrator/node_modules"
if [ ! -d "$ORCH_NODE_MODULES/ws" ]; then
    echo "ERROR: 'ws' npm module not found at $ORCH_NODE_MODULES/ws." >&2
    echo "       Run 'npm install' in AI/orchestrator/ first." >&2
    exit 1
fi
export NODE_PATH="$ORCH_NODE_MODULES"
APP_APK="$GLASSES_DIR/app/build/outputs/apk/debug/app-debug.apk"
BT_MGR_APK="$GLASSES_DIR/bt-manager/build/outputs/apk/debug/bt-manager-debug.apk"
CAPTURE_APK="$GLASSES_DIR/capture/build/outputs/apk/debug/capture-debug.apk"
FILESYNC_APK="$GLASSES_DIR/filesync/build/outputs/apk/debug/filesync-debug.apk"

# Curl knobs -- orchestrator is remote, allow generous timeouts.
CURL_CONNECT_TIMEOUT=15
CURL_MAX_TIME=300

# Track whether we ever opened the sideload session so the EXIT trap only tears
# down what we brought up.
SESSION_OPENED=0

# How long (seconds) to keep retrying poll across a WiFi-Direct outage before
# declaring the command's transport dead.
POLL_FAIL_GRACE_S="${POLL_FAIL_GRACE_S:-120}"

# Transport failure sentinel rc (same as the LAN script).
GL_EXEC_TRANSPORT_RC=200

# ---------------------------------------------------------------------------
# Cleanup on every exit path: close the sideload session. Best-effort; never
# let cleanup failures mask the real exit code.
# ---------------------------------------------------------------------------
cleanup() {
    local rc=$?
    set +e
    if [ "$SESSION_OPENED" = "1" ]; then
        echo "--- Cleanup: closing sideload session via orchestrator ---"
        orch_cmd "sideload_close" '{}' >/dev/null 2>&1 || true
    fi
    return $rc
}
trap cleanup EXIT

# ===========================================================================
#  ORCHESTRATOR PRIMITIVES
# ===========================================================================

# ---------------------------------------------------------------------------
# orch_stage <local_file>
# HTTP POST the file to the orchestrator staging endpoint. Returns the JSON
# response {id, size, sha256} on stdout. Verifies the sha256 matches the
# local file. Returns nonzero on any failure.
# ---------------------------------------------------------------------------
orch_stage() {
    local local_file="$1"
    local local_hash body got_hash

    if [ ! -f "$local_file" ]; then
        echo "orch_stage: local file not found: $local_file" >&2
        return 1
    fi
    local_hash="$(sha256sum "$local_file" | cut -d' ' -f1)"

    body="$(curl -sS -X POST \
        --connect-timeout "$CURL_CONNECT_TIMEOUT" --max-time "$CURL_MAX_TIME" \
        -H "x-api-key: $ORCH_API_KEY" \
        -H "Content-Type: application/octet-stream" \
        --data-binary "@$local_file" \
        -k \
        "$ORCH_URL/api/v1/sideload/stage" 2>/dev/null)"
    if [ $? -ne 0 ] || [ -z "$body" ]; then
        echo "orch_stage: upload transport failure for $local_file" >&2
        return 1
    fi

    got_hash="$(printf '%s' "$body" | jq -r '.sha256 // empty' 2>/dev/null)"
    if [ -z "$got_hash" ]; then
        echo "orch_stage: response missing sha256: $body" >&2
        return 1
    fi
    if [ "$local_hash" != "$got_hash" ]; then
        echo "orch_stage: sha256 mismatch (want=$local_hash got=$got_hash)" >&2
        return 1
    fi

    printf '%s' "$body"
    return 0
}

# ---------------------------------------------------------------------------
# orch_cmd <commandType> <payload_json> [timeout_s]
# Send a device_command via the orchestrator WS and wait for the matching
# response. Uses an inline Node.js one-liner for WS send+receive. Prints the
# response data JSON on stdout. Returns nonzero on timeout or transport error.
#
# The WS protocol:
#   Send:    {type: "sideload_command", requestId: "<uuid>", commandType, payload}
#   Receive: {type: "sideload_response", requestId: "<uuid>", data: {...}}
# ---------------------------------------------------------------------------
orch_cmd() {
    local cmd_type="$1"
    local payload_json="$2"
    local timeout_s="${3:-180}"
    local request_id
    request_id="$(cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen 2>/dev/null || printf '%04x%04x-%04x-%04x-%04x-%04x%04x%04x' $RANDOM $RANDOM $RANDOM $RANDOM $RANDOM $RANDOM $RANDOM $RANDOM)"

    local result
    result="$(timeout "${timeout_s}s" node -e "
const WebSocket = require('ws');
const url = process.argv[1] + '/ws/device';
const apiKey = process.argv[2];
const reqId = process.argv[3];
const cmdType = process.argv[4];
const payload = JSON.parse(process.argv[5]);
const timeoutMs = parseInt(process.argv[6], 10) * 1000;

let received = false;
const ws = new WebSocket(url, {
    headers: { 'x-api-key': apiKey },
    rejectUnauthorized: false
});
const timer = setTimeout(() => {
    process.stderr.write('orch_cmd: WS timeout after ' + (timeoutMs/1000) + 's for ' + cmdType + '\n');
    ws.close();
    process.exit(1);
}, timeoutMs);

ws.on('open', () => {
    const msg = JSON.stringify({
        type: 'sideload_command',
        requestId: reqId,
        commandType: cmdType,
        payload: payload
    });
    ws.send(msg);
});
ws.on('message', (raw) => {
    try {
        const m = JSON.parse(raw.toString());
        if (m.requestId === reqId) {
            received = true;
            clearTimeout(timer);
            process.stdout.write(JSON.stringify(m.data || {}));
            ws.close();
        }
    } catch (_) {}
});
ws.on('error', (err) => {
    clearTimeout(timer);
    process.stderr.write('orch_cmd: WS error: ' + err.message + '\n');
    process.exit(1);
});
ws.on('close', () => {
    clearTimeout(timer);
    if (!received) {
        process.stderr.write('orch_cmd: WS closed without response for ' + cmdType + '\n');
        process.exit(1);
    }
});
" "$ORCH_WS_URL" "$ORCH_API_KEY" "$request_id" "$cmd_type" "$payload_json" "$timeout_s" 2>&1)"
    local rc=$?

    if [ "$rc" -ne 0 ]; then
        echo "orch_cmd: failed (rc=$rc) for $cmd_type" >&2
        echo "$result" >&2
        return 1
    fi

    printf '%s' "$result"
    return 0
}

# ---------------------------------------------------------------------------
# orch_open
# Open the glasses WiFi-Direct sideload session via orchestrator -> phone.
# Retries with backoff up to ~90s (orchestrator path is slower than LAN).
# ---------------------------------------------------------------------------
orch_open() {
    echo "--- Opening sideload session via orchestrator (this can take up to ~90s) ---"
    local deadline body ok err
    deadline=$(( $(date +%s) + 90 ))
    while :; do
        if body="$(orch_cmd "sideload_open" '{}' 60)"; then
            ok="$(printf '%s' "$body" | jq -r '.ok // false' 2>/dev/null)"
            if [ "$ok" = "true" ]; then
                SESSION_OPENED=1
                echo "Sideload session open."
                return 0
            fi
            err="$(printf '%s' "$body" | jq -r '.error // "unknown"' 2>/dev/null)"
            echo "  open not ready yet: $err"
        else
            echo "  open request failed; retrying..."
        fi
        if [ "$(date +%s)" -ge "$deadline" ]; then
            echo "ERROR: sideload session did not come up within 90s. Aborting." >&2
            exit 1
        fi
        sleep 5
    done
}

# ---------------------------------------------------------------------------
# orch_close
# Close the glasses WiFi-Direct sideload session.
# ---------------------------------------------------------------------------
orch_close() {
    orch_cmd "sideload_close" '{}' 30 >/dev/null 2>&1 || true
    SESSION_OPENED=0
}

# ---------------------------------------------------------------------------
# orch_upload <local_file> <remote_name>
# Stage the file on the orchestrator, then send sideload_upload command so the
# phone downloads it and pushes to the glasses. Returns the glasses-side
# response JSON on stdout.
# ---------------------------------------------------------------------------
orch_upload() {
    local local_file="$1" remote_name="$2"
    local stage_body file_id body

    stage_body="$(orch_stage "$local_file")"
    if [ $? -ne 0 ] || [ -z "$stage_body" ]; then
        echo "orch_upload: staging failed for $local_file" >&2
        return 1
    fi
    file_id="$(printf '%s' "$stage_body" | jq -r '.id // empty' 2>/dev/null)"
    if [ -z "$file_id" ]; then
        echo "orch_upload: stage response missing id: $stage_body" >&2
        return 1
    fi

    body="$(orch_cmd "sideload_upload" \
        "$(jq -n --arg fid "$file_id" --arg fn "$remote_name" '{fileId:$fid, fileName:$fn}')" \
        300)"
    if [ $? -ne 0 ]; then
        echo "orch_upload: upload command failed for $remote_name" >&2
        return 1
    fi
    printf '%s' "$body"
    return 0
}

# ---------------------------------------------------------------------------
# orch_exec <cmd>
# Run a root shell command on the glasses via orchestrator -> phone -> glasses.
# Mirrors the LAN gl_exec: sends sideload_exec, streams incremental output,
# returns the glasses command rc. Returns GL_EXEC_TRANSPORT_RC on transport
# failure.
# ---------------------------------------------------------------------------
orch_exec() {
    local cmd="$1"
    local body rc_field rc

    body="$(orch_cmd "sideload_exec" \
        "$(jq -n --arg cmd "$cmd" '{cmd:$cmd}')" \
        300)"
    if [ $? -ne 0 ]; then
        echo "orch_exec: transport failure for command: $cmd" >&2
        return "$GL_EXEC_TRANSPORT_RC"
    fi

    # The orchestrator-relayed sideload_exec response carries the same fields as
    # the LAN version's final poll: rc, stdoutB64, stderrB64, truncated, error.
    # Decode and emit output.
    printf '%s' "$body" | jq -r '.stdoutB64 // ""' 2>/dev/null | base64 -d 2>/dev/null
    printf '%s' "$body" | jq -r '.stderrB64 // ""' 2>/dev/null | base64 -d 2>/dev/null >&2

    local trunc
    trunc="$(printf '%s' "$body" | jq -r 'if .truncated == true then true else false end' 2>/dev/null)"
    [ "$trunc" = "true" ] && echo "orch_exec: WARNING output truncated for command: $cmd" >&2

    # Check for error field (e.g. unknown_job, session not open).
    local err_field
    err_field="$(printf '%s' "$body" | jq -r '.error // empty' 2>/dev/null)"
    if [ -n "$err_field" ]; then
        echo "orch_exec: error from glasses: $err_field (command: $cmd)" >&2
        return "$GL_EXEC_TRANSPORT_RC"
    fi

    rc_field="$(printf '%s' "$body" | jq -r '.rc // empty' 2>/dev/null)"
    if [ -z "$rc_field" ] || [ "$rc_field" = "null" ]; then
        echo "orch_exec: response missing rc for command: $cmd" >&2
        return "$GL_EXEC_TRANSPORT_RC"
    fi
    if ! [[ "$rc_field" =~ ^-?[0-9]+$ ]]; then
        echo "orch_exec: non-integer rc='$rc_field' for command: $cmd" >&2
        return "$GL_EXEC_TRANSPORT_RC"
    fi
    rc=$(( rc_field & 255 ))
    return "$rc"
}

# ---------------------------------------------------------------------------
# orch_push <local_file> <remote_path>
# Upload a file via the orchestrator, then in-place rewrite it onto the final
# root path on the glasses. Mirrors gl_push from the LAN script.
# ---------------------------------------------------------------------------
orch_push() {
    local local_file="$1" remote_path="$2"
    local base_name local_hash body staged_path upload_hash got_hash rc

    if [ ! -f "$local_file" ]; then
        echo "orch_push: local file not found: $local_file" >&2
        return 1
    fi
    base_name="$(basename "$local_file")"
    local_hash="$(sha256sum "$local_file" | cut -d' ' -f1)"

    # Upload raw bytes via orchestrator staging + sideload_upload.
    body="$(orch_upload "$local_file" "$base_name")"
    if [ $? -ne 0 ]; then
        echo "orch_push: upload failed for $local_file" >&2
        return 1
    fi
    if [ "$(printf '%s' "$body" | jq -r '.ok // false' 2>/dev/null)" != "true" ]; then
        echo "orch_push: upload rejected by glasses: $body" >&2
        return 1
    fi
    staged_path="$(printf '%s' "$body" | jq -r '.path // empty' 2>/dev/null)"
    if [ -z "$staged_path" ]; then
        echo "orch_push: upload response missing staged path: $body" >&2
        return 1
    fi
    upload_hash="$(printf '%s' "$body" | jq -r '.sha256 // empty' 2>/dev/null)"
    if [ -n "$upload_hash" ] && [ "$upload_hash" != "$local_hash" ]; then
        echo "orch_push: staged upload sha256 mismatch (want=$local_hash got=$upload_hash)" >&2
        return 1
    fi

    # Verified in-place rewrite (same safe temp-then-cat pattern as the LAN script).
    rc=0
    orch_exec "mkdir -p /data/local/tmp/sideload-stage; \
tmp=/data/local/tmp/sideload-stage/push-\$\$.bin; cat '$staged_path' > \"\$tmp\"; r=\$?; rm -f '$staged_path'; \
if [ \$r -ne 0 ]; then rm -f \"\$tmp\"; exit \$r; fi; \
got=\$(sha256sum \"\$tmp\" 2>/dev/null | cut -d' ' -f1); \
if [ \"\$got\" != '$local_hash' ]; then rm -f \"\$tmp\"; echo \"staged temp sha mismatch got=\$got\" >&2; exit 90; fi; \
cat \"\$tmp\" > '$remote_path'; w=\$?; chmod 0644 '$remote_path' 2>/dev/null; rm -f \"\$tmp\"; exit \$w" >/dev/null || rc=$?
    if [ "$rc" = "$GL_EXEC_TRANSPORT_RC" ]; then
        echo "orch_push: transport failure during rewrite of $remote_path" >&2
        return 1
    fi
    if [ "$rc" -ne 0 ]; then
        echo "orch_push: rewrite command failed (rc=$rc) for $remote_path" >&2
        return 1
    fi

    # Verify the final on-glasses sha256.
    got_hash="$(orch_exec "sha256sum '$remote_path' 2>/dev/null | cut -d' ' -f1")"
    rc=$?
    got_hash="$(printf '%s' "$got_hash" | tr -d '\r\n')"
    if [ "$rc" = "$GL_EXEC_TRANSPORT_RC" ]; then
        echo "orch_push: transport failure verifying $remote_path" >&2
        return 1
    fi
    if [ "$local_hash" != "$got_hash" ]; then
        echo "orch_push: PUSH FAILED (sha256 mismatch want=$local_hash got=${got_hash:-<empty>}) for $remote_path" >&2
        return 1
    fi
    return 0
}

# ---------------------------------------------------------------------------
# orch_exec_grant <cmd>
# Best-effort single grant; tolerate a nonzero command rc (idempotent grants
# often "fail" the second time) but abort on a transport failure.
# ---------------------------------------------------------------------------
orch_exec_grant() {
    local cmd="$1"
    local rc=0
    orch_exec "$cmd" >/dev/null 2>&1 || rc=$?
    if [ "$rc" = "$GL_EXEC_TRANSPORT_RC" ]; then
        echo "ERROR: transport failure applying grant: $cmd" >&2
        exit 1
    fi
    return 0
}

# ===========================================================================
#  DEPLOY FLOW
# ===========================================================================

# ---------------------------------------------------------------------------
# Build all modules (identical to the other deploy scripts).
# ---------------------------------------------------------------------------
echo "Building glasses debug (bt-manager + capture + filesync + app)..."
BUILD_LOG=$(mktemp)
if ! "$GLASSES_DIR/gradlew" -p "$GLASSES_DIR" \
        :bt-manager:assembleDebug \
        :capture:assembleDebug \
        :filesync:assembleDebug \
        :app:assembleDebug > "$BUILD_LOG" 2>&1; then
    echo "BUILD FAILED. Log:"
    cat "$BUILD_LOG"
    rm -f "$BUILD_LOG"
    exit 1
fi
rm -f "$BUILD_LOG"
echo "Build OK."

for apk_path in "$BT_MGR_APK" "$CAPTURE_APK" "$FILESYNC_APK" "$APP_APK"; do
    if [ ! -f "$apk_path" ]; then
        echo "APK not found after build: $apk_path"
        exit 1
    fi
done

# ---------------------------------------------------------------------------
# Open the sideload session via orchestrator.
# ---------------------------------------------------------------------------
orch_open

# ---------------------------------------------------------------------------
# Priv-app overlay install for btmanager / filesync / listener.
# Mirrors push_priv_overlay from deploy-to-glasses-via-phone.sh.
# ---------------------------------------------------------------------------
push_priv_overlay() {
    local pkg="$1" apk_local="$2" apk_name="$3"
    local overlay_dir="/data/local/diy-overlay/system/priv-app/$pkg"
    local target="$overlay_dir/$apk_name"

    local local_hash remote_hash rc
    local_hash="$(sha256sum "$apk_local" | cut -d' ' -f1)"
    remote_hash="$(orch_exec "sha256sum '$target' 2>/dev/null | cut -d' ' -f1")"
    rc=$?
    if [ "$rc" = "$GL_EXEC_TRANSPORT_RC" ]; then
        echo "${pkg}: transport failure reading overlay hash. Aborting." >&2
        exit 1
    fi
    remote_hash="$(printf '%s' "$remote_hash" | tr -d '\r\n')"

    local pm_path
    if [ "$local_hash" = "$remote_hash" ]; then
        pm_path="$(orch_exec "pm path '$pkg' 2>/dev/null")"
        rc=$?
        if [ "$rc" = "$GL_EXEC_TRANSPORT_RC" ]; then
            echo "${pkg}: transport failure reading pm path. Aborting." >&2
            exit 1
        fi
        if printf '%s' "$pm_path" | grep -q '/data/app/'; then
            echo "${pkg} APK unchanged at overlay slot but shadowed by /data/app -- removing shadow."
            orch_exec "pm uninstall '$pkg'" >/dev/null 2>&1 || true
            return 0
        fi
        echo "${pkg} APK unchanged at overlay slot, skipping push."
        return 1
    fi

    rc=0
    orch_exec "mkdir -p '$overlay_dir'" >/dev/null || rc=$?
    if [ "$rc" != "0" ]; then
        echo "${pkg}: failed to mkdir overlay dir (rc=$rc). Aborting." >&2
        exit 1
    fi
    if ! orch_push "$apk_local" "$target"; then
        echo "${pkg} OVERLAY PUSH FAILED (in-place rewrite did not stick)." >&2
        exit 1
    fi
    pm_path="$(orch_exec "pm path '$pkg' 2>/dev/null")"
    rc=$?
    if [ "$rc" = "$GL_EXEC_TRANSPORT_RC" ]; then
        echo "${pkg}: transport failure reading pm path after push. Aborting." >&2
        exit 1
    fi
    if printf '%s' "$pm_path" | grep -q '/data/app/'; then
        echo "removing sideloaded $pkg from /data/app"
        orch_exec "pm uninstall '$pkg'" >/dev/null 2>&1 || true
    fi
    echo "${pkg} APK pushed to priv-app overlay slot."
    return 0
}

PRIV_PUSHED=0
push_priv_overlay com.repository.glasses.btmanager "$BT_MGR_APK"  btmanager.apk && PRIV_PUSHED=1
push_priv_overlay com.repository.glasses.filesync  "$FILESYNC_APK" filesync.apk  && PRIV_PUSHED=1
push_priv_overlay com.repository.glasses.listener  "$APP_APK"     listener.apk  && PRIV_PUSHED=1

# ---------------------------------------------------------------------------
# capture: plain pm install (no privapp-permissions XML needed).
# Upload via orchestrator, copy to world-readable path, pm install, clean up.
# ---------------------------------------------------------------------------
echo "Installing capture APK..."
cap_body="$(orch_upload "$CAPTURE_APK" "capture-debug.apk")"
if [ $? -ne 0 ]; then
    echo "capture upload FAILED." >&2
    exit 1
fi
cap_staged="$(printf '%s' "$cap_body" | jq -r '.path // empty' 2>/dev/null)"
if [ -z "$cap_staged" ]; then
    echo "capture upload response missing staged path: $cap_body" >&2
    exit 1
fi
CAPTURE_INSTALL="/data/local/tmp/sideload-stage/capture-debug-install.apk"
cap_rc=0
orch_exec "mkdir -p /data/local/tmp/sideload-stage && cp '$cap_staged' '$CAPTURE_INSTALL' && chmod 644 '$CAPTURE_INSTALL' && pm install -r '$CAPTURE_INSTALL'; r=\$?; rm -f '$CAPTURE_INSTALL'; exit \$r" || cap_rc=$?
if [ "$cap_rc" = "$GL_EXEC_TRANSPORT_RC" ]; then
    echo "capture INSTALL transport failure." >&2
    exit 1
fi
if [ "$cap_rc" -ne 0 ]; then
    echo "capture INSTALL FAILED (rc=$cap_rc)." >&2
    exit 1
fi
echo "capture installed."

# ---------------------------------------------------------------------------
# Reboot if any priv APK changed.
# ---------------------------------------------------------------------------
if [ "$PRIV_PUSHED" = "1" ]; then
    echo "Priv-app APKs updated -- rebooting glasses so bind-mounts and privapp permissions re-apply..."
    # Snapshot boot_id before reboot.
    pre_boot_id="$(orch_exec "cat /proc/sys/kernel/random/boot_id 2>/dev/null")"
    pre_boot_id="$(printf '%s' "$pre_boot_id" | tr -d '\r\n')"
    echo "  pre-reboot boot_id='${pre_boot_id:-<unknown>}'"

    # Fire reboot. Ignore rc -- device is going down.
    orch_exec "reboot" >/dev/null 2>&1 || true

    # Session is gone now. Close our end.
    SESSION_OPENED=0
    orch_cmd "sideload_close" '{}' 30 >/dev/null 2>&1 || true

    # Wait for device to actually go down before polling.
    sleep 15

    # Poll: re-open the session + check boot_completed AND changed boot_id,
    # up to ~210s total (orchestrator path is slower).
    boot_ok=""
    boot_deadline=$(( $(date +%s) + 210 ))
    while :; do
        # Try to re-open the session with a short per-attempt budget.
        if body="$(orch_cmd "sideload_open" '{}' 45 2>/dev/null)"; then
            ok="$(printf '%s' "$body" | jq -r '.ok // false' 2>/dev/null)"
            if [ "$ok" = "true" ]; then
                SESSION_OPENED=1
                cur_boot_id="$(orch_exec "cat /proc/sys/kernel/random/boot_id 2>/dev/null")"
                cur_boot_id="$(printf '%s' "$cur_boot_id" | tr -d '\r\n')"
                bc="$(orch_exec "getprop sys.boot_completed")"
                bc="$(printf '%s' "$bc" | tr -d '\r\n')"
                if [ -n "$pre_boot_id" ] && [ -n "$cur_boot_id" ] && [ "$cur_boot_id" = "$pre_boot_id" ]; then
                    echo "  link up but boot_id unchanged -- still the pre-reboot session, waiting for actual reboot..."
                elif [ "$bc" = "1" ]; then
                    boot_ok=1
                    echo "Glasses booted (sys.boot_completed=1, boot_id changed to '${cur_boot_id:-<unknown>}')."
                    break
                else
                    echo "  glasses up but not booted yet (boot_completed='${bc:-<empty>}')..."
                fi
            else
                echo "  session open not ready yet..."
            fi
        else
            echo "  session not back yet, retrying..."
        fi
        if [ "$(date +%s)" -ge "$boot_deadline" ]; then
            break
        fi
        sleep 8
    done

    if [ -z "$boot_ok" ]; then
        echo "ERROR: glasses did not finish booting (sys.boot_completed != 1) within budget. Aborting permission grants." >&2
        exit 1
    fi

    # Verify each priv-app package resolves to /system/priv-app.
    for pkg in com.repository.glasses.btmanager com.repository.glasses.filesync com.repository.glasses.listener; do
        ok=""
        path=""
        for _ in 1 2 3 4 5 6 7 8 9 10; do
            path="$(orch_exec "pm path '$pkg' 2>/dev/null")"
            path="$(printf '%s' "$path" | tr -d '\r')"
            case "$path" in
                *"/system/priv-app/$pkg"*) ok=1; break ;;
                *"/data/app"*)
                    echo "$pkg shadowed by /data/app sideload after reboot: $path" >&2
                    exit 1
                    ;;
            esac
            sleep 2
        done
        if [ -z "$ok" ]; then
            echo "$pkg not installed from /system/priv-app -- pm path returned: '${path:-<empty>}'." >&2
            exit 1
        fi
        echo "  $pkg -> $path"
    done
fi

# ---------------------------------------------------------------------------
# Runtime grants / appops / system roles. Idempotent. Applied AFTER any reboot
# so newly-rescanned priv-app packages exist in PMS. Kept in sync with the
# LAN and USB deploy scripts.
# ---------------------------------------------------------------------------
apply_runtime_grants() {
    local pkg perm
    # MANAGE_EXTERNAL_STORAGE: capture + filesync.
    for pkg in com.repository.glasses.capture com.repository.glasses.filesync; do
        orch_exec_grant "appops set $pkg MANAGE_EXTERNAL_STORAGE allow"
    done
    # WRITE_SETTINGS: listener.
    orch_exec_grant "appops set com.repository.glasses.listener WRITE_SETTINGS allow"
    # BLUETOOTH_SCAN/CONNECT: listener.
    for perm in \
        android.permission.BLUETOOTH_SCAN \
        android.permission.BLUETOOTH_CONNECT; do
        orch_exec_grant "pm grant com.repository.glasses.listener $perm"
    done
    # filesync signature-level Wi-Fi perms.
    for perm in \
        android.permission.NETWORK_SETTINGS \
        android.permission.OVERRIDE_WIFI_CONFIG \
        android.permission.WRITE_SECURE_SETTINGS; do
        orch_exec_grant "pm grant com.repository.glasses.filesync $perm"
    done
    # filesync runtime location perms.
    for perm in \
        android.permission.ACCESS_FINE_LOCATION \
        android.permission.ACCESS_COARSE_LOCATION; do
        orch_exec_grant "pm grant com.repository.glasses.filesync $perm"
    done
    # NLS for MediaSessionMonitor.
    orch_exec_grant "cmd notification allow_listener com.repository.glasses.listener/com.repository.glasses.listener.media.MediaNotificationListener"
    # Accessibility service for screen-off on double-tap.
    orch_exec_grant "settings put secure enabled_accessibility_services com.repository.glasses.listener/com.repository.glasses.listener.service.ScreenOffAccessibilityService"
    orch_exec_grant "settings put secure accessibility_enabled 1"
    # Default home.
    orch_exec_grant "cmd role add-role-holder android.app.role.HOME com.repository.glasses.listener"
}
apply_runtime_grants
echo "Runtime grants applied."

# ---------------------------------------------------------------------------
# Verify the accessibility service actually bound (retry loop).
# ---------------------------------------------------------------------------
a11y_svc="com.repository.glasses.listener/com.repository.glasses.listener.service.ScreenOffAccessibilityService"
a11y_bound=""
for _ in 1 2 3 4 5 6 7 8; do
    bound="$(orch_exec "dumpsys accessibility | grep -A2 'Enabled services' | grep -o ScreenOffAccessibilityService")"
    bound="$(printf '%s' "$bound" | tr -d '\r\n')"
    if [ -n "$bound" ]; then a11y_bound=1; break; fi
    orch_exec "settings put secure enabled_accessibility_services '$a11y_svc'; settings put secure accessibility_enabled 1" >/dev/null 2>&1 || true
    sleep 2
done
if [ -n "$a11y_bound" ]; then
    echo "Accessibility service bound -- double-tap screen-off live."
else
    echo "WARNING: ScreenOffAccessibilityService did NOT bind after retries -- double-tap screen-off will not work. Re-run the deploy or check listener install." >&2
fi

# ---------------------------------------------------------------------------
# Write BT MAC to a file readable by bt-manager.
# ---------------------------------------------------------------------------
orch_exec "settings get secure bluetooth_address > /data/local/tmp/glasses_bt_mac && chmod 644 /data/local/tmp/glasses_bt_mac" >/dev/null 2>&1 || true

echo "Done (deploy through orchestrator)."
# EXIT trap performs sideload_close.
exit 0
