package com.repository.glasses.listener.ui

import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.repository.glasses.listener.R

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
        const val TAG_TOOL_ICON = "rc_tool_icon"
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
        /**
         * The size this row is drawn at in the approved sketch. Re-applied through
         * [ChatFontScale] on every bind rather than fixed at creation: a holder outlives a setting
         * change, and `notifyDataSetChanged` re-binds without re-creating holders, so a size set
         * at creation would strand every existing row at the old size.
         */
        val designSp: Float,
        /** Prompt rows only: the vertical option list the DPAD walks. */
        val options: LinearLayout? = null,
        /** Tool rows only: the wrench glyph beside the count. */
        val toolIcon: ImageView? = null,
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
        var toolIcon: ImageView? = null
        // The size each row type is drawn at in the sketch. Recorded, not applied: the actual
        // setTextSize happens at bind so a live setting change reaches existing holders.
        val designSp: Float

        when (viewType) {
            TYPE_USER -> {
                designSp = 14f
                // The wearer's own words, right-aligned in a trace-filled bubble (sketch frame 2).
                text.apply {
                    tag = TAG_USER_BUBBLE
                    setTextColor(Lum.BRIGHT)
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
                designSp = 12f
                text.apply {
                    tag = TAG_TOOL_COLLAPSE
                    setTextColor(Lum.DIM)
                    setPadding(0, 3.dpToPx(), 2.dpToPx(), 3.dpToPx())
                }
                // Icon beside text, the same ImageView + TextView pairing the RC session rows use
                // (ChatListAdapter's desktopIcon + title), so the two surfaces stay consistent.
                toolIcon = ImageView(ctx).apply {
                    tag = TAG_TOOL_ICON
                    setImageResource(R.drawable.ic_tool)
                    setColorFilter(Lum.DIM)
                    setBackgroundColor(Lum.VOID)
                }
                container = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(Lum.VOID)
                    setPadding(2.dpToPx(), 0, 0, 0)
                    // Sized at bind off ChatFontScale, not fixed here: a 10dp icon vanishes beside
                    // 18sp text once the wearer turns the font up.
                    addView(toolIcon, LinearLayout.LayoutParams(0, 0).apply {
                        marginEnd = 5.dpToPx()
                    })
                    addView(text, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ))
                }
            }
            TYPE_PROMPT -> {
                designSp = 13f
                text.apply {
                    setTextColor(Lum.GLOW)
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
                designSp = 11f
                text.apply {
                    tag = TAG_EARLIER
                    setText("... earlier messages on phone")
                    setTextColor(Lum.GHOST)
                    setPadding(2.dpToPx(), 0, 2.dpToPx(), 0)
                }
                container = FrameLayout(ctx).apply {
                    setBackgroundColor(Lum.VOID)
                    addView(text)
                }
            }
            else -> {
                designSp = 14f
                text.apply {
                    setTextColor(Lum.MID)
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
        return Holder(container, text, designSp, options, toolIcon)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        // Sized here, not at creation: this is the only step `notifyDataSetChanged` re-runs, so
        // it is the only place a live font-setting change can reach an existing row.
        holder.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, ChatFontScale.sp(holder.designSp))

        val item = items[position]
        if (item is RcThreadItem.EarlierOnPhone) return
        val row = (item as RcThreadItem.Row).row

        when (row.role) {
            RcThreadModel.ROLE_TOOLS -> {
                // Icon + a sentence, not a bare chevron and a number: the number alone taught
                // nobody what it counted. The names of the tools stay on the phone.
                holder.text.text = RcToolLabel.of(row.toolCount)
                holder.toolIcon?.let { icon ->
                    // Scaled off the same ChatFontScale the text uses, so the glyph tracks the
                    // wearer's font setting instead of shrinking away beside it.
                    val px = (ChatFontScale.sp(holder.designSp) *
                        Resources.getSystem().displayMetrics.density).toInt()
                    icon.layoutParams = icon.layoutParams.apply { width = px; height = px }
                }
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, ChatFontScale.sp(13f))
                setBackgroundColor(if (selected) Lum.TRACE else Lum.VOID)
                setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 4.dpToPx() })
        }
    }
}
