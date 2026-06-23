#!/bin/bash
set -euo pipefail

# Deploy glasses app + bt-manager + capture + filesync THROUGH THE PHONE over
# LAN+WiFi-Direct, instead of a direct adb-USB cable to the glasses.
#
# The phone runs a LAN HTTP server (default port 8771) that forwards file uploads
# and root-command execution to the glasses over WiFi-Direct. This script mirrors
# scripts/deploy-to-glasses.sh step-for-step, but replaces every
# `adb -s <glasses>` operation with a phone-mediated HTTP call.
#
# Usage:
#   bash deploy-to-glasses-via-phone.sh <PHONE_IP> [PHONE_PORT]
#   PHONE_IP=192.168.1.50 bash deploy-to-glasses-via-phone.sh
#
# Endpoints targeted on http://<PHONE_IP>:<PHONE_PORT>:
#   GET  /sideload/health   -> {"ok":true,"glassesBt":<bool>}
#   POST /sideload/open     -> opens WiFi-Direct + joins (slow, ~60s)
#   POST /sideload/close    -> tears link down
#   POST /sideload/upload?name=<basename>  body=raw bytes
#         -> {"ok":true,"path":"/data/local/tmp/sideload/<name>","size":N,"sha256":"hex"}
#   POST /sideload/exec     body {"cmd":"..."} -> {"rc":int,"stdout":"","stderr":"","truncated":bool}
#   POST /sideload/cleanup  -> {"ok":true}

# --- Config ---
PHONE_IP="${1:-${PHONE_IP:-}}"
PHONE_PORT="${PHONE_PORT:-8771}"

usage() {
    echo "Usage: bash $(basename "$0") <PHONE_IP> [PHONE_PORT]"
    echo "   or: PHONE_IP=<ip> [PHONE_PORT=<port>] bash $(basename "$0")"
    echo
    echo "Deploys the glasses APKs through the phone's LAN sideload HTTP server"
    echo "(WiFi-Direct to the glasses). PHONE_PORT defaults to 8771."
}

if [ -z "$PHONE_IP" ]; then
    echo "ERROR: PHONE_IP not provided." >&2
    usage >&2
    exit 1
fi

for tool in curl jq; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "ERROR: required tool '$tool' not found on PATH. Install it and retry." >&2
        exit 1
    fi
done

BASE_URL="http://${PHONE_IP}:${PHONE_PORT}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GLASSES_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_APK="$GLASSES_DIR/app/build/outputs/apk/debug/app-debug.apk"
BT_MGR_APK="$GLASSES_DIR/bt-manager/build/outputs/apk/debug/bt-manager-debug.apk"
CAPTURE_APK="$GLASSES_DIR/capture/build/outputs/apk/debug/capture-debug.apk"
FILESYNC_APK="$GLASSES_DIR/filesync/build/outputs/apk/debug/filesync-debug.apk"

# Curl knobs. --fail-with-body is preferred but not on every curl; fall back to
# capturing the HTTP status manually below so we can distinguish transport from
# HTTP-level errors.
CURL_CONNECT_TIMEOUT=10
CURL_MAX_TIME=120

# Track whether we ever opened the session so the EXIT trap only tears down what
# we brought up.
SESSION_OPENED=0

# ---------------------------------------------------------------------------
# Cleanup on every exit path: wipe glasses staging + tear the WiFi link down.
# Best-effort; never let cleanup failures mask the real exit code.
# ---------------------------------------------------------------------------
cleanup() {
    local rc=$?
    set +e
    if [ "$SESSION_OPENED" = "1" ]; then
        echo "--- Cleanup: wiping glasses staging + closing WiFi-Direct link ---"
        curl -sS -X POST \
            --connect-timeout "$CURL_CONNECT_TIMEOUT" --max-time "$CURL_MAX_TIME" \
            "$BASE_URL/sideload/cleanup" >/dev/null 2>&1 || true
        curl -sS -X POST \
            --connect-timeout "$CURL_CONNECT_TIMEOUT" --max-time "$CURL_MAX_TIME" \
            "$BASE_URL/sideload/close" >/dev/null 2>&1 || true
    fi
    return $rc
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
# Low-level HTTP helper. Performs the request, splits the trailing HTTP status
# code off the body. On a transport failure (curl nonzero -> no status) it
# returns 1 and prints to stderr. On HTTP >= 400 it returns 2 and prints the
# body. On success it prints the body to stdout and returns 0.
#   _http <method> <url> [curl-extra-args...]
# Body content for POSTs is supplied via the extra args (e.g. --data-binary,
# --data, -H).
# ---------------------------------------------------------------------------
_http() {
    local method="$1" url="$2"
    shift 2
    local resp http_code body curl_rc
    # %{http_code} appended on its own line so we can strip it deterministically.
    resp="$(curl -sS -X "$method" \
        --connect-timeout "$CURL_CONNECT_TIMEOUT" --max-time "$CURL_MAX_TIME" \
        -w $'\n%{http_code}' \
        "$@" \
        "$url" 2>/dev/null)"
    curl_rc=$?
    if [ "$curl_rc" -ne 0 ]; then
        echo "HTTP transport error ($method $url): curl exit $curl_rc" >&2
        return 1
    fi
    http_code="${resp##*$'\n'}"
    body="${resp%$'\n'*}"
    printf '%s' "$body"
    case "$http_code" in
        2??) return 0 ;;
        *)
            echo "HTTP $http_code from $method $url" >&2
            return 2
            ;;
    esac
}

