"""
Tests for the REID tab on the glasses UI.
"""
import time
import pytest
from conftest import (
    navigate_to_tab, enter_tab, take_screenshot,
    send_key, KEY_BACK,
)


class TestReidTab:
    def test_reid_tab_renders(self, device):
        navigate_to_tab(device, "REID")
        enter_tab(device)
        time.sleep(0.5)
        take_screenshot(device, "reid_display")
        assert device.app_current()["package"] == "com.repository.glasses.listener"

    def test_reid_tab_exit(self, device):
        navigate_to_tab(device, "REID")
        enter_tab(device)
        time.sleep(0.5)
        take_screenshot(device, "reid_focused")
        send_key(KEY_BACK)
        time.sleep(0.3)
        take_screenshot(device, "reid_unfocused")
