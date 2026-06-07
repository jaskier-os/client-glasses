package com.repository.glasses.listener.ui

import android.animation.ValueAnimator
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JobDisplayAdapter : RecyclerView.Adapter<JobDisplayAdapter.JobViewHolder>(), SelectableAdapter {

    companion object {
        private fun Int.dpToPx(): Int =
            (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()

        private fun Float.dpToPx(): Float =
            this * Resources.getSystem().displayMetrics.density

        private val timeFormat = SimpleDateFormat("MMM d HH:mm", Locale.US)
    }

    private val items = mutableListOf<JobDisplayItem>()
    override var selectedPosition: Int = -1
        private set
    var focused: Boolean = false
        private set

    override val adapterItemCount: Int get() = items.size

    class JobViewHolder(
        val container: LinearLayout,
        val nameView: TextView,
        val statusView: TextView,
        val timeView: TextView
    ) : RecyclerView.ViewHolder(container) {
        var borderAnimator: ValueAnimator? = null
        var currentDrawable: GradientDrawable? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): JobViewHolder {
        val nameView = TextView(parent.context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Lum.BRIGHT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setBackgroundColor(Lum.VOID)
        }

        val statusView = TextView(parent.context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setBackgroundColor(Lum.VOID)
        }

        val timeView = TextView(parent.context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Lum.DIM)
            setBackgroundColor(Lum.VOID)
        }

        val container = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 4.dpToPx()
            }
            setPadding(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
            setBackgroundColor(Lum.VOID)
            addView(nameView, LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = 8.dpToPx()
            })
            addView(statusView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8.dpToPx()
            })
            addView(timeView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        return JobViewHolder(container, nameView, statusView, timeView)
    }

    override fun onBindViewHolder(holder: JobViewHolder, position: Int) {
        val item = items[position]

        holder.nameView.text = item.name.ifEmpty { "Job" }
        holder.nameView.setTextColor(Lum.BRIGHT)

        holder.statusView.text = item.status
        holder.statusView.setTextColor(when (item.status) {
            "completed" -> Lum.GLOW
            "running" -> Lum.BRIGHT
            "pending" -> Lum.DIM
            "needs_input" -> Lum.BRIGHT
            else -> Lum.MID
        })

        holder.timeView.text = if (item.scheduledAt > 0) {
            timeFormat.format(Date(item.scheduledAt))
        } else {
            ""
        }

        holder.borderAnimator?.cancel()

        if (focused && position == selectedPosition) {
            animateBorder(holder.container) { drawable, animator ->
                holder.currentDrawable = drawable
                holder.borderAnimator = animator
            }
        } else {
            holder.currentDrawable = null
            holder.container.setBackgroundColor(Lum.VOID)
        }
    }

    private fun animateBorder(
        container: LinearLayout,
        onCreated: (GradientDrawable, ValueAnimator) -> Unit
    ) {
        val drawable = GradientDrawable().apply {
            setColor(Lum.VOID)
            cornerRadius = 8f.dpToPx()
            setStroke(0, Lum.GHOST)
        }
        container.background = drawable

        val targetWidth = 1.dpToPx()
        val animator = ValueAnimator.ofInt(0, targetWidth).apply {
            duration = 150L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                drawable.setStroke(it.animatedValue as Int, Lum.GHOST)
            }
            start()
        }
        onCreated(drawable, animator)
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<JobDisplayItem>) {
        items.clear()
        items.addAll(newItems)
        if (selectedPosition >= items.size) {
            selectedPosition = if (items.isNotEmpty()) 0 else -1
        }
        notifyDataSetChanged()
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
        if (selectedPosition in items.indices) notifyItemChanged(selectedPosition)
    }

    fun getSelectedItem(): JobDisplayItem? {
        return if (selectedPosition in items.indices) items[selectedPosition] else null
    }
}
