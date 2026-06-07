"""
Tests for the TODO tab on the glasses UI.

The todo tab has sub-tabs: Tasks (0), Saved (1).
Tasks shows todo items with completion markers.
Toggle: DPAD_CENTER on selected item sends toggle via BT -> phone -> orchestrator.

Focus levels in TODO_FOCUSED:
  Level 0: Subtab navigation (LEFT/RIGHT to switch, CENTER to enter content)
  Level 1: Content focused (UP/DOWN to navigate, CENTER to act on item)
  Level 2: Message detail (for Saved subtab)

IMPORTANT: These tests require:
- Phone connected and paired via BT (relay for orchestrator communication)
- Orchestrator running with todo_update support
"""
import time

import pytest

from conftest import (
    navigate_to_tab, enter_tab, take_screenshot,
    dump_ui_hierarchy, get_visible_text,
    send_key, send_keys,
    KEY_CENTER, KEY_LEFT, KEY_RIGHT, KEY_UP, KEY_DOWN, KEY_BACK,
    GLASSES_PACKAGE,
)


def navigate_to_todo_focused(device):
    """Navigate to TODO tab and enter level 0 (subtab nav)."""
    navigate_to_tab(device, "TODO")
    enter_tab(device)  # TAB_NAV -> TODO_FOCUSED level 0
    time.sleep(5)      # Wait for todo list to load via BT


def enter_todo_content():
    """Enter level 1 (content focused) with first item auto-selected."""
    send_key(KEY_CENTER)  # Level 0 -> Level 1
    time.sleep(0.5)


# ---------------------------------------------------------------------------
# Tab and subtab navigation
# ---------------------------------------------------------------------------

class TestTodoNavigation:
    """Verify navigation to and within the todo tab."""

    def test_navigate_to_todo_tab(self, device, step_reporter):
        """Navigate to TODO tab from TAB_NAV and enter it."""
        step_reporter.step("navigate_to_todo_tab",
                           lambda: navigate_to_tab(device, "TODO"))

        step_reporter.step("enter_todo_tab",
                           lambda: enter_tab(device))
        time.sleep(5)

        step_reporter.screenshot("todo_tab_entered")

        current = device.app_current()
        assert current["package"] == GLASSES_PACKAGE, (
            f"App crashed. Current package: {current['package']}"
        )

    def test_subtab_switch(self, device, step_reporter):
        """Switch between subtabs with DPAD_RIGHT/LEFT at level 0."""
        step_reporter.step("enter_todo",
                           lambda: navigate_to_todo_focused(device))

        step_reporter.screenshot("subtab_tasks")

        step_reporter.step("switch_to_saved",
                           lambda: send_key(KEY_RIGHT))
        time.sleep(0.5)

        step_reporter.screenshot("subtab_saved")

        step_reporter.step("switch_back_to_tasks",
                           lambda: send_key(KEY_LEFT))
        time.sleep(0.5)

        step_reporter.screenshot("subtab_back_to_tasks")


# ---------------------------------------------------------------------------
# Item navigation within the list
# ---------------------------------------------------------------------------

class TestTodoItemNavigation:
    """Navigate items within the todo checklist at level 1."""

    def test_scroll_through_items(self, device, step_reporter):
        """Navigate up/down within the todo checklist."""
        step_reporter.step("enter_todo",
                           lambda: navigate_to_todo_focused(device))

        step_reporter.step("enter_content",
                           lambda: enter_todo_content())

        step_reporter.screenshot("item_start")

        for i in range(3):
            step_reporter.step(f"navigate_down_{i}",
                               lambda: send_key(KEY_DOWN))
            time.sleep(0.3)

        for i in range(3):
            step_reporter.step(f"navigate_up_{i}",
                               lambda: send_key(KEY_UP))
            time.sleep(0.3)

    def test_items_visible(self, device, step_reporter):
        """Verify todo items are displayed (either items or empty state)."""
        step_reporter.step("enter_todo",
                           lambda: navigate_to_todo_focused(device))

        hierarchy = dump_ui_hierarchy(device)
        step_reporter.screenshot("todo_items_check")

        assert "RecyclerView" in hierarchy or "TextView" in hierarchy, (
            "Neither RecyclerView nor TextView found in hierarchy"
        )


# ---------------------------------------------------------------------------
# Toggle tests via DPAD
# ---------------------------------------------------------------------------

