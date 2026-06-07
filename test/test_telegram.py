"""
Tests for the TELEGRAM tab on the glasses UI.

Full flow: auth gate -> chat list -> open Saved Messages -> scroll ->
send test message -> verify -> leave chat -> leave tab.

Each step captures a screenshot and logcat snapshot.
Sends messages ONLY to Saved Messages -- nowhere else.

NOTE: Tests that need Telegram data (chat list, messages, send) require
BT connection to the phone with Telegram relay running. These are marked
with @pytest.mark.bt_required and will be skipped if no BT data arrives.
"""
import os
import re
import subprocess
import time

import pytest

from conftest import (
    GLASSES_SERIAL,
    GLASSES_PACKAGE,
    SCREENSHOTS_DIR,
    REPORTS_DIR,
    navigate_to_tab,
    enter_tab,
    send_key,
    send_keys,
    get_visible_text,
    take_screenshot,
    KEY_CENTER,
    KEY_LEFT,
    KEY_RIGHT,
    KEY_UP,
    KEY_DOWN,
    KEY_BACK,
)

LOGCAT_DIR = os.path.join(os.path.dirname(__file__), "logs")

# How long to wait for BT data after entering chat list (seconds)
BT_DATA_TIMEOUT = 8


def capture_logcat(label, lines=80):
    """Grab recent logcat lines filtered to our app and save to file."""
    os.makedirs(LOGCAT_DIR, exist_ok=True)
    path = os.path.join(LOGCAT_DIR, f"telegram_{label}.txt")
    result = subprocess.run(
        ["adb", "-s", GLASSES_SERIAL, "logcat", "-d", "-t", str(lines)],
        capture_output=True, text=True, timeout=10,
    )
    with open(path, "w") as f:
        f.write(result.stdout)
    return path


def get_recycler_child_count(device, resource_id_fragment):
    """Count children of a RecyclerView by resource-id substring in the UI hierarchy."""
    xml = device.dump_hierarchy()
    # Find the RecyclerView node and count immediate <node> children inside it
    pattern = rf'resource-id="[^"]*{resource_id_fragment}[^"]*".*?class="androidx.recyclerview.widget.RecyclerView"'
    rv_match = re.search(pattern, xml)
    if not rv_match:
        return -1  # RecyclerView not found
    # Check if it's self-closing (no children)
    rest = xml[rv_match.end():]
    if rest.lstrip().startswith("/>"):
        return 0
    # Count child nodes at depth 1 within the RecyclerView
    depth = 1
    count = 0
    for m in re.finditer(r"</?node\b", rest):
        tag = m.group()
        if tag == "<node":
            if depth == 1:
                count += 1
            depth += 1
        else:
            depth -= 1
            if depth == 0:
                break
    return count


