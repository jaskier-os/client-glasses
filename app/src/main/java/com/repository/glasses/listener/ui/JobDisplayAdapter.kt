package com.repository.glasses.listener.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
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

        private const val GLYPH_TICK = 0
        private const val GLYPH_BOLT = 1
        private const val GLYPH_PENDING = 2
    }

    private val items = mutableListOf<JobDisplayItem>()
    override var selectedPosition: Int = -1
        private set
    var focused: Boolean = false
        private set

    override val adapterItemCount: Int get() = items.size

    /**
     * Small vector glyph for the timeline column: a tick (done), a bolt (recurring),
     * or a hollow ring (pending). Drawn directly so we avoid monospace text-glyph hacks.
     */
    class TimelineGlyph(context: Context) : View(context) {
        private val paint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * Resources.getSystem().displayMetrics.density
        }
        var glyph: Int = GLYPH_PENDING
        var glyphColor: Int = Lum.GLOW
        private val path = Path()

        init {
            setBackgroundColor(Lum.VOID)
        }

        override fun onDraw(canvas: Canvas) {
            paint.color = glyphColor
            val w = width.toFloat()
            val h = height.toFloat()
            path.reset()
            when (glyph) {
                GLYPH_TICK -> {
                    paint.style = Paint.Style.STROKE
                    path.moveTo(w * 0.18f, h * 0.55f)
                    path.lineTo(w * 0.42f, h * 0.78f)
                    path.lineTo(w * 0.84f, h * 0.24f)
                    canvas.drawPath(path, paint)
                }
                GLYPH_BOLT -> {
                    paint.style = Paint.Style.FILL
                    path.moveTo(w * 0.58f, h * 0.06f)
                    path.lineTo(w * 0.20f, h * 0.56f)
                    path.lineTo(w * 0.46f, h * 0.56f)
                    path.lineTo(w * 0.40f, h * 0.94f)
                    path.lineTo(w * 0.80f, h * 0.42f)
                    path.lineTo(w * 0.52f, h * 0.42f)
                    path.close()
                    canvas.drawPath(path, paint)
                }
                else -> {
                    paint.style = Paint.Style.STROKE
                    val inset = paint.strokeWidth / 2f + 0.5f
                    canvas.drawCircle(w / 2f, h / 2f, w / 2f - inset, paint)
                }
            }
        }
    }

    class JobViewHolder(
        val container: LinearLayout,
        val glyphView: TimelineGlyph,
        val connector: View,
        val whenView: TextView,
        val whatView: TextView
    ) : RecyclerView.ViewHolder(container) {
        var borderAnimator: ValueAnimator? = null
        var currentDrawable: GradientDrawable? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): JobViewHolder {
        // Timeline column: 18dp wide, centered, paddingTop 4dp.
        val glyphView = TimelineGlyph(parent.context)

        val connector = View(parent.context).apply {
            setBackgroundColor(Lum.TRACE)
        }

        val timelineColumn = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 4.dpToPx(), 0, 0)
            setBackgroundColor(Lum.VOID)
            addView(glyphView, LinearLayout.LayoutParams(
                10.dpToPx(),
                11.dpToPx()
            ))
            // Vertical connector fills remaining height below the glyph.
            addView(connector, LinearLayout.LayoutParams(
                1.dpToPx(),
                0,
                1f
            ).apply {
                topMargin = 2.dpToPx()
            })
        }

        // "when" line: DIM, meta size, uppercase, letterSpacing 0.16em.
        val whenView = TextView(parent.context).apply {
            typeface = Typeface.DEFAULT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 6f)
            setTextColor(Lum.DIM)
            letterSpacing = 0.16f
            setBackgroundColor(Lum.VOID)
        }

        // "what" line: primary body size.
        val whatView = TextView(parent.context).apply {
            typeface = Typeface.DEFAULT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setLineSpacing(0f, 1.4f)
            setTextColor(Lum.MID)
            setBackgroundColor(Lum.VOID)
        }

        val contentColumn = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Lum.VOID)
            addView(whenView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(whatView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        val container = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 4.dpToPx()
            }
            setPadding(4.dpToPx(), 3.dpToPx(), 4.dpToPx(), 3.dpToPx())
            setBackgroundColor(Lum.VOID)
            addView(timelineColumn, LinearLayout.LayoutParams(
                18.dpToPx(),
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                marginEnd = 6.dpToPx()
            })
            addView(contentColumn, LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ))
        }

        return JobViewHolder(container, glyphView, connector, whenView, whatView)
    }

    override fun onBindViewHolder(holder: JobViewHolder, position: Int) {
        val item = items[position]
        val isLast = position == items.size - 1

        // Map status -> design states: done / recurring / pending.
        when (item.status) {
            "completed" -> {
                holder.glyphView.glyph = GLYPH_TICK
                holder.glyphView.glyphColor = Lum.DIM
            }
            "running", "needs_input" -> {
                holder.glyphView.glyph = GLYPH_BOLT
                holder.glyphView.glyphColor = Lum.BRIGHT
            }
            else -> {
                holder.glyphView.glyph = GLYPH_PENDING
                holder.glyphView.glyphColor = Lum.GLOW
            }
        }
        holder.glyphView.invalidate()

        // Connector hidden on the last row.
        holder.connector.visibility = if (isLast) View.INVISIBLE else View.VISIBLE

        // "when" line from scheduled time.
        holder.whenView.text = if (item.scheduledAt > 0) {
            timeFormat.format(Date(item.scheduledAt)).uppercase()
        } else {
            ""
        }

        // "what" line: job name, dimmed when done.
        holder.whatView.text = item.name.ifEmpty { "Job" }
        holder.whatView.setTextColor(if (item.status == "completed") Lum.DIM else Lum.MID)

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