# ---------------------------------------------------------------------------
# gl_exec <cmd>
# Run a root (su) shell command on the glasses via the phone.
# Prints the command's stdout then stderr (stderr to our stderr).
# Returns the glasses command rc (0-255). If the HTTP transport itself failed
# (phone unreachable, link down) returns 200 to distinguish it from any plausible
# command rc, and logs a clear transport-error line. Callers that must tell the
# two apart should check for rc==200.
# ---------------------------------------------------------------------------
GL_EXEC_TRANSPORT_RC=200
# How long (seconds) to keep retrying poll across a WiFi-Direct outage before declaring the
# command's transport dead. The on-glasses ExecJob keeps running regardless, so we can afford a
# generous window; a real p2p blip on this firmware recovers within a few seconds.
POLL_FAIL_GRACE_S="${POLL_FAIL_GRACE_S:-120}"

gl_exec() {
    local cmd="$1"
    local payload body job rc

    # Start an async job. The command runs on the glasses with NO time limit; we drain its
    # output incrementally via /sideload/exec/poll. This replaces the old single-request
    # /sideload/exec (which was capped at the phone's 60s socket + the glasses' 120s exec
    # timeout), so arbitrarily long commands work and stream live.
    #
    # START RESILIENCE: the glasses-side filesync HTTP server (:8849) frequently drops the
    # FIRST request after a WiFi-Direct group (re)forms -- the phone proxies it back as an
    # HTTP 502 "unexpected end of stream", and the GO also tears down after ~36s idle. A bare
    # one-shot start would abort the whole deploy on that transient blip. So retry exec/start
    # for a bounded grace window, re-opening the session between attempts (a re-open clears the
    # wedge); the job is only created once a start actually returns a job id, so retrying a
    # failed start cannot leak duplicate jobs.
    payload="$(jq -n --arg cmd "$cmd" '{cmd:$cmd}')"
    local start_fail_start=0 start_reopen_at=0 snow
    job=""
    while :; do
        if body="$(_http POST "$BASE_URL/sideload/exec/start" \
                -H 'Content-Type: application/json' \
                --data "$payload")"; then
            job="$(printf '%s' "$body" | jq -r '.job // empty' 2>/dev/null)"
            [ -n "$job" ] && break
            # Reached the server but got no job id (e.g. {"ok":false,...} transient). Treat like
            # a transport blip and retry within the grace window rather than aborting outright.
            echo "gl_exec: start returned no job id (will retry): $body" >&2
        fi
        snow=$(date +%s)
        if [ "$start_fail_start" -eq 0 ]; then start_fail_start=$snow; start_reopen_at=$snow; fi
        if [ $((snow - start_fail_start)) -ge "$POLL_FAIL_GRACE_S" ]; then
            echo "gl_exec TRANSPORT FAILURE (start outage > ${POLL_FAIL_GRACE_S}s) for command: $cmd" >&2
            return "$GL_EXEC_TRANSPORT_RC"
        fi
        # Recycle the WiFi-Direct session and retry. A bare re-open does NOT clear the glasses
        # :8849 "unexpected end of stream" wedge once the server is in that state -- the link
        # reports open but every request 502s. A close->open cycle DOES reset it (verified on
        # device). Spaced every ~5s to avoid hammering.
        if [ "$snow" -ge "$start_reopen_at" ]; then
            _http POST "$BASE_URL/sideload/close" >/dev/null 2>&1 || true
            _http POST "$BASE_URL/sideload/open" >/dev/null 2>&1 || true
            start_reopen_at=$((snow + 5))
        fi
        sleep 1
    done

    local sout=0 serr=0 running rc_field trunc out err total_out total_err
    # The async ExecJob keeps running on the glasses independent of the WiFi-Direct link, so a
    # transient p2p blip (common on this firmware) must NOT abort the job -- we keep polling, and
    # periodically try to re-open the session, for a long grace window before giving up. The job
    # id is stable across a re-join, so polling resumes exactly where it left off.
    local fail_start=0 reopen_at=0 now
    while :; do
        payload="$(jq -n --arg job "$job" --argjson so "$sout" --argjson se "$serr" \
            '{job:$job, stdoutFrom:$so, stderrFrom:$se}')"
        if ! body="$(_http POST "$BASE_URL/sideload/exec/poll" \
                -H 'Content-Type: application/json' \
                --data "$payload")"; then
            # A poll that reaches the glasses but returns "unknown_job" means the job was LOST
            # (the session was torn down mid-command -- e.g. a WiFi-Direct idle teardown killed the
            # ExecJob). It will never come back; retrying just spins. Treat as a hard, distinct
            # failure so the caller aborts instead of looping for the whole grace window and never
            # mistakes it for a still-running job.
            if printf '%s' "$body" | grep -q 'unknown_job'; then
                echo "gl_exec JOB LOST (session reset mid-command) for command: $cmd" >&2
                return "$GL_EXEC_TRANSPORT_RC"
            fi
            now=$(date +%s)
            if [ "$fail_start" -eq 0 ]; then fail_start=$now; reopen_at=$((now + 5)); fi
            # Give up only after a long sustained outage (the link isn't coming back).
            if [ $((now - fail_start)) -ge "$POLL_FAIL_GRACE_S" ]; then
                echo "gl_exec TRANSPORT FAILURE (poll outage > ${POLL_FAIL_GRACE_S}s) for command: $cmd" >&2
                return "$GL_EXEC_TRANSPORT_RC"
            fi
            # Every few seconds of failure, recycle the WiFi-Direct session so polling can resume.
            # The on-glasses ExecJob keeps running the whole time (it's independent of the link),
            # and the job id is stable across a re-join, so polling resumes where it left off. A
            # close->open cycle is required (not a bare open): once the glasses :8849 server is in
            # the "unexpected end of stream" wedge a re-open alone keeps 502ing; a close resets it.
            if [ "$now" -ge "$reopen_at" ]; then
                _http POST "$BASE_URL/sideload/close" >/dev/null 2>&1 || true
                _http POST "$BASE_URL/sideload/open" >/dev/null 2>&1 || true
                reopen_at=$((now + 5))
            fi
            sleep 1
            continue
        fi
        fail_start=0

        # Stream any new stdout/stderr as it arrives, advancing our byte offsets. Output is
        # base64 (binary-clean: a multibyte UTF-8 char split across two polls is never
        # corrupted, and arbitrary binary output survives intact).
        printf '%s' "$body" | jq -r '.stdoutB64 // ""' 2>/dev/null | base64 -d 2>/dev/null
        printf '%s' "$body" | jq -r '.stderrB64 // ""' 2>/dev/null | base64 -d 2>/dev/null >&2
        total_out="$(printf '%s' "$body" | jq -r '.stdoutTotal // 0' 2>/dev/null)"
        total_err="$(printf '%s' "$body" | jq -r '.stderrTotal // 0' 2>/dev/null)"
        [[ "$total_out" =~ ^[0-9]+$ ]] && sout="$total_out"
        [[ "$total_err" =~ ^[0-9]+$ ]] && serr="$total_err"

        # NOTE: do NOT use `.running // true` -- jq's `//` treats boolean false as "empty" and
        # returns the fallback, so a finished job (running:false) would read as true and loop
        # forever. Extract the raw value and default only a literal null/missing to "true".
        running="$(printf '%s' "$body" | jq -r 'if has("running") and .running != null then .running else true end' 2>/dev/null)"
        if [ "$running" != "true" ]; then
            trunc="$(printf '%s' "$body" | jq -r 'if .truncated == true then true else false end' 2>/dev/null)"
            [ "$trunc" = "true" ] && echo "gl_exec: WARNING output truncated for command: $cmd" >&2
            rc_field="$(printf '%s' "$body" | jq -r '.rc // empty' 2>/dev/null)"
            if [ -z "$rc_field" ] || [ "$rc_field" = "null" ]; then
                echo "gl_exec: finished job with no rc for command: $cmd" >&2
                return "$GL_EXEC_TRANSPORT_RC"
            fi
            # appsud maps signals to 128+sig and spawn failure to -1; normalize to a byte rc.
            if ! [[ "$rc_field" =~ ^-?[0-9]+$ ]]; then
                echo "gl_exec: non-integer rc='$rc_field' for command: $cmd" >&2
                return "$GL_EXEC_TRANSPORT_RC"
            fi
            # Final drain poll with the fully-advanced offsets so the glasses retires the job
            # and wipes staging now (otherwise it lingers until the idle-TTL reaper). Best-effort.
            payload="$(jq -n --arg job "$job" --argjson so "$sout" --argjson se "$serr" \
                '{job:$job, stdoutFrom:$so, stderrFrom:$se}')"
            _http POST "$BASE_URL/sideload/exec/poll" -H 'Content-Type: application/json' \
                --data "$payload" >/dev/null 2>&1 || true
            rc=$(( rc_field & 255 ))
            return "$rc"
        fi
        sleep 0.3
    done
}

