package com.repository.glasses.listener.ui

import android.animation.ValueAnimator
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TelegramTopicListAdapter : RecyclerView.Adapter<TelegramTopicListAdapter.TopicViewHolder>(), SelectableAdapter {

    companion object {
        private fun Int.dpToPx(): Int =
            (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()

        private fun Float.dpToPx(): Float =
            this * Resources.getSystem().displayMetrics.density
    }

    private val items = mutableListOf<TelegramTopic>()
    override var selectedPosition: Int = -1
        private set
    var focused: Boolean = false
        private set

    override val adapterItemCount: Int get() = items.size

    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        recyclerView = rv
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        super.onDetachedFromRecyclerView(rv)
        recyclerView = null
    }

    class TopicViewHolder(
        val container: LinearLayout,
        val titleView: TextView,
        val badgeView: TextView
    ) : RecyclerView.ViewHolder(container) {
        var borderAnimator: ValueAnimator? = null
        var currentDrawable: GradientDrawable? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TopicViewHolder {
        val titleView = TextView(parent.context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Lum.BRIGHT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val badgeView = TextView(parent.context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setTextColor(Lum.GLOW)
            gravity = Gravity.CENTER
            val bg = GradientDrawable().apply {
                setColor(Lum.TRACE)
                cornerRadius = 6f.dpToPx()
            }
            background = bg
            setPadding(4.dpToPx(), 1.dpToPx(), 4.dpToPx(), 1.dpToPx())
        }

        val container = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 3.dpToPx()
            }
            setPadding(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
            setBackgroundColor(Lum.VOID)

            addView(titleView, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            addView(badgeView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 4.dpToPx()
            })
        }

        return TopicViewHolder(container, titleView, badgeView)
    }

    override fun onBindViewHolder(holder: TopicViewHolder, position: Int) {
        val item = items[position]
        holder.titleView.text = item.title

        if (item.unreadCount > 0) {
            holder.badgeView.text = if (item.unreadCount > 99) "99+" else item.unreadCount.toString()
            holder.badgeView.visibility = View.VISIBLE
        } else {
            holder.badgeView.visibility = View.GONE
        }

        holder.borderAnimator?.cancel()
        if (focused && position == selectedPosition) {
            val drawable = GradientDrawable().apply {
                setColor(Lum.VOID)
                cornerRadius = 8f.dpToPx()
                setStroke(0, Lum.GHOST)
            }
            holder.container.background = drawable
            val targetWidth = 1.dpToPx()
            holder.borderAnimator = ValueAnimator.ofInt(0, targetWidth).apply {
                duration = 150L
                interpolator = DecelerateInterpolator()
                addUpdateListener { drawable.setStroke(it.animatedValue as Int, Lum.GHOST) }
                start()
            }
            holder.currentDrawable = drawable
        } else {
            holder.currentDrawable = null
            holder.container.setBackgroundColor(Lum.VOID)
        }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newTopics: List<TelegramTopic>) {
        items.clear()
        items.addAll(newTopics)
        if (selectedPosition < 0 || selectedPosition >= items.size) {
            selectedPosition = if (items.isNotEmpty()) 0 else -1
        }
        notifyDataSetChanged()
    }

    fun getSelectedTopic(): TelegramTopic? {
        return if (selectedPosition in items.indices) items[selectedPosition] else null
    }

    override fun selectPosition(pos: Int) {
        if (pos < 0 || pos >= items.size) return
        val old = selectedPosition
        selectedPosition = pos
        if (old in items.indices) notifyItemChanged(old)
        notifyItemChanged(pos)
    }

    override fun moveSelectionDown() {
        if (items.isEmpty()) return
        if (selectedPosition < items.size - 1) selectPosition(selectedPosition + 1)
    }

    override fun moveSelectionUp() {
        if (items.isEmpty()) return
        if (selectedPosition > 0) selectPosition(selectedPosition - 1)
    }

    override fun setFocused(isFocused: Boolean) {
        if (focused == isFocused) return
        focused = isFocused
        val rv = recyclerView ?: return
        for (i in 0 until rv.childCount) {
            val holder = rv.getChildViewHolder(rv.getChildAt(i)) as? TopicViewHolder ?: continue
            holder.borderAnimator?.cancel()
            if (focused && holder.adapterPosition == selectedPosition) {
                val drawable = GradientDrawable().apply {
                    setColor(Lum.VOID)
                    cornerRadius = 8f.dpToPx()
                    setStroke(1.dpToPx(), Lum.GHOST)
                }
                holder.container.background = drawable
                holder.currentDrawable = drawable
            } else {
                holder.currentDrawable = null
                holder.container.setBackgroundColor(Lum.VOID)
            }
        }
    }
}
