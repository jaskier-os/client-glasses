"""
Glasses UI test suite -- pytest configuration and shared fixtures.

Uses uiautomator2 for screenshots/app detection and raw ADB for key input.
u2's press() is unreliable on Rokid glasses -- raw adb input keyevent works.
Device must be connected via USB cable (adb devices shows it).
"""
import os
import subprocess
import time
import traceback
from datetime import datetime

import pytest
import uiautomator2 as u2

GLASSES_SERIAL = "1901092534009177"
GLASSES_PACKAGE = "com.repository.glasses.listener"
SCREENSHOTS_DIR = os.path.join(os.path.dirname(__file__), "screenshots")
REPORTS_DIR = os.path.join(os.path.dirname(__file__), "reports")

# Key codes
KEY_BACK = 4
KEY_LEFT = 21
KEY_RIGHT = 22
KEY_CENTER = 23
KEY_UP = 19
KEY_DOWN = 20


def send_key(keycode):
    """Send a keyevent via raw ADB. More reliable than u2 on Rokid glasses."""
    subprocess.run(
        ["adb", "-s", GLASSES_SERIAL, "shell", "input", "keyevent", str(keycode)],
        capture_output=True, timeout=5
    )


def send_keys(keycode, count, delay=0.2):
    """Send a keyevent multiple times with delay between presses."""
    for _ in range(count):
        send_key(keycode)
        time.sleep(delay)


@pytest.fixture(scope="session")
def device():
    """Connect to glasses device via ADB. Session-scoped -- one connection for all tests."""
    d = u2.connect(GLASSES_SERIAL)
    info = d.info
    assert info["displayWidth"] == 480 and info["displayHeight"] == 640, (
        f"Unexpected display size: {info['displayWidth']}x{info['displayHeight']}"
    )
    yield d


@pytest.fixture(scope="session")
def ensure_app_foreground(device):
    """Ensure our app is in the foreground at session start."""
    current = device.app_current()
    if current["package"] != GLASSES_PACKAGE:
        device.app_start(GLASSES_PACKAGE)
        time.sleep(2)
    return device


def _ensure_in_app(device):
    """Check we're in our app. If not, relaunch it."""
    current = device.app_current()
    if current["package"] != GLASSES_PACKAGE:
        device.app_start(GLASSES_PACKAGE, ".MainActivity")
        time.sleep(2)


@pytest.fixture(autouse=True)
def reset_to_tab_nav(device, ensure_app_foreground):
    """Before each test, press BACK until we're in TAB_NAV focus state.
    Checks after each press whether we've exited the app."""
    for _ in range(3):
        current = device.app_current()
        if current["package"] != GLASSES_PACKAGE:
            _ensure_in_app(device)
            break
        send_key(KEY_BACK)
        time.sleep(0.2)
    _ensure_in_app(device)
    time.sleep(0.3)


def take_screenshot(device, name):
    """Take a screenshot and save it with a descriptive name.
    Returns the file path for assertion/viewing."""
    os.makedirs(SCREENSHOTS_DIR, exist_ok=True)
    path = os.path.join(SCREENSHOTS_DIR, f"{name}.png")
    device.screenshot(path)
    return path


def navigate_to_tab(device, target_tab, current_tab=None):
    """Navigate to a specific tab using DPAD_LEFT/RIGHT from TAB_NAV state.

    Tab order (left to right): MUSIC, TODO, CHAT, CHAT_LIST, TELEGRAM, REID
    Default tabs are always present. Dynamic tabs (MAP, TRANSLATE, etc.) may be added.

    Note: SCROLL_THROTTLE_MS in MainActivity is 300ms. All key delays must exceed this.

    Args:
        device: u2 device instance
        target_tab: one of 'MUSIC', 'TODO', 'CHAT', 'CHAT_LIST', 'TELEGRAM', 'REID'
        current_tab: if known, skip detection. Otherwise navigates from leftmost.
    """
    tab_order = ["MUSIC", "TODO", "CHAT", "CHAT_LIST", "TELEGRAM", "REID"]
    assert target_tab in tab_order, f"Unknown tab: {target_tab}"

    if current_tab is None:
        send_keys(KEY_LEFT, 6, delay=0.35)
        current_idx = 0
    else:
        current_idx = tab_order.index(current_tab)

    target_idx = tab_order.index(target_tab)
    diff = target_idx - current_idx

    key = KEY_RIGHT if diff > 0 else KEY_LEFT
    send_keys(key, abs(diff), delay=0.35)
    time.sleep(0.3)