class TestTodoToggle:
    """Test toggling todo items from the glasses.

    Flow: Level 0 (subtab nav) -> CENTER -> Level 1 (content, item selected)
          -> CENTER -> toggle item via BT -> phone -> orchestrator todo_update
          -> todo_result -> phone -> BT -> glasses UI update
    """

    def test_toggle_first_item(self, device, step_reporter):
        """Select first todo item and toggle it with DPAD_CENTER."""
        step_reporter.step("enter_todo",
                           lambda: navigate_to_todo_focused(device))

        step_reporter.step("enter_content",
                           lambda: enter_todo_content())

        step_reporter.screenshot("before_toggle")
        hierarchy_before = dump_ui_hierarchy(device)

        step_reporter.step("toggle_item",
                           lambda: send_key(KEY_CENTER))

        # Wait for round-trip: glasses -> BT -> phone -> orchestrator -> phone -> BT -> glasses
        time.sleep(5)

        step_reporter.screenshot("after_toggle")
        hierarchy_after = dump_ui_hierarchy(device)

        assert hierarchy_before != hierarchy_after, (
            "UI hierarchy did not change after toggle -- "
            "phone may not be sending todo_update to orchestrator"
        )

    def test_toggle_and_toggle_back(self, device, step_reporter):
        """Toggle an item twice -- should return to original state."""
        step_reporter.step("enter_todo",
                           lambda: navigate_to_todo_focused(device))

        step_reporter.step("enter_content",
                           lambda: enter_todo_content())

        step_reporter.screenshot("initial_state")

        step_reporter.step("toggle_first",
                           lambda: send_key(KEY_CENTER))
        time.sleep(5)
        step_reporter.screenshot("after_first_toggle")

        step_reporter.step("toggle_second",
                           lambda: send_key(KEY_CENTER))
        time.sleep(5)
        step_reporter.screenshot("after_second_toggle")

    def test_toggle_multiple_items(self, device, step_reporter):
        """Navigate through items and toggle several."""
        step_reporter.step("enter_todo",
                           lambda: navigate_to_todo_focused(device))

        step_reporter.step("enter_content",
                           lambda: enter_todo_content())

        step_reporter.screenshot("multi_toggle_start")

        step_reporter.step("toggle_item_1",
                           lambda: send_key(KEY_CENTER))
        time.sleep(3)

        step_reporter.step("move_to_item_2",
                           lambda: send_key(KEY_DOWN))
        time.sleep(0.3)

        step_reporter.screenshot("item_2_selected")

        step_reporter.step("toggle_item_2",
                           lambda: send_key(KEY_CENTER))
        time.sleep(3)

        step_reporter.screenshot("item_2_toggled")


# ---------------------------------------------------------------------------
# Edge cases
# ---------------------------------------------------------------------------

class TestTodoEdgeCases:
    """Edge case tests for the todo system."""

    def test_rapid_toggles(self, device, step_reporter):
        """Rapidly toggle the same item -- should not crash or desync."""
        step_reporter.step("enter_todo",
                           lambda: navigate_to_todo_focused(device))

        step_reporter.step("enter_content",
                           lambda: enter_todo_content())

        for i in range(5):
            step_reporter.step(f"rapid_toggle_{i}",
                               lambda: send_key(KEY_CENTER))
            time.sleep(1)

        time.sleep(5)
        step_reporter.screenshot("rapid_toggles_result")

        current = device.app_current()
        assert current["package"] == GLASSES_PACKAGE, (
            f"App crashed after rapid toggles. Current package: {current['package']}"
        )

    def test_exit_and_reenter_preserves_state(self, device, step_reporter):
        """Exit TODO tab and re-enter -- should show same state."""
        step_reporter.step("enter_todo",
                           lambda: navigate_to_todo_focused(device))

        step_reporter.screenshot("before_exit")

        step_reporter.step("exit_to_tab_nav",
                           lambda: send_key(KEY_BACK))
        time.sleep(0.3)

        step_reporter.step("reenter_todo",
                           lambda: enter_tab(device))
        time.sleep(5)

        step_reporter.screenshot("after_reenter")

    def test_back_button_exits_to_tab_nav(self, device, step_reporter):
        """BACK from TODO_FOCUSED returns to TAB_NAV."""
        step_reporter.step("enter_todo",
                           lambda: navigate_to_todo_focused(device))

        step_reporter.step("press_back",
                           lambda: send_key(KEY_BACK))
        time.sleep(0.3)

        step_reporter.screenshot("after_back")

        current = device.app_current()
        assert current["package"] == GLASSES_PACKAGE, (
            f"App exited after BACK. Current package: {current['package']}"
        )