# ---------------------------------------------------------------------------
# gl_push <local_file> <remote_path>
# Upload a file to the phone, which stages it on the glasses, then in-place
# rewrite it onto the final root path (inode-preserving `cat >`), chmod 0644,
# and verify sha256. Returns nonzero on any failure (including sha mismatch).
# ---------------------------------------------------------------------------
gl_push() {
    local local_file="$1" remote_path="$2"
    local base_name staged_path body local_hash got_hash rc

    if [ ! -f "$local_file" ]; then
        echo "gl_push: local file not found: $local_file" >&2
        return 1
    fi
    base_name="$(basename "$local_file")"
    local_hash="$(sha256sum "$local_file" | cut -d' ' -f1)"

    # Upload raw bytes; phone stages under /data/local/tmp/sideload/<name>.
    if ! body="$(_http POST "$BASE_URL/sideload/upload?name=$base_name" \
            -H 'Content-Type: application/octet-stream' \
            --data-binary "@$local_file")"; then
        echo "gl_push: upload transport/HTTP failure for $local_file" >&2
        return 1
    fi
    if [ "$(printf '%s' "$body" | jq -r '.ok // false' 2>/dev/null)" != "true" ]; then
        echo "gl_push: upload rejected by glasses: $body" >&2
        return 1
    fi
    staged_path="$(printf '%s' "$body" | jq -r '.path // empty' 2>/dev/null)"
    if [ -z "$staged_path" ]; then
        echo "gl_push: upload response missing staged path: $body" >&2
        return 1
    fi
    # Verify the upload landed intact at the staging path before rewriting.
    local upload_hash
    upload_hash="$(printf '%s' "$body" | jq -r '.sha256 // empty' 2>/dev/null)"
    if [ -n "$upload_hash" ] && [ "$upload_hash" != "$local_hash" ]; then
        echo "gl_push: staged upload sha256 mismatch (want=$local_hash got=$upload_hash)" >&2
        return 1
    fi

    # Verified in-place rewrite. The overlay target is BIND-MOUNTED over the baked /system stub, so
    # `mv` onto it fails with EBUSY -- the only thing that works is rewriting its CONTENTS in place
    # (`cat src > target`). To avoid leaving a TRUNCATED overlay APK (which would bootloop/break the
    # priv-app) if the link drops mid-cat, we first copy the upload to a scratch temp and verify the
    # TEMP's sha BEFORE touching the real target; only a fully-correct temp is allowed to overwrite
    # the live overlay file. We then re-verify the target's sha below. The temp lives in the
    # root-owned INSTALL_SCRATCH_DIR which filesync force-wipes on session teardown.
    rc=0
    gl_exec "mkdir -p /data/local/tmp/sideload-stage; \
tmp=/data/local/tmp/sideload-stage/push-\$\$.bin; cat '$staged_path' > \"\$tmp\"; r=\$?; rm -f '$staged_path'; \
if [ \$r -ne 0 ]; then rm -f \"\$tmp\"; exit \$r; fi; \
got=\$(sha256sum \"\$tmp\" 2>/dev/null | cut -d' ' -f1); \
if [ \"\$got\" != '$local_hash' ]; then rm -f \"\$tmp\"; echo \"staged temp sha mismatch got=\$got\" >&2; exit 90; fi; \
cat \"\$tmp\" > '$remote_path'; w=\$?; chmod 0644 '$remote_path' 2>/dev/null; rm -f \"\$tmp\"; exit \$w" >/dev/null || rc=$?
    if [ "$rc" = "$GL_EXEC_TRANSPORT_RC" ]; then
        echo "gl_push: transport failure during rewrite of $remote_path" >&2
        return 1
    fi
    if [ "$rc" -ne 0 ]; then
        echo "gl_push: rewrite command failed (rc=$rc) for $remote_path" >&2
        return 1
    fi

    # Verify the final on-glasses sha256 matches the local file.
    got_hash="$(gl_exec "sha256sum '$remote_path' 2>/dev/null | cut -d' ' -f1")"
    rc=$?
    got_hash="$(printf '%s' "$got_hash" | tr -d '\r\n')"
    if [ "$rc" = "$GL_EXEC_TRANSPORT_RC" ]; then
        echo "gl_push: transport failure verifying $remote_path" >&2
        return 1
    fi
    if [ "$local_hash" != "$got_hash" ]; then
        echo "gl_push: PUSH FAILED (sha256 mismatch want=$local_hash got=${got_hash:-<empty>}) for $remote_path" >&2
        return 1
    fi
    return 0
}

