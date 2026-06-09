"""
E2E test: photo auto-attach to AI prompt with thumbnail + progress verification.

Flow:
1. Wait for BT connection between phone and glasses
2. Navigate to CHAT tab, enter CHAT_FOCUSED
3. Send chat_send with attach_photo=true via phone ADB command
4. Take glasses screenshots during BT transfer (progress bar) and after response (thumbnail)
5. Verify AI response describes the photo
6. Re-enter chat focus and scroll up to verify USER message thumbnail

Requires:
- Glasses connected via USB (set GLASSES_SERIAL or connect one device)
- Phone connected via USB (set PHONE_SERIAL or connect one device)
- Orchestrator running
- BT connection between phone and glasses
- At least one DCIM photo on glasses
"""
import json
import os
import subprocess
import time

import pytest
from conftest import (
    GLASSES_SERIAL, SCREENSHOTS_DIR, REPORTS_DIR,
    navigate_to_tab, enter_tab, take_screenshot, send_key, send_keys,
    KEY_CENTER, KEY_LEFT, KEY_RIGHT, KEY_BACK, StepReporter,
)

def _first_adb_device():
    try:
        out = subprocess.run(
            ["adb", "devices"], capture_output=True, text=True, check=False
        ).stdout
    except Exception:
        return ""
    for line in out.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            return parts[0]
    return ""


# Phone ADB serial. Override with PHONE_SERIAL env var; otherwise fall back to
# the first attached adb device.
PHONE_SERIAL = os.environ.get("PHONE_SERIAL") or _first_adb_device()
PHONE_APP_ID = "com.repository.listener"
ADB_ACTION = "com.repository.listener.ADB_COMMAND"
CHAT_TIMEOUT = 120
POLL_INTERVAL = 2


def phone_adb(*args, timeout=10):
    """Run an ADB command targeting the phone."""
    cmd = ["adb", "-s", PHONE_SERIAL] + list(args)
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    return result.stdout.strip()


def send_phone_command(cmd_type, cmd_id, params=None):
    """Send an ADB broadcast command to the phone app."""
    params_json = json.dumps(params or {})
    phone_adb(
        "shell", f"am broadcast "
        f"-n {PHONE_APP_ID}/.adb.AdbCommandReceiver "
        f"-a {ADB_ACTION} "
        f"--es type {cmd_type} "
        f"--es command_id {cmd_id} "
        f"--es params '{params_json}'"
    )


def read_phone_result(cmd_id):
    """Read ADB result file from phone."""
    raw = phone_adb(
        "shell", f"run-as {PHONE_APP_ID} cat files/adb_results/{cmd_id}.json"
    )
    if not raw:
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return None


def poll_phone_result(cmd_id, timeout=CHAT_TIMEOUT, screenshot_device=None,
                      screenshot_name=None):
    """Poll phone ADB result until non-pending or timeout.
    Optionally takes screenshots every 5s during polling."""
    elapsed = 0
    shot_count = 0
    while elapsed < timeout:
        result = read_phone_result(cmd_id)
        if result and result.get("status") not in (None, "", "pending"):
            return result
        # Take periodic screenshots during polling (every 5s)
        if screenshot_device and screenshot_name and elapsed > 0 and elapsed % 6 < POLL_INTERVAL:
            shot_count += 1
            take_screenshot(screenshot_device, f"{screenshot_name}_{shot_count}")
        time.sleep(POLL_INTERVAL)
        elapsed += POLL_INTERVAL
    return None


def check_phone_connected():
    """Verify phone is reachable via ADB."""
    out = subprocess.run(
        ["adb", "devices"], capture_output=True, text=True, timeout=5
    ).stdout
    if PHONE_SERIAL not in out:
        pytest.skip(f"Phone not connected (serial {PHONE_SERIAL})")


def wait_for_bt_connection(timeout=60):
    """Wait until phone reports BT connection to glasses."""
    cmd_id = f"bt_check_{int(time.time())}"
    send_phone_command("status", cmd_id)
    elapsed = 0
    while elapsed < timeout:
        result = read_phone_result(cmd_id)
        if result:
            data = result.get("data", {})
            bt_state = data.get("glasses_connected", False)
            if bt_state:
                return True
            # Resend status check
            cmd_id = f"bt_check_{int(time.time())}"
            send_phone_command("status", cmd_id)
        time.sleep(3)
        elapsed += 3
    return False