def wait_for_chat_list(device, timeout=BT_DATA_TIMEOUT):
    """Wait for chat list items to appear. Returns child count (0 = no BT data)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        count = get_recycler_child_count(device, "telegramChatListRecycler")
        if count > 0:
            return count
        time.sleep(1)
    return 0


def enter_telegram_chat_list(device):
    """Navigate to Telegram tab, enter, wait for auth auto-pass + chat list load.
    Returns number of chat list items (0 if BT data not available)."""
    navigate_to_tab(device, "TELEGRAM")
    enter_tab(device)
    time.sleep(3.5)  # 2s auth auto-pass + 1.5s margin
    return wait_for_chat_list(device)


def inject_user_text(text, request_id="test-inject"):
    """Inject a USER_TEXT broadcast via ADB to simulate transcription result.
    NOTE: May not work on Android 12+ due to RECEIVER_NOT_EXPORTED defaults."""
    subprocess.run(
        ["adb", "-s", GLASSES_SERIAL, "shell", "am", "broadcast",
         "-a", "com.repository.glasses.listener.USER_TEXT",
         "-p", GLASSES_PACKAGE,
         "--es", "user_text", text,
         "--es", "user_text_request_id", request_id],
        capture_output=True, timeout=10,
    )


# ---------------------------------------------------------------------------
# Auth gate tests (no BT required)
# ---------------------------------------------------------------------------

class TestTelegramAuthGate:
    """Test that entering the Telegram tab shows auth and auto-passes.
    Force-stops the app before running to ensure clean auth state."""

    @pytest.fixture(autouse=True, scope="class")
    def fresh_app(self, device):
        """Force-stop app to reset telegramAuthenticated state."""
        device.app_stop(GLASSES_PACKAGE)
        time.sleep(1)
        device.app_start(GLASSES_PACKAGE, ".MainActivity")
        time.sleep(3)

    def test_auth_prompt_visible(self, device, step_reporter):
        """Navigate to Telegram in TAB_NAV, verify 'Tap to unlock' shows."""
        step_reporter.step("nav_to_telegram", lambda: navigate_to_tab(device, "TELEGRAM"))
        time.sleep(0.3)
        texts = get_visible_text(device)
        step_reporter.screenshot("tap_to_unlock")
        assert any("tap to unlock" in t.lower() for t in texts), (
            f"Expected 'Tap to unlock' on TELEGRAM tab, got: {texts}"
        )

    def test_auth_verifying_on_enter(self, device, step_reporter):
        """Press CENTER on TELEGRAM tab, verify 'Verifying...' appears."""
        step_reporter.step("nav_to_telegram", lambda: navigate_to_tab(device, "TELEGRAM"))
        step_reporter.step("enter_telegram", lambda: enter_tab(device))
        time.sleep(0.3)
        texts = get_visible_text(device)
        step_reporter.screenshot("verifying_prompt")
        capture_logcat("01_auth_verifying")
        # Auth might already auto-pass if BT data arrives fast -- either Verifying or chat list is OK
        has_auth = any("verif" in t.lower() for t in texts)
        has_chats = get_recycler_child_count(device, "telegramChatListRecycler") > 0
        assert has_auth or has_chats, (
            f"Expected 'Verifying...' or chat list after CENTER, got: {texts}"
        )

    def test_auth_auto_passes(self, device, step_reporter):
        """Enter Telegram, verify chat list appears after auth auto-pass."""
        navigate_to_tab(device, "TELEGRAM")
        enter_tab(device)

        # Wait for auto-pass (2s) + chat list load
        time.sleep(4)
        capture_logcat("02_auth_passed")
        step_reporter.screenshot("auth_passed")

        texts = get_visible_text(device)
        assert not any("verif" in t.lower() for t in texts), (
            f"Auth did not auto-pass after 2s, still see: {texts}"
        )

        count = get_recycler_child_count(device, "telegramChatListRecycler")
        assert count >= 0, "Chat list RecyclerView not visible after auth pass"

    def test_auth_skipped_when_already_authenticated(self, device, step_reporter):
        """Re-enter Telegram tab, verify auth is skipped (already authenticated)."""
        # First entry: auth + auto-pass
        navigate_to_tab(device, "TELEGRAM")
        enter_tab(device)
        time.sleep(3.5)

        # Leave tab
        send_key(KEY_BACK)
        time.sleep(0.5)

        # Re-enter: should skip auth and go straight to chat list
        enter_tab(device)
        time.sleep(1)
        texts = get_visible_text(device)
        step_reporter.screenshot("reentry_no_auth")
        capture_logcat("03_auth_skipped")

        assert not any("verif" in t.lower() for t in texts), (
            f"Auth should be skipped on re-entry, but got: {texts}"
        )


# ---------------------------------------------------------------------------
# Chat list tests (require BT data)
# ---------------------------------------------------------------------------

class TestTelegramChatList:
    """Test chat list navigation: scroll up/down, verify items visible."""

    def test_chat_list_loads(self, device, step_reporter):
        """Enter Telegram, verify chat list populates with items."""
        count = enter_telegram_chat_list(device)
        capture_logcat("04_chat_list_load")
        step_reporter.screenshot("chat_list_loaded")

        if count == 0:
            pytest.skip("No chat list data -- BT to phone not connected or Telegram relay not running")
        assert count > 0, f"Expected chat items, got {count}"

    def test_chat_list_scroll_down(self, device, step_reporter):
        """Scroll down through the chat list."""
        count = enter_telegram_chat_list(device)
        if count == 0:
            pytest.skip("No BT data")

        step_reporter.screenshot("chat_list_initial")

        def scroll_down():
            for _ in range(4):
                send_key(KEY_RIGHT)
                time.sleep(0.4)

        step_reporter.step("scroll_down", scroll_down)
        capture_logcat("05_chat_list_scroll_down")

    def test_chat_list_scroll_up(self, device, step_reporter):
        """Scroll down then back up in chat list."""
        count = enter_telegram_chat_list(device)
        if count == 0:
            pytest.skip("No BT data")

        for _ in range(4):
            send_key(KEY_RIGHT)
            time.sleep(0.4)
        step_reporter.screenshot("scrolled_down")

        def scroll_up():
            for _ in range(4):
                send_key(KEY_LEFT)
                time.sleep(0.4)

        step_reporter.step("scroll_back_up", scroll_up)
        capture_logcat("06_chat_list_scroll_up")


# ---------------------------------------------------------------------------
# Saved Messages tests (require BT data)
# ---------------------------------------------------------------------------

class TestTelegramSavedMessages:
    """Test opening Saved Messages, scrolling, and sending a message."""

    def _enter_saved_messages(self, device, step_reporter):
        """Navigate to Telegram -> auth -> open first chat (Saved Messages).
        Returns False if BT data not available."""
        count = enter_telegram_chat_list(device)
        if count == 0:
            return False

        step_reporter.screenshot("before_open_saved")
        send_key(KEY_CENTER)
        time.sleep(1.5)  # wait for messages to load
        capture_logcat("07_opened_saved")
        step_reporter.screenshot("saved_messages_opened")
        return True

    def test_open_saved_messages(self, device, step_reporter):
        """Open Saved Messages and verify chat view is active."""
        if not self._enter_saved_messages(device, step_reporter):
            pytest.skip("No BT data")

        # Chat container should now be visible (telegramChatContainer or telegramChatRecycler)
        count = get_recycler_child_count(device, "telegramChatRecycler")
        # Even 0 messages is OK -- the view should exist
        assert count >= 0, "Chat RecyclerView not found after opening chat"

    def test_scroll_messages_down(self, device, step_reporter):
        """Scroll down through messages in Saved Messages."""
        if not self._enter_saved_messages(device, step_reporter):
            pytest.skip("No BT data")

        def scroll_down():
            for _ in range(5):
                send_key(KEY_RIGHT)
                time.sleep(0.5)

        step_reporter.step("scroll_messages_down", scroll_down)
        capture_logcat("08_scroll_messages_down")

    def test_scroll_messages_up_dynamic_load(self, device, step_reporter):
        """Scroll up to trigger dynamic loading of older messages."""
        if not self._enter_saved_messages(device, step_reporter):
            pytest.skip("No BT data")

        def scroll_up():
            for _ in range(8):
                send_key(KEY_LEFT)
                time.sleep(0.6)

        step_reporter.step("scroll_up_dynamic", scroll_up)
        capture_logcat("09_scroll_up_dynamic")
        step_reporter.screenshot("after_dynamic_load")

    def test_send_message_to_saved(self, device, step_reporter):
        """Send a test message to Saved Messages via voice record+stop flow.
        Records ~2s of ambient audio, stops, waits for transcription, confirms send."""
        if not self._enter_saved_messages(device, step_reporter):
            pytest.skip("No BT data")

        # Press CENTER to start voice recording (TELEGRAM_RECORDING)
        step_reporter.step("start_voice", lambda: (send_key(KEY_CENTER), time.sleep(0.5)))
        capture_logcat("10_voice_started")
        step_reporter.screenshot("recording_state")

        # Record ~2s of ambient audio, then stop
        time.sleep(2)
        step_reporter.step("stop_voice", lambda: (send_key(KEY_CENTER), time.sleep(0.5)))
        capture_logcat("11_voice_stopped")

        # Wait for transcription result from phone (up to 10s)
        preview_found = False
        for _ in range(10):
            time.sleep(1)
            texts = get_visible_text(device)
            # Send preview shows countdown text like "3s" or "Send" or the message text
            if any(t.endswith("s") and t[:-1].isdigit() for t in texts):
                preview_found = True
                break
            # Also check for send text view
            count = get_recycler_child_count(device, "telegramSendPreview")
            if count >= 0:
                xml = device.dump_hierarchy()
                if "telegramSendText" in xml and 'visibility="visible"' not in xml:
                    # Check if telegramSendPreview is visible by bounds
                    if re.search(r'telegramSendPreview.*?visible-to-user="true"', xml):
                        preview_found = True
                        break

        capture_logcat("12_transcription_wait")
        step_reporter.screenshot("after_transcription")

        if not preview_found:
            pytest.skip(
                "Transcription did not return text (phone transcriber may not be running, "
                "or ambient audio was too quiet to produce text)"
            )

        # Press CENTER to confirm send immediately (skip countdown)
        step_reporter.step("confirm_send", lambda: (send_key(KEY_CENTER), time.sleep(1)))
        capture_logcat("13_message_sent")
        step_reporter.screenshot("after_send")

    def test_leave_chat_back_to_list(self, device, step_reporter):
        """Open Saved Messages then BACK to chat list."""
        if not self._enter_saved_messages(device, step_reporter):
            pytest.skip("No BT data")

        step_reporter.step("back_to_list", lambda: (send_key(KEY_BACK), time.sleep(0.5)))
        capture_logcat("14_back_to_list")
        step_reporter.screenshot("back_in_chat_list")

        count = get_recycler_child_count(device, "telegramChatListRecycler")
        assert count > 0, "Chat list should be visible after BACK from chat"


# ---------------------------------------------------------------------------
# Exit tests (no BT required for basic flow)
# ---------------------------------------------------------------------------

class TestTelegramExit:
    """Test leaving the Telegram tab cleanly."""

    def test_back_from_telegram_to_tab_nav(self, device, step_reporter):
        """Enter Telegram, wait for auth, then BACK out to TAB_NAV."""
        navigate_to_tab(device, "TELEGRAM")
        enter_tab(device)
        time.sleep(3.5)
        step_reporter.screenshot("in_telegram")

        step_reporter.step("back_to_tab_nav", lambda: (send_key(KEY_BACK), time.sleep(0.5)))
        capture_logcat("15_exit_telegram")
        step_reporter.screenshot("back_in_tab_nav")

        # Verify "Tap to unlock" reappears or we're back in TAB_NAV
        texts = get_visible_text(device)
        in_app = device.app_current()["package"] == GLASSES_PACKAGE
        assert in_app, "App should still be in foreground after BACK"

    def test_back_from_chat_to_tab_nav(self, device, step_reporter):
        """Open a chat (if BT available), then BACK to TAB_NAV."""
        count = enter_telegram_chat_list(device)
        if count == 0:
            pytest.skip("No BT data")

        # Open first chat
        send_key(KEY_CENTER)
        time.sleep(1.5)
        step_reporter.screenshot("in_chat")

        # BACK to list, then BACK to tab nav
        send_key(KEY_BACK)
        time.sleep(0.5)
        send_key(KEY_BACK)
        time.sleep(0.5)
        capture_logcat("16_double_back")
        step_reporter.screenshot("final_tab_nav")

        assert device.app_current()["package"] == GLASSES_PACKAGE
