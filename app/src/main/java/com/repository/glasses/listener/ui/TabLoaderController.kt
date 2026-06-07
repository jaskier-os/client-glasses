package com.repository.glasses.listener.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import com.repository.glasses.listener.MainActivity.TabId

// Data jobs are not cancelled on tab switch -- only the visual spinner is suppressed when its owner tab is not active.
class TabLoaderController(
    private val context: Context,
    private val container: FrameLayout,
    private val currentTab: () -> TabId
) {
    private val pending = mutableSetOf<TabId>()
    private var view: SpinnerView? = null
    private var renderedFor: TabId? = null

    fun show(owner: TabId = currentTab()) {
        pending += owner
        render()
    }

    fun hide(owner: TabId = currentTab()) {
        pending -= owner
        render()
    }

    fun hideAll() {
        pending.clear()
        render()
    }

    fun onTabSwitched() {
        render()
    }

    private fun render() {
        val tab = currentTab()
        val want = tab in pending
        if (want && renderedFor != tab) attach(tab)
        else if (!want && renderedFor != null) detach()
    }

    private fun attach(forTab: TabId) {
        detach()
        val s = SpinnerView(context, 16)
        s.setBackgroundColor(Lum.VOID)
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER }
        container.addView(s, lp)
        s.alpha = 0f
        Anim.fadeIn(s, 150L)
        s.start()
        view = s
        renderedFor = forTab
    }

    private fun detach() {
        val s = view ?: return
        view = null
        renderedFor = null
        s.stop()
        Anim.fadeOut(s, 100L) {
            (s.parent as? ViewGroup)?.removeView(s)
        }
    }
}