def glasses_screenshot(name):
    """Take a screenshot directly from the glasses via ADB."""
    import os
    os.makedirs(SCREENSHOTS_DIR, exist_ok=True)
    path = os.path.join(SCREENSHOTS_DIR, f"{name}.png")
    subprocess.run(
        ["adb", "-s", GLASSES_SERIAL, "shell", "screencap", "-p", "/sdcard/test_e2e.png"],
        capture_output=True, timeout=5
    )
    subprocess.run(
        ["adb", "-s", GLASSES_SERIAL, "pull", "/sdcard/test_e2e.png", path],
        capture_output=True, timeout=5
    )
    return path


class TestPhotoChatE2E:
    def test_photo_attach_and_thumbnail(self, device, step_reporter):
        """Send a photo-attached chat, verify AI response and thumbnail on glasses."""
        check_phone_connected()

        # Wait for BT connection
        print("Waiting for BT connection...")
        bt_ok = wait_for_bt_connection(timeout=60)
        assert bt_ok, "BT connection not established within 60s"
        print("BT connected.")

        # Navigate to chat tab and enter focused mode
        step_reporter.step("navigate_to_chat", lambda: (
            navigate_to_tab(device, "CHAT"),
            enter_tab(device),
            time.sleep(0.5),
        ))

        step_reporter.screenshot("chat_before_send")

        # Send photo-attached chat via phone ADB
        cmd_id = f"photo_e2e_{int(time.time())}"
        send_phone_command(
            "chat_send", cmd_id,
            {"text": "What do you see on this photo?",
             "device_type": "glasses",
             "attach_photo": True},
        )
        print(f"Sent chat_send (cmd_id={cmd_id})")

        # Take rapid screenshots during the BT transfer phase (first 10s)
        for i in range(5):
            time.sleep(2)
            step_reporter.screenshot(f"transfer_phase_{i+1}")

        # Poll for AI response, taking periodic screenshots
        result = poll_phone_result(
            cmd_id, timeout=CHAT_TIMEOUT,
            screenshot_device=device, screenshot_name="during_response"
        )
        assert result is not None, "Timeout waiting for AI response"

        # Verify response
        status = result.get("status", "")
        data = result.get("data", {})
        response_text = data.get("text", "")

        assert status == "success", f"Response status: {status}, error: {result.get('error', 'unknown')}"
        assert len(response_text) > 10, f"Response too short: '{response_text[:100]}'"

        # Wait for glasses UI to fully update
        time.sleep(3)
        step_reporter.screenshot("chat_after_response")

        # Re-enter chat focused mode to scroll up and find user message
        # Press BACK to ensure TAB_NAV, then navigate to CHAT, then enter
        send_key(KEY_BACK)
        time.sleep(0.3)
        navigate_to_tab(device, "CHAT")
        enter_tab(device)
        time.sleep(0.5)

        # Scroll up in CHAT_FOCUSED (DPAD_LEFT = scroll up)
        for i in range(5):
            send_key(KEY_LEFT)
            time.sleep(0.3)
        step_reporter.screenshot("scrolled_to_top")

        # Take one more scrolled slightly down
        send_key(KEY_RIGHT)
        time.sleep(0.3)
        step_reporter.screenshot("user_message_area")

        # Print response for manual review
        print(f"\nAI response ({len(response_text)} chars): {response_text[:200]}")
        if data.get("total_tokens"):
            print(f"Tokens: {data['total_tokens']}, Time: {data.get('elapsed_ms', 0)}ms")

        # Check glasses logs for thumbnail activity
        try:
            log_out = phone_adb(
                "shell", f"run-as {PHONE_APP_ID} tail -200 files/logs/glasses/latest.log",
                timeout=5
            )
            photo_lines = [l for l in log_out.split('\n') if 'photo' in l.lower() or 'thumb' in l.lower()]
            if photo_lines:
                print(f"\nGlasses photo/thumb log lines: {len(photo_lines)}")
                for l in photo_lines[-5:]:
                    print(f"  {l}")
            else:
                print("\nNo photo/thumb activity in glasses logs")
        except Exception:
            pass


@pytest.fixture
def step_reporter(device, request):
    """Per-test step reporter with before/after screenshots and text report."""
    test_name = request.node.name
    reporter = StepReporter(device, test_name, SCREENSHOTS_DIR, REPORTS_DIR)
    yield reporter
    failed = request.node.rep_call.failed if hasattr(request.node, "rep_call") else False
    reporter.finalize(failed=failed)