# ---------------------------------------------------------------------------
# Open the sideload session: health-check, then POST /sideload/open with backoff
# up to ~60s. Aborts with a clear message if the link never comes up.
# ---------------------------------------------------------------------------
open_session() {
    echo "--- Health-checking phone sideload server at $BASE_URL ---"
    local hbody
    if ! hbody="$(_http GET "$BASE_URL/sideload/health")"; then
        echo "ERROR: phone sideload server not reachable at $BASE_URL/sideload/health." >&2
        echo "       Is the phone on the LAN, app running, and 'Enable sideloading' on?" >&2
        exit 1
    fi
    if [ "$(printf '%s' "$hbody" | jq -r '.ok // false' 2>/dev/null)" != "true" ]; then
        echo "ERROR: phone sideload health not ok: $hbody" >&2
        exit 1
    fi
    if [ "$(printf '%s' "$hbody" | jq -r '.glassesBt // false' 2>/dev/null)" != "true" ]; then
        echo "WARNING: phone reports glassesBt=false -- glasses BT link not present; open may fail." >&2
    fi
    echo "Health OK."

    echo "--- Opening WiFi-Direct sideload session (this can take up to ~60s) ---"
    local deadline now obody ok
    deadline=$(( $(date +%s) + 60 ))
    while :; do
        if obody="$(_http POST "$BASE_URL/sideload/open")"; then
            ok="$(printf '%s' "$obody" | jq -r '.ok // false' 2>/dev/null)"
            if [ "$ok" = "true" ]; then
                SESSION_OPENED=1
                echo "WiFi-Direct sideload session open."
                return 0
            fi
            echo "  open not ready yet: $(printf '%s' "$obody" | jq -r '.error // "unknown"' 2>/dev/null)"
        else
            echo "  open request failed (transport/HTTP); retrying..."
        fi
        now=$(date +%s)
        if [ "$now" -ge "$deadline" ]; then
            echo "ERROR: WiFi-Direct sideload session did not come up within 60s. Aborting." >&2
            exit 1
        fi
        sleep 3
    done
}

