package com.repository.glasses.listener.ui

import android.animation.ValueAnimator
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.repository.glasses.listener.R

class AlarmDisplayAdapter : RecyclerView.Adapter<AlarmDisplayAdapter.AlarmViewHolder>(), SelectableAdapter {

    companion object {
        private fun Int.dpToPx(): Int =
            (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()

        private fun Float.dpToPx(): Float =
            this * Resources.getSystem().displayMetrics.density
    }

    private val items = mutableListOf<AlarmDisplayItem>()
    override var selectedPosition: Int = -1
        private set
    var focused: Boolean = false
        private set

    override val adapterItemCount: Int get() = items.size

    class AlarmViewHolder(
        val container: LinearLayout,
        val iconView: ImageView,
        val timeView: TextView,
        val labelView: TextView,
        val repeatView: TextView,
        val indicatorView: View,
        val indicatorDrawable: GradientDrawable
    ) : RecyclerView.ViewHolder(container) {
        var borderAnimator: ValueAnimator? = null
        var currentDrawable: GradientDrawable? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlarmViewHolder {
        // Leading alarm icon ~16dp, tinted by on/off.
        val iconView = ImageView(parent.context).apply {
            setImageResource(R.drawable.ic_alarm)
            setBackgroundColor(Lum.VOID)
        }

        // Time: primary body size, on?BRIGHT:DIM.
        val timeView = TextView(parent.context).apply {
            typeface = Typeface.DEFAULT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Lum.BRIGHT)
            setBackgroundColor(Lum.VOID)
        }

        // Label: DIM, primary body size.
        val labelView = TextView(parent.context).apply {
            typeface = Typeface.DEFAULT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Lum.DIM)
            setBackgroundColor(Lum.VOID)
        }

        // Baseline row: time + label.
        val baselineRow = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setBackgroundColor(Lum.VOID)
            addView(timeView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8.dpToPx()
            })
            addView(labelView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        // Repeat line: GHOST, meta size, uppercase, letterSpacing 0.16em.
        val repeatView = TextView(parent.context).apply {
            typeface = Typeface.DEFAULT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 6f)
            setTextColor(Lum.GHOST)
            letterSpacing = 0.16f
            setBackgroundColor(Lum.VOID)
        }

        val middleColumn = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Lum.VOID)
            addView(baselineRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(repeatView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        // Trailing 11dp on/off indicator (ring vs filled dot with inset look).
        val indicatorDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
        }
        val indicatorView = View(parent.context).apply {
            background = indicatorDrawable
        }

        val container = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 6.dpToPx()
            }
            setPadding(6.dpToPx(), 4.dpToPx(), 6.dpToPx(), 4.dpToPx())
            setBackgroundColor(Lum.VOID)
            addView(iconView, LinearLayout.LayoutParams(
                16.dpToPx(),
                16.dpToPx()
            ).apply {
                marginEnd = 10.dpToPx()
            })
            addView(middleColumn, LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = 10.dpToPx()
            })
            addView(indicatorView, LinearLayout.LayoutParams(
                11.dpToPx(),
                11.dpToPx()
            ))
        }

        return AlarmViewHolder(
            container, iconView, timeView, labelView, repeatView, indicatorView, indicatorDrawable
        )
    }

    override fun onBindViewHolder(holder: AlarmViewHolder, position: Int) {
        val item = items[position]
        val on = item.enabled

        holder.iconView.setColorFilter(if (on) Lum.GLOW else Lum.GHOST)

        holder.timeView.text = String.format("%02d:%02d", item.hour, item.minute)
        holder.timeView.setTextColor(if (on) Lum.BRIGHT else Lum.DIM)

        holder.labelView.text = item.title.ifEmpty { "Alarm" }

        // No repeat schedule in the data model; hide the line rather than invent one.
        holder.repeatView.visibility = View.GONE

        // On/off indicator: filled GLOW with 2dp black inset ring when ON; hollow GHOST ring when OFF.
        holder.indicatorDrawable.apply {
            if (on) {
                setColor(Lum.GLOW)
                setStroke(2.dpToPx(), Lum.VOID)
            } else {
                setColor(Lum.VOID)
                setStroke(1.dpToPx(), Lum.GHOST)
            }
        }
        holder.indicatorView.invalidate()

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
            cornerRadius = 6f.dpToPx()
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

    fun submitList(newItems: List<AlarmDisplayItem>) {
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

    fun getSelectedItem(): AlarmDisplayItem? {
        return if (selectedPosition in items.indices) items[selectedPosition] else null
    }
}
