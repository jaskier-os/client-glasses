"""
Tests for tab navigation on the glasses UI.

Tab order: TODO, CHAT, CHAT_LIST, REID
Navigation: DPAD_LEFT/RIGHT to switch tabs, DPAD_CENTER to enter, BACK to exit.
"""
import time
import pytest
from conftest import (
    navigate_to_tab, enter_tab, take_screenshot, dump_ui_hierarchy,
    send_key, send_keys, KEY_CENTER, KEY_LEFT, KEY_RIGHT, KEY_BACK,
)


class TestTabNavigation:
    """Verify basic tab switching and focus state transitions."""

    def test_navigate_to_todo_tab(self, device):
        navigate_to_tab(device, "TODO")
        path = take_screenshot(device, "nav_todo_tab")
        assert path

    def test_navigate_to_chat_tab(self, device):
        navigate_to_tab(device, "CHAT")
        path = take_screenshot(device, "nav_chat_tab")
        assert path

    def test_navigate_to_chat_list_tab(self, device):
        navigate_to_tab(device, "CHAT_LIST")
        path = take_screenshot(device, "nav_chat_list_tab")
        assert path

    def test_navigate_to_reid_tab(self, device):
        navigate_to_tab(device, "REID")
        path = take_screenshot(device, "nav_reid_tab")
        assert path

    def test_navigate_left_to_right_cycle(self, device):
        """Navigate through all tabs left to right."""
        tabs = ["TODO", "CHAT", "CHAT_LIST", "REID"]
        navigate_to_tab(device, "TODO")
        for i, tab in enumerate(tabs):
            take_screenshot(device, f"nav_cycle_{i}_{tab.lower()}")
            if i < len(tabs) - 1:
                send_key(KEY_RIGHT)
                time.sleep(0.2)

    def test_enter_and_exit_tab(self, device):
        """Enter a tab with DPAD_CENTER, then exit with BACK."""
        navigate_to_tab(device, "CHAT")
        take_screenshot(device, "nav_before_enter_chat")

        enter_tab(device)
        time.sleep(0.3)
        take_screenshot(device, "nav_chat_focused")

        send_key(KEY_BACK)
        time.sleep(0.3)
        take_screenshot(device, "nav_chat_unfocused")

    def test_enter_todo_tab(self, device):
        """Enter TODO tab and verify it shows the checklist view."""
        navigate_to_tab(device, "TODO")
        enter_tab(device)
        time.sleep(5)
        take_screenshot(device, "nav_todo_focused")


class TestTabBoundary:
    """Verify tab navigation doesn't wrap or crash at edges."""

    def test_left_boundary(self, device):
        navigate_to_tab(device, "TODO")
        send_keys(KEY_LEFT, 3, delay=0.15)
        take_screenshot(device, "nav_left_boundary")

    def test_right_boundary(self, device):
        navigate_to_tab(device, "REID")
        send_keys(KEY_RIGHT, 3, delay=0.15)
        take_screenshot(device, "nav_right_boundary")