# Re-open the session after a reboot drops the WiFi-Direct group. Longer budget
# because the glasses are also booting. Polls /sideload/open until ok=true.
reopen_session() {
    local budget="$1"
    local deadline obody ok
    deadline=$(( $(date +%s) + budget ))
    echo "--- Re-opening WiFi-Direct sideload session (budget ${budget}s) ---"
    while :; do
        if obody="$(_http POST "$BASE_URL/sideload/open")"; then
            ok="$(printf '%s' "$obody" | jq -r '.ok // false' 2>/dev/null)"
            if [ "$ok" = "true" ]; then
                SESSION_OPENED=1
                echo "  link back up."
                return 0
            fi
        fi
        if [ "$(date +%s)" -ge "$deadline" ]; then
            return 1
        fi
        sleep 5
    done
}

# ---------------------------------------------------------------------------
# Build all modules (identical to deploy-to-glasses.sh).
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
# Open the phone-mediated session.
# ---------------------------------------------------------------------------
open_session

# ---------------------------------------------------------------------------
# Priv-app overlay install for btmanager / filesync / listener.
# Mirrors push_priv_overlay from deploy-to-glasses.sh, but every adb op is now
# a gl_exec / gl_push. Returns 0 (and sets a marker) when the overlay changed,
# 1 when unchanged.
#
# The privapp-permissions rationale is unchanged from the USB script: these
# packages MUST live in /system/priv-app via the overlay slot, pushed with the
# in-place `cat > target` rewrite, with any /data/app shadow removed, then a
# reboot so PMS rescans /system/priv-app and applies the privapp grants.
# ---------------------------------------------------------------------------
push_priv_overlay() {
    local pkg="$1" apk_local="$2" apk_name="$3"
    local overlay_dir="/data/local/diy-overlay/system/priv-app/$pkg"
    local target="$overlay_dir/$apk_name"

    local local_hash remote_hash rc
    local_hash="$(sha256sum "$apk_local" | cut -d' ' -f1)"
    remote_hash="$(gl_exec "sha256sum '$target' 2>/dev/null | cut -d' ' -f1")"
    rc=$?
    if [ "$rc" = "$GL_EXEC_TRANSPORT_RC" ]; then
        echo "${pkg}: transport failure reading overlay hash. Aborting." >&2
        exit 1
    fi
    remote_hash="$(printf '%s' "$remote_hash" | tr -d '\r\n')"

    local pm_path
    if [ "$local_hash" = "$remote_hash" ]; then
        # Hash matches the overlay; check for a stale /data/app shadow.
        pm_path="$(gl_exec "pm path '$pkg' 2>/dev/null")"
        rc=$?
        if [ "$rc" = "$GL_EXEC_TRANSPORT_RC" ]; then
            echo "${pkg}: transport failure reading pm path. Aborting." >&2
            exit 1
        fi
        if printf '%s' "$pm_path" | grep -q '/data/app/'; then
            echo "${pkg} APK unchanged at overlay slot but shadowed by /data/app -- removing shadow."
            gl_exec "pm uninstall '$pkg'" >/dev/null 2>&1 || true
            return 0
        fi
        echo "${pkg} APK unchanged at overlay slot, skipping push."
        return 1
    fi

    rc=0
    gl_exec "mkdir -p '$overlay_dir'" >/dev/null || rc=$?
    if [ "$rc" != "0" ]; then
        echo "${pkg}: failed to mkdir overlay dir (rc=$rc). Aborting." >&2
        exit 1
    fi
    if ! gl_push "$apk_local" "$target"; then
        echo "${pkg} OVERLAY PUSH FAILED (in-place rewrite did not stick)." >&2
        exit 1
    fi
    # Drop any /data/app shadow left from older deploys.
    pm_path="$(gl_exec "pm path '$pkg' 2>/dev/null")"
    rc=$?
    if [ "$rc" = "$GL_EXEC_TRANSPORT_RC" ]; then
        echo "${pkg}: transport failure reading pm path after push. Aborting." >&2
        exit 1
    fi
    if printf '%s' "$pm_path" | grep -q '/data/app/'; then
        echo "removing sideloaded $pkg from /data/app"
        gl_exec "pm uninstall '$pkg'" >/dev/null 2>&1 || true
    fi
    echo "${pkg} APK pushed to priv-app overlay slot."
    return 0
}