def enter_tab(device):
    """Press DPAD_CENTER to enter the currently selected tab (TAB_NAV -> *_FOCUSED)."""
    send_key(KEY_CENTER)
    time.sleep(0.4)


def dump_ui_hierarchy(device):
    """Dump the current UI hierarchy XML. Useful for debugging element locations."""
    return device.dump_hierarchy()


def get_visible_text(device):
    """Extract all visible text from the current screen."""
    import re
    xml = device.dump_hierarchy()
    texts = re.findall(r'text="([^"]+)"', xml)
    return [t for t in texts if t.strip()]


# ---------------------------------------------------------------------------
# StepReporter -- per-step screenshot and text report generation
# ---------------------------------------------------------------------------

class StepReporter:
    """Records test steps with before/after screenshots and generates text reports."""

    def __init__(self, device, test_name, screenshots_dir, reports_dir):
        self.device = device
        self.test_name = test_name
        self.screenshots_dir = screenshots_dir
        self.reports_dir = reports_dir
        self.steps = []
        self.start_time = time.time()
        self._step_counter = 0
        os.makedirs(screenshots_dir, exist_ok=True)
        os.makedirs(reports_dir, exist_ok=True)

    def _screenshot_path(self, label):
        return os.path.join(
            self.screenshots_dir,
            f"{self.test_name}_{self._step_counter:02d}_{label}.png",
        )

    def _take_screenshot(self, label):
        path = self._screenshot_path(label)
        self.device.screenshot(path)
        return path

    def step(self, name, action=None):
        """Execute a named step with before/after screenshots.

        Args:
            name: human-readable step name (used in report and filenames)
            action: callable to execute between screenshots. If None, just takes a screenshot.
        """
        self._step_counter += 1
        step_start = time.time()
        step_record = {
            "index": self._step_counter,
            "name": name,
            "status": "PASSED",
            "before": None,
            "after": None,
            "error": None,
            "duration": 0,
        }

        step_record["before"] = self._take_screenshot(f"{name}_before")

        if action is not None:
            try:
                action()
            except Exception:
                step_record["status"] = "FAILED"
                step_record["error"] = traceback.format_exc()
                step_record["after"] = self._take_screenshot(f"{name}_error")
                step_record["duration"] = time.time() - step_start
                self.steps.append(step_record)
                raise

        step_record["after"] = self._take_screenshot(f"{name}_after")
        step_record["duration"] = time.time() - step_start
        self.steps.append(step_record)

    def screenshot(self, name):
        """Take a standalone screenshot without an action."""
        self._step_counter += 1
        path = self._take_screenshot(name)
        self.steps.append({
            "index": self._step_counter,
            "name": name,
            "status": "SCREENSHOT",
            "before": path,
            "after": None,
            "error": None,
            "duration": 0,
        })
        return path

    def finalize(self, failed=False):
        """Write text report to reports/{test_name}.txt."""
        duration = time.time() - self.start_time
        status = "FAILED" if failed else "PASSED"
        total = len(self.steps)

        lines = [
            f"TEST: {self.test_name}",
            f"DATE: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
            f"STATUS: {status}",
            f"DURATION: {duration:.1f}s",
            "",
            "STEPS:",
        ]

        for s in self.steps:
            idx = s["index"]
            name = s["name"]
            st = s["status"]
            dur = s["duration"]
            pad = "." * max(1, 40 - len(name))
            lines.append(f"  [{idx}/{total}] {name} {pad} {st} ({dur:.1f}s)")
            if s["before"]:
                label = "File" if st == "SCREENSHOT" else "Before"
                lines.append(f"         {label}: {os.path.basename(s['before'])}")
            if s["after"]:
                lines.append(f"         After:  {os.path.basename(s['after'])}")
            if s["error"]:
                for err_line in s["error"].strip().split("\n"):
                    lines.append(f"         ! {err_line}")

        report_path = os.path.join(self.reports_dir, f"{self.test_name}.txt")
        with open(report_path, "w") as f:
            f.write("\n".join(lines) + "\n")
        return report_path


@pytest.fixture
def step_reporter(device, request):
    """Per-test step reporter with before/after screenshots and text report."""
    test_name = request.node.name
    reporter = StepReporter(device, test_name, SCREENSHOTS_DIR, REPORTS_DIR)
    yield reporter
    failed = request.node.rep_call.failed if hasattr(request.node, "rep_call") else False
    reporter.finalize(failed=failed)


@pytest.hookimpl(tryfirst=True, hookwrapper=True)
def pytest_runtest_makereport(item, call):
    """Store test result on the item for the step_reporter fixture to read."""
    outcome = yield
    rep = outcome.get_result()
    setattr(item, f"rep_{rep.when}", rep)
