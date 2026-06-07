package com.repository.glasses.listener.ui

import android.content.res.Resources
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * Custom item animator: new messages materialize by fading in and sliding up.
 * Changes (streaming updates) have no animation to avoid flicker.
 */
class MessageItemAnimator : DefaultItemAnimator() {

    companion object {
        private const val ADD_DURATION = 250L
        private fun Float.dpToPx(): Float =
            this * Resources.getSystem().displayMetrics.density
    }

    private val decelerate = DecelerateInterpolator()

    init {
        addDuration = ADD_DURATION
        changeDuration = 0L  // streaming updates should be instant
        removeDuration = 150L
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        val view = holder.itemView
        view.alpha = 0f
        view.translationY = 8f.dpToPx()

        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ADD_DURATION)
            .setInterpolator(decelerate)
            .withEndAction { dispatchAddFinished(holder) }
            .start()

        return true
    }

    override fun animateChange(
        oldHolder: RecyclerView.ViewHolder,
        newHolder: RecyclerView.ViewHolder,
        fromLeft: Int, fromTop: Int,
        toLeft: Int, toTop: Int
    ): Boolean {
        // No animation for content updates (streaming) -- just snap
        if (newHolder.itemView.alpha != 1f) {
            newHolder.itemView.alpha = 1f
        }
        newHolder.itemView.translationY = 0f
        dispatchChangeFinished(newHolder, false)
        if (oldHolder !== newHolder) {
            dispatchChangeFinished(oldHolder, true)
        }
        return false
    }
}