PRIV_PUSHED=0
push_priv_overlay com.repository.glasses.btmanager "$BT_MGR_APK"  btmanager.apk && PRIV_PUSHED=1
push_priv_overlay com.repository.glasses.filesync  "$FILESYNC_APK" filesync.apk  && PRIV_PUSHED=1
push_priv_overlay com.repository.glasses.listener  "$APP_APK"     listener.apk  && PRIV_PUSHED=1

# ---------------------------------------------------------------------------
# capture has no privapp-permissions XML; plain `pm install -r` of a pushed APK
# is correct (equivalent to `adb install -r` in the USB script).
# ---------------------------------------------------------------------------
# The upload endpoint stages into the filesync app's PRIVATE dir (the only place a
# priv_app can write); the response carries the actual staged path. PackageManager
# (system_server, uid 1000) cannot read that private dir, so we first have appsud
# (root) copy the APK to a world-readable /data/local/tmp path, then `pm install`,
# then remove both copies. The upload staged copy is cleared by the session cleanup.
cap_body="$(_http POST "$BASE_URL/sideload/upload?name=capture-debug.apk" \
        -H 'Content-Type: application/octet-stream' \
        --data-binary "@$CAPTURE_APK")" || { echo "capture upload FAILED." >&2; exit 1; }
cap_staged="$(printf '%s' "$cap_body" | jq -r '.path // empty' 2>/dev/null)"
if [ -z "$cap_staged" ]; then
    echo "capture upload response missing staged path: $cap_body" >&2
    exit 1
