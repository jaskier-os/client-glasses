"""
Tests for the CHAT and CHAT_LIST tabs on the glasses UI.

CHAT tab: Shows conversation messages. DPAD_RIGHT = scroll down, DPAD_LEFT = scroll up.
CHAT_LIST tab: Shows list of conversations. DPAD_UP/DOWN to navigate, DPAD_CENTER to select.
"""
import time
import pytest
from conftest import (
    navigate_to_tab, enter_tab, take_screenshot, dump_ui_hierarchy,
    send_key, KEY_CENTER, KEY_LEFT, KEY_RIGHT, KEY_UP, KEY_DOWN, KEY_BACK,
)


class TestChatTab:
    def test_chat_tab_renders(self, device):
        navigate_to_tab(device, "CHAT")
        enter_tab(device)
        time.sleep(0.5)
        take_screenshot(device, "chat_display")
        assert device.app_current()["package"] == "com.repository.glasses.listener"

    def test_chat_scroll_down(self, device):
        navigate_to_tab(device, "CHAT")
        enter_tab(device)
        time.sleep(0.5)
        take_screenshot(device, "chat_before_scroll")
        for _ in range(3):
            send_key(KEY_RIGHT)
            time.sleep(0.3)
        take_screenshot(device, "chat_after_scroll_down")

    def test_chat_scroll_up(self, device):
        navigate_to_tab(device, "CHAT")
        enter_tab(device)
        time.sleep(0.5)
        for _ in range(3):
            send_key(KEY_RIGHT)
            time.sleep(0.3)
        take_screenshot(device, "chat_scrolled_down")
        for _ in range(3):
            send_key(KEY_LEFT)
            time.sleep(0.3)
        take_screenshot(device, "chat_scrolled_back_up")


class TestChatListTab:
    def test_chat_list_renders(self, device):
        navigate_to_tab(device, "CHAT_LIST")
        enter_tab(device)
        time.sleep(0.5)
        take_screenshot(device, "chat_list_display")
        assert device.app_current()["package"] == "com.repository.glasses.listener"

    def test_chat_list_navigate_items(self, device):
        navigate_to_tab(device, "CHAT_LIST")
        enter_tab(device)
        time.sleep(0.5)
        take_screenshot(device, "chat_list_item_0")
        for i in range(3):
            send_key(KEY_DOWN)
            time.sleep(0.3)
            take_screenshot(device, f"chat_list_item_{i + 1}")

    def test_chat_list_select_conversation(self, device):
        navigate_to_tab(device, "CHAT_LIST")
        enter_tab(device)
        time.sleep(0.5)
        send_key(KEY_CENTER)
        time.sleep(1)
        take_screenshot(device, "chat_list_selected")
        send_key(KEY_BACK)
        time.sleep(0.3)
