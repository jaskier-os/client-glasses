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
        // Cancel first: a holder can be re-added while its previous fade-in is still running, and
        // the second animate() would otherwise not own the alpha it is starting from.
        view.animate().cancel()
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

    /**
     * RecyclerView calls these to abort in-flight animations, e.g. when a row is removed or
     * re-bound mid-fade. Without restoring alpha/translation the holder is recycled still
     * transparent, so the next row that reuses it renders INVISIBLE. Reachable now that the chat
     * list diffs its updates and therefore actually dispatches inserts.
     */
    override fun endAnimation(holder: RecyclerView.ViewHolder) {
        reset(holder)
        super.endAnimation(holder)
    }

    override fun onAnimationFinished(viewHolder: RecyclerView.ViewHolder) {
        reset(viewHolder)
        super.onAnimationFinished(viewHolder)
    }

    private fun reset(holder: RecyclerView.ViewHolder) {
        val view = holder.itemView
        view.animate().cancel()
        view.alpha = 1f
        view.translationY = 0f
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