fi
# Install scratch lives under /data/local/tmp/sideload-stage (filesync force-wipes this dir via
# the root daemon on every session teardown), so even if this exec is interrupted after the cp
# but before the rm, the copy cannot persist past the sideload session.
CAPTURE_INSTALL="/data/local/tmp/sideload-stage/capture-debug-install.apk"
cap_rc=0
gl_exec "mkdir -p /data/local/tmp/sideload-stage && cp '$cap_staged' '$CAPTURE_INSTALL' && chmod 644 '$CAPTURE_INSTALL' && pm install -r '$CAPTURE_INSTALL'; r=\$?; rm -f '$CAPTURE_INSTALL'; exit \$r" || cap_rc=$?
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
# Reboot if any priv APK changed, so post-fs-data re-binds the overlay APKs and
# PMS rescans /system/priv-app applying the privapp-permissions XMLs.
#
# A reboot WILL drop the WiFi-Direct link. So: issue reboot, close the link,
# then poll reopen + getprop sys.boot_completed until it returns 1 (long budget),
# then verify each pkg resolves to /system/priv-app (not /data/app).
# ---------------------------------------------------------------------------
if [ "$PRIV_PUSHED" = "1" ]; then
    echo "Priv-app APKs updated -- rebooting glasses so bind-mounts and privapp permissions re-apply..."
    # Snapshot the current boot identity BEFORE rebooting. The kernel boot_id is a
    # fresh random UUID per boot, so requiring it to change is the only reliable way
    # to tell a genuinely-rebooted device from a stale pre-reboot WiFi-Direct link
    # that happens to still answer with boot_completed=1 from the OLD boot.
    pre_boot_id="$(gl_exec "cat /proc/sys/kernel/random/boot_id 2>/dev/null")"
    pre_boot_id="$(printf '%s' "$pre_boot_id" | tr -d '\r\n')"
    echo "  pre-reboot boot_id='${pre_boot_id:-<unknown>}'"

    # Fire reboot. The command will likely race with the link drop; ignore its rc
    # since the device is going down regardless.
    gl_exec "reboot" >/dev/null 2>&1 || true

    # The WiFi-Direct group is gone now. Mark session closed and tear down our end.
    SESSION_OPENED=0
    curl -sS -X POST --connect-timeout "$CURL_CONNECT_TIMEOUT" --max-time "$CURL_MAX_TIME" \
        "$BASE_URL/sideload/close" >/dev/null 2>&1 || true

    # Give the device a moment to actually go down before we start polling, so we
    # do not immediately re-open onto the still-up pre-reboot link.
    sleep 10

    # Poll: re-open the link + check boot_completed AND a changed boot_id, up to
    # ~180s total. boot_completed=1 alone is insufficient (see pre_boot_id above).
    boot_ok=""
    boot_deadline=$(( $(date +%s) + 180 ))
    while :; do
        # Try to (re)establish the link; short per-attempt budget so we loop.
        if reopen_session 30; then
            cur_boot_id="$(gl_exec "cat /proc/sys/kernel/random/boot_id 2>/dev/null")"
            cur_boot_id="$(printf '%s' "$cur_boot_id" | tr -d '\r\n')"
            bc="$(gl_exec "getprop sys.boot_completed")"
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
            echo "  link not back yet, retrying..."
        fi
        if [ "$(date +%s)" -ge "$boot_deadline" ]; then
            break
        fi
        sleep 5
    done

    if [ -z "$boot_ok" ]; then
        echo "ERROR: glasses did not finish booting (sys.boot_completed != 1) within budget. Aborting permission grants." >&2
        exit 1
    fi

    # Verify each priv-app package resolves to /system/priv-app (not /data/app).
    # PMS scans /priv-app asynchronously after boot_completed, so retry briefly.
    for pkg in com.repository.glasses.btmanager com.repository.glasses.filesync com.repository.glasses.listener; do
        ok=""
        path=""
        for _ in 1 2 3 4 5 6 7 8 9 10; do
            path="$(gl_exec "pm path '$pkg' 2>/dev/null")"
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
# Runtime grants / appops / system roles. Idempotent. Applied AFTER any reboot so
# newly-rescanned priv-app packages exist in PMS. MUST be kept in sync with
# deploy-to-glasses.sh apply_runtime_grants and root-firmware.sh.
#
# Each line is issued via gl_exec. The grants are best-effort (the USB script
# swallows failures with `|| true`); we mirror that to stay idempotent and
# re-runnable, but still surface a transport failure (phone/link down) loudly.
# ---------------------------------------------------------------------------
gl_exec_grant() {
    # Best-effort single grant; tolerate a nonzero command rc (idempotent grants
    # often "fail" the second time) but abort on a transport failure.
    local cmd="$1"
    local rc=0
    # Capture rc with `|| rc=$?` so a nonzero command result does not trip `set -e`
    # (idempotent grants legitimately return nonzero when already applied).
    gl_exec "$cmd" >/dev/null 2>&1 || rc=$?
    if [ "$rc" = "$GL_EXEC_TRANSPORT_RC" ]; then
        echo "ERROR: transport failure applying grant: $cmd" >&2
        exit 1
    fi
    return 0
}

