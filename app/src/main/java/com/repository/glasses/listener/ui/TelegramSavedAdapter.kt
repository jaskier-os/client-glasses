package com.repository.glasses.listener.ui

import android.animation.ValueAnimator
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TelegramSavedAdapter : RecyclerView.Adapter<TelegramSavedAdapter.MessageViewHolder>(), SelectableAdapter {

    companion object {
        private fun Int.dpToPx(): Int =
            (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()

        private fun Float.dpToPx(): Float =
            this * Resources.getSystem().displayMetrics.density

        // Render the date as "13 June 2026 20:30" in the device's local time zone.
        private val DATE_OUT = java.time.format.DateTimeFormatter
            .ofPattern("d MMMM yyyy HH:mm", java.util.Locale.ENGLISH)

        /** Format an incoming ISO-8601 timestamp (e.g. "2026-05-21T13:49:37.000Z"). Falls
         *  back to the raw string if it can't be parsed. */
        private fun formatDate(raw: String): String {
            if (raw.isBlank()) return raw
            return try {
                java.time.Instant.parse(raw)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(DATE_OUT)
            } catch (_: Exception) {
                raw
            }
        }
    }

    private val items = mutableListOf<TelegramMessage>()
    override var selectedPosition: Int = -1
        private set
    var focused: Boolean = false
        private set

    override val adapterItemCount: Int get() = items.size

    class MessageViewHolder(
        val container: LinearLayout,
        val textView: TextView,
        val dateView: TextView
    ) : RecyclerView.ViewHolder(container) {
        var borderAnimator: ValueAnimator? = null
        var currentDrawable: GradientDrawable? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        // Message text: MID, primary body size, multi-line wrapping.
        val textView = TextView(parent.context).apply {
            typeface = Typeface.DEFAULT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setLineSpacing(0f, 1.4f)
            setTextColor(Lum.MID)
            setBackgroundColor(Lum.VOID)
        }

        // Date line: DIM, meta size + 2sp, letterSpacing 0.14em.
        val dateView = TextView(parent.context).apply {
            typeface = Typeface.DEFAULT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
            setTextColor(Lum.DIM)
            letterSpacing = 0.14f
            setBackgroundColor(Lum.VOID)
        }

        val container = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12.dpToPx()
            }
            setPadding(6.dpToPx(), 4.dpToPx(), 6.dpToPx(), 4.dpToPx())
            setBackgroundColor(Lum.VOID)
            addView(textView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(dateView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 2.dpToPx()
            })
        }

        return MessageViewHolder(container, textView, dateView)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val item = items[position]

        holder.textView.text = item.text
        holder.dateView.text = formatDate(item.date)

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

    fun submitList(newMessages: List<TelegramMessage>) {
        items.clear()
        items.addAll(newMessages)
        if (selectedPosition >= items.size) {
            selectedPosition = if (items.isNotEmpty()) 0 else -1
        }
        notifyDataSetChanged()
    }

    /**
     * Append an older page at the bottom of the list (Saved is newest-first, so older
     * messages have smaller ids and belong at the end). De-dupes by id and preserves the
     * current selection so pagination never disturbs the highlighted row. Returns the number
     * of genuinely new rows added (0 means the page was fully redundant / end-of-list).
     */
    fun appendOlder(more: List<TelegramMessage>): Int {
        if (more.isEmpty()) return 0
        val existingIds = items.mapTo(HashSet()) { it.id }
        val fresh = more.filter { it.id !in existingIds }
        if (fresh.isEmpty()) return 0
        val start = items.size
        items.addAll(fresh)
        notifyItemRangeInserted(start, fresh.size)
        return fresh.size
    }

    /** Smallest (oldest) loaded message id, used as the Telegram offsetId for the next page. */
    fun getOldestMessageId(): Int =
        items.minOfOrNull { it.id } ?: 0

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

    fun getSelectedMessage(): TelegramMessage? {
        return if (selectedPosition in items.indices) items[selectedPosition] else null
    }
}
