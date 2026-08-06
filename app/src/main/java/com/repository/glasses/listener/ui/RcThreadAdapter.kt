package com.repository.glasses.listener.ui

import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Renders an RC thread: the agent's prose, the user's messages, a glyph and a count for each tool
 * run, and a bordered box for a prompt the session is blocked on.
 *
 * A tool run collapses to one line on purpose. The full call belongs on the phone; on a 480x640
 * waveguide it would push the answer -- the only thing the wearer came for -- off the screen.
 */
class RcThreadAdapter : RecyclerView.Adapter<RcThreadAdapter.Holder>() {

    companion object {
        const val TAG_TOOL_COLLAPSE = "rc_tool_collapse"
        const val TAG_PROMPT_BOX = "rc_prompt_box"
        const val TAG_PROMPT_OPTION = "rc_prompt_option"
        const val TAG_EARLIER = "rc_earlier"
        const val TAG_USER_BUBBLE = "rc_user_bubble"

        private const val TYPE_EARLIER = 0
        private const val TYPE_USER = 1
        private const val TYPE_ASSISTANT = 2
        private const val TYPE_TOOLS = 3
        private const val TYPE_PROMPT = 4

        private fun Int.dpToPx(): Int =
            (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()

        private fun Float.dpToPx(): Float =
            this * Resources.getSystem().displayMetrics.density
    }

    class Holder(
        val container: ViewGroup,
        val text: TextView,
        /** Prompt rows only: the vertical option list the DPAD walks. */
        val options: LinearLayout? = null,
    ) : RecyclerView.ViewHolder(container)

    private var items: List<RcThreadItem> = emptyList()

    /**
     * Which option of the blocking prompt the caret sits on. Held here rather than in the model
     * because it is a view concern: the model decides WHETHER a prompt blocks, never where a caret
     * happens to be resting.
     */
    private var promptCursor: Int = 0

    val currentItems: List<RcThreadItem> get() = items

    fun submit(newItems: List<RcThreadItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun setPromptCursor(index: Int) {
        if (index == promptCursor) return
        promptCursor = index
        notifyDataSetChanged()
    }

    fun promptCursor(): Int = promptCursor

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (val item = items[position]) {
        is RcThreadItem.EarlierOnPhone -> TYPE_EARLIER
        is RcThreadItem.Row -> when (item.row.role) {
            RcThreadModel.ROLE_USER -> TYPE_USER
            RcThreadModel.ROLE_TOOLS -> TYPE_TOOLS
            RcThreadModel.ROLE_PROMPT -> TYPE_PROMPT
            else -> TYPE_ASSISTANT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context

        val text = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Lum.VOID)
        }

        val container: ViewGroup
        var options: LinearLayout? = null

        when (viewType) {
            TYPE_USER -> {
                // The wearer's own words, right-aligned in a trace-filled bubble (sketch frame 2).
                text.apply {
                    tag = TAG_USER_BUBBLE
                    setTextColor(Lum.BRIGHT)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(10.dpToPx(), 7.dpToPx(), 10.dpToPx(), 7.dpToPx())
                    background = GradientDrawable().apply {
                        setColor(Lum.TRACE)
                        cornerRadius = 12f.dpToPx()
                    }
                }
                container = FrameLayout(ctx).apply {
                    setBackgroundColor(Lum.VOID)
                    addView(text, FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT
                    ).apply { gravity = Gravity.END })
                }
            }
            TYPE_TOOLS -> {
                text.apply {
                    tag = TAG_TOOL_COLLAPSE
                    setTextColor(Lum.DIM)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(2.dpToPx(), 3.dpToPx(), 2.dpToPx(), 3.dpToPx())
                }
                container = FrameLayout(ctx).apply {
                    setBackgroundColor(Lum.VOID)
                    addView(text)
                }
            }
            TYPE_PROMPT -> {
                text.apply {
                    setTextColor(Lum.GLOW)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                }
                options = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Lum.VOID)
                    visibility = View.GONE
                }
                container = LinearLayout(ctx).apply {
                    tag = TAG_PROMPT_BOX
                    orientation = LinearLayout.VERTICAL
                    setPadding(10.dpToPx(), 8.dpToPx(), 10.dpToPx(), 8.dpToPx())
                    background = GradientDrawable().apply {
                        setColor(Lum.VOID)
                        cornerRadius = 8f.dpToPx()
                        setStroke(1.dpToPx(), Lum.SOFT)
                    }
                    addView(text, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ))
                    addView(options, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ))
                }
            }
            TYPE_EARLIER -> {
                text.apply {
                    tag = TAG_EARLIER
                    setText("... earlier messages on phone")
                    setTextColor(Lum.GHOST)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setPadding(2.dpToPx(), 0, 2.dpToPx(), 0)
                }
                container = FrameLayout(ctx).apply {
                    setBackgroundColor(Lum.VOID)
                    addView(text)
                }
            }
            else -> {
                text.apply {
                    setTextColor(Lum.MID)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(2.dpToPx(), 0, 2.dpToPx(), 0)
                }
                container = FrameLayout(ctx).apply {
                    setBackgroundColor(Lum.VOID)
                    addView(text)
                }
            }
        }

        container.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 11.dpToPx() }
        return Holder(container, text, options)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        if (item is RcThreadItem.EarlierOnPhone) return
        val row = (item as RcThreadItem.Row).row

        when (row.role) {
            RcThreadModel.ROLE_TOOLS -> {
                // Glyph + count, exactly as the sketch: the names are on the phone.
                holder.text.text = ">> ${row.toolCount}"
            }
            RcThreadModel.ROLE_PROMPT -> {
                holder.text.text = row.text
                bindOptions(holder, row, isLast = position == items.lastIndex)
            }
            else -> holder.text.text = row.text
        }
    }

    /**
     * Renders the options of a prompt, but ONLY while it is the last row. A resolved prompt keeps
     * its text as history and drops its options: offering a choice that can no longer be sent
     * would be a button that does nothing.
     */
    private fun bindOptions(holder: Holder, row: RcThreadRow, isLast: Boolean) {
        val list = holder.options ?: return
        list.removeAllViews()
        if (!isLast || row.options.isEmpty()) {
            list.visibility = View.GONE
            return
        }
        list.visibility = View.VISIBLE
        val cursor = promptCursor.coerceIn(0, row.options.lastIndex)
        row.options.forEachIndexed { i, option ->
            val selected = i == cursor
            list.addView(TextView(list.context).apply {
                tag = TAG_PROMPT_OPTION
                typeface = Typeface.MONOSPACE
                // The caret is a brightness step, not a colour: the waveguide is monochrome.
                text = if (selected) "> $option" else "  $option"
                setTextColor(if (selected) Lum.GLOW else Lum.DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setBackgroundColor(if (selected) Lum.TRACE else Lum.VOID)
                setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4.dpToPx() })
        }
    }
}
