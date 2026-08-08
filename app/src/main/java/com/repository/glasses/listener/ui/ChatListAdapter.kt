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

        // View tags, so an instrumented test can find these by identity instead of guessing from
        // geometry. These are programmatic views with no ids; a heuristic search would silently
        // start matching the wrong view the first time the layout changed.
        const val TAG_UNREAD_BAR = "rc_unread_bar"
        const val TAG_SPINNER = "rc_spinner"
        const val TAG_RAIL = "rc_rail"

        private fun Int.dpToPx(): Int =
            (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()

        private fun Float.dpToPx(): Float =
            this * Resources.getSystem().displayMetrics.density

        /** @param designSp the size as drawn in the approved sketch, before the wearer's setting. */
        private fun TextView.applyScaledSize(designSp: Float) =
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ChatFontScale.sp(designSp))
    }

    private var conversations: List<ChatSummaryItem> = emptyList()
    private var rcState: RcState = RcState.EMPTY
    private val gate = ListMutationGate()

    /** All caret state and policy. Pure, JVM-tested; the adapter only renders its decisions. */
    private val selection = RowSelection().apply {
        // The two header rows exist before any data arrives, exactly as they did when the count was
        // items.size + HEADER_COUNT.
        onRowsReplaced(ChatRowBuilder.build(RcState.EMPTY, emptyList()))
    }

    /** The rows currently rendered, in order. Read-only; the caller cannot mutate the adapter. */
    val currentRows: List<ChatRow> get() = selection.rows

    /**
     * The phone's orchestrator link as of the last snapshot. Defaults to false so a thread opened
     * before any snapshot refuses the microphone rather than inviting a dictation into a void.
     */
    val rcWsConnected: Boolean get() = rcState.wsConnected

    /**
     * The rendered RC row for [sessionId], or null once the snapshot no longer carries it.
     *
     * Absence IS the removal instruction, so a null here is what tells an open thread to pop back
     * to the list rather than render an orphan.
     */
    fun rcSessionRow(sessionId: String): ChatRow.RcSession? =
        selection.rows.filterIsInstance<ChatRow.RcSession>().firstOrNull { it.id == sessionId }

    private val rows: List<ChatRow> get() = selection.rows

    /** The caret's identity. Everything else about selection is derived from it. */
    val selectedKey: String? get() = selection.key

    var focused: Boolean = false
        private set

    /** Where the caret currently sits, or -1. Derived; never the source of truth. */
    val selectedPosition: Int get() = selection.index

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

    /**
     * Stable across rebuilds because the key is. A 64-bit FNV-1a rather than String.hashCode:
     * hashCode is 32-bit and RecyclerView treats colliding stable ids as the same item, which with
     * a session id and a conversation id in the same list would swap two rows' views.
     */
    override fun getItemId(position: Int): Long {
        var h = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
        for (c in rows[position].key) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return h
    }

    /** Derived from the row TYPE. No positional arithmetic anywhere in this adapter. */
    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is ChatRow.NewChat -> VIEW_TYPE_NEW_CHAT
        is ChatRow.RcGroup -> VIEW_TYPE_RC_GROUP
        is ChatRow.RcSession -> VIEW_TYPE_RC_SESSION
        is ChatRow.Conversation -> VIEW_TYPE_CHAT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_NEW_CHAT -> createHeaderHolder(parent, "+ New chat")
            VIEW_TYPE_RC_GROUP -> createRcGroupHolder(parent)
            VIEW_TYPE_RC_SESSION -> createRcSessionHolder(parent)
            else -> createChatHolder(parent)
        }
    }

    private fun createHeaderHolder(parent: ViewGroup, text: String): NewChatViewHolder {
        val label = TextView(parent.context).apply {
            typeface = Typeface.MONOSPACE
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

        val rail = View(ctx).apply { setBackgroundColor(Lum.SOFT); tag = TAG_RAIL }

        // Wraps rather than truncating. A session name is distinguished by its tail far more often
        // than by its head ("glasses font cap" vs "glasses font wrap"), so an ellipsis costs the
        // wearer the one part of the string that identifies the row, while a second line costs
        // only screen the RC section has to spare. Both this and the workDir line below grow the
        // holder; the rail and the caret border are drawn on containers that are WRAP_CONTENT all
        // the way up, so they grow with it instead of boxing the first line.
        val titleView = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(Lum.BRIGHT)
            setBackgroundColor(Lum.VOID)
        }

        // Solid filled bar. Not a dot and not an outline: on a monochrome waveguide a thin outline
        // at this size reads as noise, and the unread state has to survive a glance.
        val unreadBar = View(ctx).apply { setBackgroundColor(Lum.GLOW); tag = TAG_UNREAD_BAR }

        // The existing spinner component, not a new arc. GLOW because "this session is working" is
        // the most urgent thing a row can say; the component's DIM default would rank it below the
        // row's own title, which inverts the hierarchy.
        val spinner = SpinnerView(ctx, sizeDp = 14, tint = Lum.GLOW).apply {
            setBackgroundColor(Lum.VOID)
            tag = TAG_SPINNER
        }

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
            setTextColor(Lum.DIM)
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

        // Text sizes are applied on every bind, never at holder creation. `notifyDataSetChanged`
        // re-binds without re-creating holders, so this is the only step a live change to the
        // wearer's font setting can reach -- and it has to reach the RC rows and the conversation
        // rows alike, or the pinned RC block ends up at a different scale from the list it sits in.
        when (holder) {
            is NewChatViewHolder -> holder.label.applyScaledSize(13f)
            is RcSessionViewHolder -> {
                holder.titleView.applyScaledSize(13f)
                holder.folderView.applyScaledSize(11f)
            }
            is ChatItemViewHolder -> {
                holder.titleView.applyScaledSize(13f)
                holder.subtitleView.applyScaledSize(11f)
            }
        }

        // Every cast below is a SAFE cast on the ROW, not on the holder. A holder whose type no
        // longer matches its row means a bind raced a list swap; skipping the content update is
        // recoverable, a ClassCastException is not.
        when (holder) {
            is NewChatViewHolder -> if (selection) applyCaret(
                holder.container, isSelected,
                { holder.currentDrawable }, { holder.borderAnimator },
                { d, a -> holder.currentDrawable = d; holder.borderAnimator = a },
            )
            is RcGroupViewHolder -> Unit
            is RcSessionViewHolder -> {
                // The title's colour depends on selection as well as on content, so a
                // selection-only rebind still has to recolour it (sketch: .rc.sel .title = GLOW).
                (row as? ChatRow.RcSession)?.let {
                    if (content) bindRcSession(holder, it)
                    holder.titleView.setTextColor(rcTitleColor(it, isSelected))
                }
                // The TRACE fill is what marks the pinned block as one region; it must survive the
                // caret arriving and leaving, so it is applied on both branches.
                applyCaret(
                    holder.container, isSelected,
                    { holder.currentDrawable }, { holder.borderAnimator },
                    { d, a -> holder.currentDrawable = d; holder.borderAnimator = a },
                    fillColor = Lum.TRACE,
                )
            }
            is ChatItemViewHolder -> {
                if (content) (row as? ChatRow.Conversation)?.let { bindChat(holder, it.summary) }
                if (selection) applyCaret(
                    holder.container, isSelected,
                    { holder.currentDrawable }, { holder.borderAnimator },
                    { d, a -> holder.currentDrawable = d; holder.borderAnimator = a },
                )
            }
        }
    }

    /** GLOW when the caret is on it, DIM when the session is dead or the link is down, else BRIGHT. */
    private fun rcTitleColor(row: ChatRow.RcSession, isSelected: Boolean): Int = when {
        isSelected -> Lum.GLOW
        row.dim -> Lum.DIM
        else -> Lum.BRIGHT
    }

    private fun bindRcSession(holder: RcSessionViewHolder, row: ChatRow.RcSession) {
        holder.titleView.text = row.name
        holder.folderView.text = row.folder

        // Dimming is a colour step down the luminance ladder, never alpha: the waveguide has no
        // real blending, and an alpha'd green just reads as a dirtier green.
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

    /**
     * @param fillColor the row's own background under the caret border. RC rows carry a TRACE fill
     *        that marks the pinned block; every other row is VOID, i.e. see-through.
     */
    private fun applyCaret(
        container: LinearLayout,
        isSelected: Boolean,
        currentDrawable: () -> GradientDrawable?,
        currentAnimator: () -> ValueAnimator?,
        store: (GradientDrawable?, ValueAnimator?) -> Unit,
        fillColor: Int = Lum.VOID,
    ) {
        if (isSelected) {
            // A rebind for an unrelated reason must not replay the 150 ms stroke: the caret would
            // flicker every time the list scrolled or a spinner elsewhere changed.
            val existing = currentDrawable()
            if (existing != null && container.background === existing) {
                existing.setColor(fillColor)
                return
            }
            animateBorder(container, fillColor) { drawable, animator -> store(drawable, animator) }
        } else {
            currentAnimator()?.cancel()
            store(null, null)
            container.setBackgroundColor(fillColor)
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
        fillColor: Int = Lum.VOID,
        onCreated: (GradientDrawable, ValueAnimator) -> Unit
    ) {
        val drawable = GradientDrawable().apply {
            setColor(fillColor)
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

    /**
     * A phone push can land in the middle of a scroll frame -- the touchpad daemon drives
     * `ScrollDrainer`, which calls `scrollBy` once per animation frame, so "RecyclerView is
     * computing a layout or scrolling" is the normal case, not a corner case. Notifying then throws.
     * Re-post to the RecyclerView's own handler and run once it is quiet.
     */
    private fun runWhenIdle(block: () -> Unit): Boolean {
        val rv = attachedRecycler ?: return false
        if (!rv.isComputingLayout && rv.scrollState == RecyclerView.SCROLL_STATE_IDLE) return false
        rv.post { block() }
        return true
    }

    private fun rebuild() {
        if (runWhenIdle { rebuild() }) return
        val next = ChatRowBuilder.build(rcState, conversations)
        when (val decision = gate.submit(rows.toList(), next, focused)) {
            is ListMutationGate.Decision.None -> Unit
            is ListMutationGate.Decision.Apply -> applyRows(decision.rows)
            is ListMutationGate.Decision.ContentOnly -> applyRows(decision.rows)
            is ListMutationGate.Decision.Deferred -> {
                applyRows(decision.rows)
                scheduleHoldExpiry()
            }
        }
        if (!gate.hasPending) cancelHoldExpiry()
    }

    /**
     * The hold is bounded, so it needs something to fire at the end of it: a user who walks away
     * with the list focused must still end up looking at a current list. Scheduled on the attached
     * RecyclerView so it dies with the view rather than outliving the screen.
     */
    private fun scheduleHoldExpiry() {
        val rv = attachedRecycler ?: return
        rv.removeCallbacks(holdExpiryRunnable)
        rv.postDelayed(holdExpiryRunnable, ListMutationGate.HOLD_MS + 50L)
    }

    private fun cancelHoldExpiry() {
        attachedRecycler?.removeCallbacks(holdExpiryRunnable)
    }

    private val holdExpiryRunnable = Runnable {
        flushPendingIfDue(force = false)
        // tick() refuses before the deadline; re-arm so a submit that reset nothing still lands.
        if (gate.hasPending) scheduleHoldExpiry()
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecycler = recyclerView
        if (gate.hasPending) scheduleHoldExpiry()
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        cancelHoldExpiry()
        // Detaching does not recycle the ATTACHED holders, so without this their infinite spinner
        // animators keep ticking and invalidating off-screen for as long as the process lives.
        for (i in 0 until recyclerView.childCount) {
            val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(i))
            if (holder is RcSessionViewHolder) holder.spinner.stop()
            (holder as? ChatItemViewHolder)?.borderAnimator?.cancel()
            (holder as? RcSessionViewHolder)?.borderAnimator?.cancel()
            (holder as? NewChatViewHolder)?.borderAnimator?.cancel()
        }
        attachedRecycler = null
        super.onDetachedFromRecyclerView(recyclerView)
    }

    private var attachedRecycler: RecyclerView? = null

    /**
     * Flush a set change that was held back while the caret was live. Called on focus exit
     * ([force] = true) and from the hold expiry; both are no-ops when nothing is pending.
     */
    fun flushPendingIfDue(force: Boolean) {
        if (runWhenIdle { flushPendingIfDue(force) }) return
        val pending = if (force) gate.release() else gate.tick()
        if (pending != null) {
            applyRows(pending)
            cancelHoldExpiry()
        }
    }

    val hasPendingListChange: Boolean get() = gate.hasPending

    /**
     * @param dropSelection true to end with no caret at all (used by [clear]). The old caret's row
     *        is still explicitly rebound, so its border cannot survive as an orphan.
     */
    private fun applyRows(next: List<ChatRow>, dropSelection: Boolean = false) {
        val previous = rows.toList()
        val previousKey = selectedKey
        val diff = DiffUtil.calculateDiff(RowDiff(previous, next), true)

        val change = selection.onRowsReplaced(next, dropSelection)
        diff.dispatchUpdatesTo(this)

        // A row that neither moved nor changed content is not rebound by the diff, so a caret that
        // moved to a different key -- or was dropped -- would otherwise leave its border painted.
        if (change == RowSelection.Change.REHOMED || change == RowSelection.Change.DROPPED) {
            previousKey?.let { key ->
                val idx = rows.indexOfFirst { it.key == key }
                if (idx >= 0) notifyItemChanged(idx, PAYLOAD_SELECTION)
            }
            val now = selectedPosition
            if (now >= 0) notifyItemChanged(now, PAYLOAD_SELECTION)
        }
    }

    /**
     * Re-bind every row's CONTENT without disturbing its selection state.
     *
     * Used when something outside the row data changes how a row must be drawn -- today that is
     * the wearer's chat font size, which every holder re-applies in [bind]. The rows themselves are
     * unchanged, so there is nothing for the diff to notice; the rebind has to be asked for
     * explicitly.
     *
     * Deliberately NOT `notifyDataSetChanged`. This adapter's contract (see the class header) is
     * that set changes are diffed, because a blanket invalidate cancels the caret's border
     * ValueAnimator and restarts every spinner on screen. Resizing text is a content change like
     * any other and goes through the same [PAYLOAD_CONTENT] path, so the caret keeps its border and
     * the RC spinners keep spinning while the wearer drags the font slider.
     */
    fun rebindAllContent() {
        if (rows.isEmpty()) return
        notifyItemRangeChanged(0, rows.size, PAYLOAD_CONTENT)
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

    /**
     * @return false when the caret did NOT move there -- the position is out of range, or its row
     *         is not selectable. Callers that assume a caret exists afterwards must check this.
     */
    fun selectPosition(pos: Int): Boolean = repaintCaretAround { selection.selectIndex(pos) }

    /** @return false when [key] is absent from the list or its row is not selectable. */
    fun selectKey(key: String?): Boolean = repaintCaretAround { selection.select(key) }

    /** @return false when the caret was already on the last selectable row. */
    fun moveSelectionDown(): Boolean = repaintCaretAround { selection.moveDown() }

    /** @return false when the caret was already on the first selectable row. */
    fun moveSelectionUp(): Boolean = repaintCaretAround { selection.moveUp() }

    /**
     * Runs a caret move and rebinds only the two rows whose border can have changed.
     *
     * @return whatever [move] reported, so a refusal reaches the caller rather than being
     *         swallowed by the repaint bookkeeping.
     */
    private fun repaintCaretAround(move: () -> Boolean): Boolean {
        val old = selectedPosition
        val moved = move()
        // The move itself is pure state; only the repaint has to wait for a quiet RecyclerView.
        if (!runWhenIdle { repaintCaretFrom(old) }) repaintCaretFrom(old)
        return moved
    }

    private fun repaintCaretFrom(old: Int) {
        val now = selectedPosition
        if (old == now) return
        if (old >= 0) notifyItemChanged(old, PAYLOAD_SELECTION)
        if (now >= 0) notifyItemChanged(now, PAYLOAD_SELECTION)
    }

    fun setFocused(isFocused: Boolean) {
        if (focused == isFocused) return
        focused = isFocused
        // Leaving the list is the natural moment to land anything that was held back.
        if (!isFocused) flushPendingIfDue(force = true)
        val pos = selectedPosition
        if (pos < 0) return
        if (runWhenIdle { notifyItemChanged(pos, PAYLOAD_SELECTION) }) return
        notifyItemChanged(pos, PAYLOAD_SELECTION)
    }

    fun selectedRow(): ChatRow? = selection.selectedRow()

    fun isNewChatSelected(): Boolean = selection.isNewChatSelected()


    fun getSelectedItem(): ChatSummaryItem? = selection.selectedConversation()

    /** The selected RC session, or null. Null for an ended session -- it is not enterable. */
    fun getSelectedRcSession(): ChatRow.RcSession? = selection.selectedRcSession()

    /**
     * Drops the conversation rows. The two headers survive -- they are not data and never were --
     * and so does the RC section.
     *
     * RC state is deliberately KEPT: the glasses cannot ask for it back (there is no list-request
     * channel by design; the phone pushes on every RC event and on Bluetooth link-up). Wiping it on
     * an unrelated chat-session reset would blank the pinned block until the next agent event,
     * which could be minutes.
     */
    fun clear() {
        if (runWhenIdle { clear() }) return
        conversations = emptyList()
        gate.release()
        cancelHoldExpiry()
        applyRows(ChatRowBuilder.build(rcState, emptyList()), dropSelection = true)
    }
}
