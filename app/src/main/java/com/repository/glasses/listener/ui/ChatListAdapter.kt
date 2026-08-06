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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.repository.glasses.listener.R

/**
 * The chat list: two fixed header rows, an optional pinned remote-control section, then the
 * conversations.
 *
 * Two rules hold this together and neither is optional:
 *
 * 1. **Selection is a KEY, never an index.** RC rows arrive asynchronously and land ABOVE the
 *    conversations, so any index-based caret silently re-aims onto a different row when a session
 *    appears -- and one of the rows it can land on starts the microphone. The index is recomputed
 *    from the key after every submit.
 * 2. **Set changes are diffed, not `notifyDataSetChanged`.** A blanket invalidate cancels the
 *    caret's border ValueAnimator and restarts every spinner on screen, once per RC event.
 */
class ChatListAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_NEW_CHAT = 0
        private const val VIEW_TYPE_CHAT = 1
        private const val VIEW_TYPE_ASSISTANT = 2
        private const val VIEW_TYPE_RC_GROUP = 3
        private const val VIEW_TYPE_RC_SESSION = 4

        /** Rebind only the caret border. */
        const val PAYLOAD_SELECTION = "sel"

        /** Rebind only the row's own content (title, status indicator, dimming). */
        const val PAYLOAD_CONTENT = "content"

        /** Solid unread bar. The spec floor is 18dp tall; it is a bar, not a dot, not an outline. */
        private const val UNREAD_BAR_H_DP = 18
        private const val UNREAD_BAR_W_DP = 4

        /** The RC left rail. Spec floor is 3dp so it survives the waveguide's low effective DPI. */
        private const val RC_RAIL_W_DP = 3

        private fun Int.dpToPx(): Int =
            (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()

        private fun Float.dpToPx(): Float =
            this * Resources.getSystem().displayMetrics.density
    }

    private var conversations: List<ChatSummaryItem> = emptyList()
    private var rcState: RcState = RcState.EMPTY
    private val gate = ListMutationGate()

    // The two header rows exist before any data arrives, exactly as they did when the count was
    // items.size + HEADER_COUNT.
    private val rows = ChatRowBuilder.build(RcState.EMPTY, emptyList()).toMutableList()

    /** The caret's identity. Everything else about selection is derived from it. */
    var selectedKey: String? = null
        private set

    var focused: Boolean = false
        private set

    /** Where the caret currently sits, or -1. Derived; never the source of truth. */
    val selectedPosition: Int
        get() = selectedKey?.let { key -> rows.indexOfFirst { it.key == key } } ?: -1

    init {
        setHasStableIds(true)
    }

    // -- View Holders --

    class NewChatViewHolder(
        val container: LinearLayout,
        val label: TextView
    ) : RecyclerView.ViewHolder(container) {
        var borderAnimator: ValueAnimator? = null
        var currentDrawable: GradientDrawable? = null
    }

    class RcGroupViewHolder(val container: LinearLayout) : RecyclerView.ViewHolder(container)

    class ChatItemViewHolder(
        val container: LinearLayout,
        val titleView: TextView,
        val subtitleView: TextView,
        val deviceIcon: ImageView,
        val clockIcon: ImageView
    ) : RecyclerView.ViewHolder(container) {
        var borderAnimator: ValueAnimator? = null
        var currentDrawable: GradientDrawable? = null
    }

    class RcSessionViewHolder(
        val container: LinearLayout,
        val rail: View,
        val content: LinearLayout,
        val titleView: TextView,
        val folderView: TextView,
        val desktopIcon: ImageView,
        val unreadBar: View,
        val spinner: SpinnerView
    ) : RecyclerView.ViewHolder(container) {
        var borderAnimator: ValueAnimator? = null
        var currentDrawable: GradientDrawable? = null
    }

    // -- Adapter overrides --

    override fun getItemCount(): Int = rows.size

    /** Stable across rebuilds because the key is; this is what lets DiffUtil animate correctly. */
    override fun getItemId(position: Int): Long = rows[position].key.hashCode().toLong()

    /** Derived from the row TYPE. No positional arithmetic anywhere in this adapter. */
    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is ChatRow.NewChat -> VIEW_TYPE_NEW_CHAT
        is ChatRow.Assistant -> VIEW_TYPE_ASSISTANT
        is ChatRow.RcGroup -> VIEW_TYPE_RC_GROUP
        is ChatRow.RcSession -> VIEW_TYPE_RC_SESSION
        is ChatRow.Conversation -> VIEW_TYPE_CHAT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_NEW_CHAT -> createHeaderHolder(parent, "+ New chat")
            VIEW_TYPE_ASSISTANT -> createHeaderHolder(parent, "* Assistant")
            VIEW_TYPE_RC_GROUP -> createRcGroupHolder(parent)
            VIEW_TYPE_RC_SESSION -> createRcSessionHolder(parent)
            else -> createChatHolder(parent)
        }
    }

    private fun createHeaderHolder(parent: ViewGroup, text: String): NewChatViewHolder {
        val label = TextView(parent.context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Lum.DIM)
            this.text = text
            setBackgroundColor(Lum.VOID)
        }

        val container = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 4.dpToPx()
            }
            setPadding(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
            setBackgroundColor(Lum.VOID)
            addView(label, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        return NewChatViewHolder(container, label)
    }

    /**
     * The RC section marker: a desktop glyph on its own line. No text label and no separator rule,
     * per the approved sketch -- the glyph plus the rows' rails carry the grouping.
     */
    private fun createRcGroupHolder(parent: ViewGroup): RcGroupViewHolder {
        val glyph = ImageView(parent.context).apply {
            setBackgroundColor(Lum.VOID)
            setImageDrawable(ContextCompat.getDrawable(parent.context, R.drawable.ic_device_desktop))
            setColorFilter(Lum.SOFT)
        }
        val container = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(8.dpToPx(), 9.dpToPx(), 8.dpToPx(), 5.dpToPx())
            setBackgroundColor(Lum.VOID)
            addView(glyph, LinearLayout.LayoutParams(12.dpToPx(), 12.dpToPx()))
        }
        return RcGroupViewHolder(container)
    }

    private fun createRcSessionHolder(parent: ViewGroup): RcSessionViewHolder {
        val ctx = parent.context

        val rail = View(ctx).apply { setBackgroundColor(Lum.SOFT) }

        val titleView = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Lum.BRIGHT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setBackgroundColor(Lum.VOID)
        }

        // Solid filled bar. Not a dot and not an outline: on a monochrome waveguide a thin outline
        // at this size reads as noise, and the unread state has to survive a glance.
        val unreadBar = View(ctx).apply { setBackgroundColor(Lum.GLOW) }

        // The existing spinner component, not a new arc.
        val spinner = SpinnerView(ctx, sizeDp = 14).apply { setBackgroundColor(Lum.VOID) }

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Lum.VOID)
            addView(titleView, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            addView(unreadBar, LinearLayout.LayoutParams(
                UNREAD_BAR_W_DP.dpToPx(), UNREAD_BAR_H_DP.dpToPx()
            ).apply { marginStart = 6.dpToPx() })
            addView(spinner, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 6.dpToPx() })
        }

        val desktopIcon = ImageView(ctx).apply {
            setBackgroundColor(Lum.VOID)
            setImageDrawable(ContextCompat.getDrawable(ctx, R.drawable.ic_device_desktop))
        }

        val folderView = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Lum.DIM)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            setBackgroundColor(Lum.VOID)
        }

        val subRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Lum.VOID)
            addView(desktopIcon, LinearLayout.LayoutParams(12.dpToPx(), 12.dpToPx()))
            addView(folderView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 4.dpToPx() })
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Lum.VOID)
            addView(titleRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(subRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 2.dpToPx() })
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4.dpToPx() }
            setPadding(0, 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
            setBackgroundColor(Lum.VOID)
            addView(rail, LinearLayout.LayoutParams(
                RC_RAIL_W_DP.dpToPx(), LinearLayout.LayoutParams.MATCH_PARENT
            ))
            addView(content, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8.dpToPx() })
        }

        return RcSessionViewHolder(
            container, rail, content, titleView, folderView, desktopIcon, unreadBar, spinner
        )
    }

    private fun createChatHolder(parent: ViewGroup): ChatItemViewHolder {
        val titleView = TextView(parent.context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Lum.MID)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setBackgroundColor(Lum.VOID)
        }

        val deviceIcon = ImageView(parent.context).apply {
            setBackgroundColor(Lum.VOID)
        }

        val clockIcon = ImageView(parent.context).apply {
            setBackgroundColor(Lum.VOID)
            setImageDrawable(ContextCompat.getDrawable(parent.context, R.drawable.ic_clock))
        }

        val subtitleView = TextView(parent.context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Lum.DIM)
            maxLines = 1
            setBackgroundColor(Lum.VOID)
        }

        val subtitleRow = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Lum.VOID)

            val iconSize = 12.dpToPx()
            val clockSize = 10.dpToPx()

            addView(deviceIcon, LinearLayout.LayoutParams(iconSize, iconSize))
            addView(clockIcon, LinearLayout.LayoutParams(clockSize, clockSize).apply {
                marginStart = 4.dpToPx()
            })
            addView(subtitleView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 3.dpToPx()
            })
        }

        val container = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 4.dpToPx()
            }
            setPadding(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
            setBackgroundColor(Lum.VOID)
            addView(titleView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(subtitleRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 2.dpToPx() })
        }

        return ChatItemViewHolder(container, titleView, subtitleView, deviceIcon, clockIcon)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        bind(holder, position, content = true, selection = true)
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            bind(holder, position, content = true, selection = true)
            return
        }
        bind(
            holder,
            position,
            content = payloads.contains(PAYLOAD_CONTENT),
            selection = payloads.contains(PAYLOAD_SELECTION),
        )
    }

    private fun bind(
        holder: RecyclerView.ViewHolder,
        position: Int,
        content: Boolean,
        selection: Boolean
    ) {
        val row = rows.getOrNull(position) ?: return
        val isSelected = focused && row.key == selectedKey
        when (holder) {
            is NewChatViewHolder -> if (selection) applyCaret(
                holder.container, isSelected,
                { holder.borderAnimator }, { d, a -> holder.currentDrawable = d; holder.borderAnimator = a },
            )
            is RcGroupViewHolder -> Unit
            is RcSessionViewHolder -> {
                if (content) bindRcSession(holder, row as ChatRow.RcSession)
                if (selection) applyCaret(
                    holder.container, isSelected,
                    { holder.borderAnimator }, { d, a -> holder.currentDrawable = d; holder.borderAnimator = a },
                )
            }
            is ChatItemViewHolder -> {
                if (content) bindChat(holder, (row as ChatRow.Conversation).summary)
                if (selection) applyCaret(
                    holder.container, isSelected,
                    { holder.borderAnimator }, { d, a -> holder.currentDrawable = d; holder.borderAnimator = a },
                )
            }
        }
    }

    private fun bindRcSession(holder: RcSessionViewHolder, row: ChatRow.RcSession) {
        holder.titleView.text = row.name
        holder.folderView.text = row.folder

        // Dimming is a colour step down the luminance ladder, never alpha: the waveguide has no
        // real blending, and an alpha'd green just reads as a dirtier green.
        holder.titleView.setTextColor(if (row.dim) Lum.DIM else Lum.BRIGHT)
        holder.folderView.setTextColor(if (row.dim) Lum.TRACE else Lum.DIM)
        holder.desktopIcon.setColorFilter(if (row.dim) Lum.TRACE else Lum.DIM)
        holder.rail.setBackgroundColor(
            when {
                row.dim -> Lum.GHOST
                row.turning -> Lum.GLOW
                else -> Lum.SOFT
            }
        )

        holder.unreadBar.visibility = if (row.unread) View.VISIBLE else View.GONE
        if (row.turning) {
            holder.spinner.visibility = View.VISIBLE
            holder.spinner.start()
        } else {
            holder.spinner.stop()
            holder.spinner.visibility = View.GONE
        }
    }

    private fun bindChat(holder: ChatItemViewHolder, item: ChatSummaryItem) {
        holder.titleView.text = item.title
        holder.subtitleView.text = item.relativeTime
        holder.titleView.setTextColor(if (item.isActive) Lum.GLOW else Lum.MID)

        val deviceRes = when (item.deviceType.lowercase()) {
            "desktop", "pc" -> R.drawable.ic_device_desktop
            "glasses" -> R.drawable.ic_device_glasses
            else -> R.drawable.ic_device_phone
        }
        holder.deviceIcon.setImageDrawable(
            ContextCompat.getDrawable(holder.container.context, deviceRes)
        )
    }

    private fun applyCaret(
        container: LinearLayout,
        isSelected: Boolean,
        currentAnimator: () -> ValueAnimator?,
        store: (GradientDrawable?, ValueAnimator?) -> Unit
    ) {
        currentAnimator()?.cancel()
        if (isSelected) {
            animateBorder(container) { drawable, animator -> store(drawable, animator) }
        } else {
            store(null, null)
            container.setBackgroundColor(Lum.VOID)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        // A recycled holder keeps running its animations otherwise, which on this device is a
        // measurable idle-CPU cost with several sessions scrolling past.
        when (holder) {
            is NewChatViewHolder -> holder.borderAnimator?.cancel()
            is ChatItemViewHolder -> holder.borderAnimator?.cancel()
            is RcSessionViewHolder -> {
                holder.borderAnimator?.cancel()
                holder.spinner.stop()
            }
        }
        super.onViewRecycled(holder)
    }

    private fun animateBorder(
        container: LinearLayout,
        onCreated: (GradientDrawable, ValueAnimator) -> Unit
    ) {
        val drawable = GradientDrawable().apply {
            setColor(Lum.VOID)
            cornerRadius = 8f.dpToPx()
            setStroke(0, Lum.GLOW)
        }
        container.background = drawable

        val targetWidth = 1.dpToPx()
        val animator = ValueAnimator.ofInt(0, targetWidth).apply {
            duration = 150L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                drawable.setStroke(it.animatedValue as Int, Lum.GLOW)
            }
            start()
        }
        onCreated(drawable, animator)
    }

    // -- Public API --

    /** New conversation list from the phone. Merged with the RC snapshot already held. */
    fun submitList(newItems: List<ChatSummaryItem>) {
        conversations = newItems
        rebuild()
    }

    /**
     * A full authoritative RC snapshot. The sessions it carries are exactly the sessions that
     * exist: absence from it removes a row.
     */
    fun submitRcState(state: RcState) {
        rcState = state
        rebuild()
    }

    private fun rebuild() {
        val next = ChatRowBuilder.build(rcState, conversations)
        when (val decision = gate.submit(rows.toList(), next, focused)) {
            is ListMutationGate.Decision.None -> Unit
            is ListMutationGate.Decision.Apply -> applyRows(decision.rows)
            is ListMutationGate.Decision.ContentOnly -> applyRows(decision.rows)
            is ListMutationGate.Decision.Deferred -> applyRows(decision.rows)
        }
    }

    /**
     * Flush a set change that was held back while the caret was live. Call on focus exit and from
     * the periodic tick; both are no-ops when nothing is pending.
     */
    fun flushPendingIfDue(force: Boolean) {
        val pending = if (force) gate.release() else gate.tick()
        if (pending != null) applyRows(pending)
    }

    val hasPendingListChange: Boolean get() = gate.hasPending

    private fun applyRows(next: List<ChatRow>) {
        val previous = rows.toList()
        val previousIndex = selectedPosition
        val diff = DiffUtil.calculateDiff(RowDiff(previous, next), true)
        rows.clear()
        rows.addAll(next)

        // No caret means no caret. Re-resolving from null would silently plant one on row 0, which
        // is the same class of bug as re-aiming it: a keypress would then open something.
        val previousKey = selectedKey
        val resolvedIndex =
            if (previousKey == null) -1
            else ChatRowBuilder.resolveSelection(rows, previousKey, previousIndex)
        selectedKey = rows.getOrNull(resolvedIndex)?.key

        diff.dispatchUpdatesTo(this)

        // A row that neither moved nor changed content is not rebound by the diff, so a caret that
        // had to fall back to a different key would otherwise leave two borders on screen.
        if (previousKey != selectedKey) {
            previousKey?.let { key ->
                val idx = rows.indexOfFirst { it.key == key }
                if (idx >= 0) notifyItemChanged(idx, PAYLOAD_SELECTION)
            }
            if (resolvedIndex >= 0) notifyItemChanged(resolvedIndex, PAYLOAD_SELECTION)
        }
    }

    private class RowDiff(
        private val old: List<ChatRow>,
        private val new: List<ChatRow>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) = old[oldPos].key == new[newPos].key
        override fun areContentsTheSame(oldPos: Int, newPos: Int) = old[oldPos] == new[newPos]
        override fun getChangePayload(oldPos: Int, newPos: Int): Any = PAYLOAD_CONTENT
    }

    fun selectPosition(pos: Int) {
        val row = rows.getOrNull(pos) ?: return
        if (!row.selectable) return
        selectKey(row.key)
    }

    fun selectKey(key: String?) {
        if (selectedKey == key) return
        val old = selectedPosition
        selectedKey = key
        if (old >= 0) notifyItemChanged(old, PAYLOAD_SELECTION)
        val now = selectedPosition
        if (now >= 0) notifyItemChanged(now, PAYLOAD_SELECTION)
    }

    fun moveSelectionDown() {
        val from = selectedPosition
        var i = from + 1
        while (i <= rows.lastIndex) {
            if (rows[i].selectable) { selectPosition(i); return }
            i++
        }
    }

    fun moveSelectionUp() {
        val from = selectedPosition
        if (from < 0) return
        var i = from - 1
        while (i >= 0) {
            if (rows[i].selectable) { selectPosition(i); return }
            i--
        }
    }

    fun setFocused(isFocused: Boolean) {
        if (focused == isFocused) return
        focused = isFocused
        // Leaving the list is the natural moment to land anything that was held back.
        if (!isFocused) flushPendingIfDue(force = true)
        val pos = selectedPosition
        if (pos >= 0) notifyItemChanged(pos, PAYLOAD_SELECTION)
    }

    fun selectedRow(): ChatRow? = rows.getOrNull(selectedPosition)

    fun isNewChatSelected(): Boolean = selectedRow() is ChatRow.NewChat

    fun isAssistantSelected(): Boolean = selectedRow() is ChatRow.Assistant

    fun getSelectedItem(): ChatSummaryItem? = (selectedRow() as? ChatRow.Conversation)?.summary

    /** The selected RC session, or null. Null for an ended session -- it is not enterable. */
    fun getSelectedRcSession(): ChatRow.RcSession? =
        (selectedRow() as? ChatRow.RcSession)?.takeIf { it.enterable }

    /** Drops all data rows. The two headers survive -- they are not data and never were. */
    fun clear() {
        conversations = emptyList()
        rcState = RcState.EMPTY
        gate.release()
        selectedKey = null
        applyRows(ChatRowBuilder.build(RcState.EMPTY, emptyList()))
        selectedKey = null
    }
}