apply_runtime_grants() {
    # MANAGE_EXTERNAL_STORAGE: capture + filesync write to /sdcard/DCIM/Repository/.
    local pkg perm
    for pkg in com.repository.glasses.capture com.repository.glasses.filesync; do
        gl_exec_grant "appops set $pkg MANAGE_EXTERNAL_STORAGE allow"
    done
    # WRITE_SETTINGS: listener writes screen_brightness; appops since the
    # special-permission UI prompt isn't reachable from a headless service.
    gl_exec_grant "appops set com.repository.glasses.listener WRITE_SETTINGS allow"
    # BLUETOOTH_SCAN/CONNECT: Lone mode scans for nearby BT devices from the listener
    # process. Runtime perms (targetSdk 34); no UI prompt reachable headless.
    for perm in \
        android.permission.BLUETOOTH_SCAN \
        android.permission.BLUETOOTH_CONNECT; do
        gl_exec_grant "pm grant com.repository.glasses.listener $perm"
    done
    # filesync signature-level Wi-Fi perms (rooted firmware accepts pm grant).
    for perm in \
        android.permission.NETWORK_SETTINGS \
        android.permission.OVERRIDE_WIFI_CONFIG \
        android.permission.WRITE_SECURE_SETTINGS; do
        gl_exec_grant "pm grant com.repository.glasses.filesync $perm"
    done
    # filesync runtime location perms (LocalOnlyHotspot prerequisite).
    for perm in \
        android.permission.ACCESS_FINE_LOCATION \
        android.permission.ACCESS_COARSE_LOCATION; do
        gl_exec_grant "pm grant com.repository.glasses.filesync $perm"
    done
    # NLS for MediaSessionMonitor.
    gl_exec_grant "cmd notification allow_listener com.repository.glasses.listener/com.repository.glasses.listener.media.MediaNotificationListener"
    # Accessibility service for screen-off on double-tap. The verify+retry loop
    # below is REQUIRED (a bare settings put right after install no-ops).
    gl_exec_grant "settings put secure enabled_accessibility_services com.repository.glasses.listener/com.repository.glasses.listener.service.ScreenOffAccessibilityService"
    gl_exec_grant "settings put secure accessibility_enabled 1"
    # Default home.
    gl_exec_grant "cmd role add-role-holder android.app.role.HOME com.repository.glasses.listener"
}
apply_runtime_grants
echo "Runtime grants applied."

# ---------------------------------------------------------------------------
# Verify the accessibility service ACTUALLY bound (the one grant that routinely
# fails to stick). Retry the enable until dumpsys lists it, or warn loudly.
# ---------------------------------------------------------------------------
a11y_svc="com.repository.glasses.listener/com.repository.glasses.listener.service.ScreenOffAccessibilityService"
a11y_bound=""
for _ in 1 2 3 4 5 6 7 8; do
    bound="$(gl_exec "dumpsys accessibility | grep -A2 'Enabled services' | grep -o ScreenOffAccessibilityService")"
    bound="$(printf '%s' "$bound" | tr -d '\r\n')"
    if [ -n "$bound" ]; then a11y_bound=1; break; fi
    gl_exec "settings put secure enabled_accessibility_services '$a11y_svc'; settings put secure accessibility_enabled 1" >/dev/null 2>&1 || true
    sleep 2
done
if [ -n "$a11y_bound" ]; then
    echo "Accessibility service bound -- double-tap screen-off live."
else
    echo "WARNING: ScreenOffAccessibilityService did NOT bind after retries -- double-tap screen-off will not work. Re-run the deploy or check listener install." >&2
fi

# ---------------------------------------------------------------------------
# Write BT MAC to a file readable by bt-manager (all ContentProvider/Settings
# approaches blocked by ROM permission checks for app UIDs).
# ---------------------------------------------------------------------------
gl_exec "settings get secure bluetooth_address > /data/local/tmp/glasses_bt_mac && chmod 644 /data/local/tmp/glasses_bt_mac" >/dev/null 2>&1 || true

echo "Done (deploy through phone)."
# EXIT trap performs /sideload/cleanup + /sideload/close.
exit 0
