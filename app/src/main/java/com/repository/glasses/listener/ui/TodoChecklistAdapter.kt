package com.repository.glasses.listener.ui

import android.content.res.Resources
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TodoChecklistAdapter : RecyclerView.Adapter<TodoChecklistAdapter.TodoViewHolder>(), SelectableAdapter {

    companion object {
        // Completion choreography stages (per item, keyed by id):
        //   1. strike in place                          -> STRIKE_HOLD_MS
        //   2. slide to the end (drag-drop style move)  -> MOVE_SETTLE_MS for the move to settle
        //   3. hold struck at the bottom                -> END_HOLD_MS
        //   4. fade the row out                         -> FADE_MS
        //   5. remove (list collapses)
        private const val STRIKE_HOLD_MS = 500L
        private const val MOVE_SETTLE_MS = 320L
        private const val END_HOLD_MS = 3000L
        private const val FADE_MS = 5000L
        // After an item is removed, briefly ignore a lagging backend echo that
        // still contains it, so it doesn't visibly resurrect.
        private const val REMOVED_GUARD_MS = 3000L
        // Partial-rebind payload: update only the row alpha during the fade tick,
        // so the change does NOT trigger a cross-fade item animation or a full rebind.
        private val FADE_PAYLOAD = Any()

        private fun Int.dpToPx(): Int =
            (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()

        private fun Float.dpToPx(): Float =
            this * Resources.getSystem().displayMetrics.density
    }

    private val items = mutableListOf<TodoItem>()
    // ids known to be completed (so a flow is never started twice, and pre-completed
    // items on first load render struck but never animate).
    private val completedIds = mutableSetOf<String>()
    // ids currently running the completion choreography.
    private val activeFlows = mutableSetOf<String>()
    // id -> the list index the item occupied when its flow began. Used to slide it back
    // to its original spot (mirroring the move-to-end) when the user un-marks it.
    private val originalIndex = mutableMapOf<String, Int>()
    // id -> uptime millis at which the fade stage started. Drives a bind-time alpha
    // ramp that survives rebinds/recycling (a view.animate() on the holder gets
    // cancelled by every rebind, which is why the fade was invisible). Absent = no fade.
    private val fadeStartAt = mutableMapOf<String, Long>()
    // ids in the strike/move/hold stages BEFORE the fade has begun -- these can still
    // be un-marked by tapping again (the fade has not started yet).
    private val undoableFlows = mutableSetOf<String>()
    // ids removed in the last REMOVED_GUARD_MS -- dropped from incoming lists.
    private val removedRecently = mutableSetOf<String>()
    // A backend list that arrived mid-animation; applied once all flows drain.
    private var pendingList: List<TodoItem>? = null
    private var seeded = false

    private val handler = Handler(Looper.getMainLooper())
    private var recyclerView: RecyclerView? = null

    override var selectedPosition: Int = -1
        private set
    var focused: Boolean = false
        private set

    /** Fired whenever the selection changes so the host can reposition the overlay cursor. */
    var onSelectionChanged: (() -> Unit)? = null

    override val adapterItemCount: Int get() = items.size

    class TodoViewHolder(
        val container: LinearLayout,
        val dotView: View,
        val dotDrawable: GradientDrawable,
        val textView: TextView
    ) : RecyclerView.ViewHolder(container) {
        var textSizeAnimator: android.animation.ValueAnimator? = null
        // Current text size in sp; -1 = not yet initialized (first bind sets it directly).
        var currentTextSp: Float = -1f
    }

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        recyclerView = rv
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        handler.removeCallbacksAndMessages(null)
        activeFlows.clear()
        recyclerView = null
        // A backend list that arrived mid-flow must not be stranded: with all flows
        // torn down, apply it now so the next attach shows current server data.
        pendingList?.let { stashed ->
            pendingList = null
            applyList(stashed)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TodoViewHolder {
        // Leading 4dp dot (design: selection-colored circle)
        val dotDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Lum.DIM)
        }
        val dotView = View(parent.context).apply {
            background = dotDrawable
        }

        val textView = TextView(parent.context).apply {
            typeface = Typeface.DEFAULT
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setLineSpacing(0f, 1.4f)
            setTextColor(Lum.MID)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setBackgroundColor(Lum.VOID)
        }

        val container = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 2.dpToPx()
            }
            setPadding(6.dpToPx(), 5.dpToPx(), 6.dpToPx(), 5.dpToPx())
            setBackgroundColor(Lum.VOID)
            addView(dotView, LinearLayout.LayoutParams(
                4.dpToPx(),
                4.dpToPx()
            ).apply {
                marginEnd = 9.dpToPx()
            })
            addView(textView, LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ))
        }

        return TodoViewHolder(container, dotView, dotDrawable, textView)
    }

    override fun onBindViewHolder(holder: TodoViewHolder, position: Int, payloads: MutableList<Any>) {
        // Fade tick: update only the alpha, leave everything else untouched. This avoids
        // a full rebind (which would reset text/strike and could fight the cursor).
        if (payloads.contains(FADE_PAYLOAD)) {
            holder.container.alpha = rowAlpha(items[position].id)
            return
        }
        onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: TodoViewHolder, position: Int) {
        val item = items[position]
        val sel = focused && position == selectedPosition

        holder.textView.text = item.text
        // Selected row text is 2sp larger than the base body size, animated smoothly.
        animateTextSize(holder, if (sel) 12f else 10f)

        if (item.completed) {
            // Completed: filled MID dot, strikethrough + DIM text (subtle, no checkbox).
            holder.dotDrawable.setColor(Lum.MID)
            holder.textView.setTextColor(Lum.DIM)
            holder.textView.paintFlags = holder.textView.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            // Static per-row dot is always DIM; selection is shown by the floating
            // overlay cursor (sits on top of the selected row's dot) plus brighter text.
            holder.dotDrawable.setColor(Lum.DIM)
            holder.textView.setTextColor(if (sel) Lum.GLOW else Lum.MID)
            holder.textView.paintFlags = holder.textView.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }

        // No selection border: selected row is plain black, selection conveyed by the
        // overlay cursor and brighter text. The fade stage owns alpha via rowAlpha();
        // any non-fading row is fully opaque.
        holder.container.alpha = rowAlpha(item.id)
        holder.container.setBackgroundColor(Lum.VOID)
    }

    /** Row opacity: 1f unless the item is in its fade stage, then ramps 1f -> 0f over FADE_MS. */
    private fun rowAlpha(id: String): Float {
        val start = fadeStartAt[id] ?: return 1f
        val elapsed = android.os.SystemClock.uptimeMillis() - start
        return when {
            elapsed <= 0L -> 1f
            elapsed >= FADE_MS -> 0f
            else -> 1f - elapsed.toFloat() / FADE_MS
        }
    }

    /** Smoothly grow/shrink a row's text between base (10sp) and selected (12sp). */
    private fun animateTextSize(holder: TodoViewHolder, targetSp: Float) {
        val from = holder.currentTextSp
        if (from < 0f) {
            // First bind for this holder: set directly, no animation.
            holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, targetSp)
            holder.currentTextSp = targetSp
            return
        }
        if (from == targetSp) return
        holder.textSizeAnimator?.cancel()
        holder.textSizeAnimator = android.animation.ValueAnimator.ofFloat(from, targetSp).apply {
            duration = 150L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                val sp = it.animatedValue as Float
                holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                holder.currentTextSp = sp
            }
            start()
        }
    }

    override fun onViewRecycled(holder: TodoViewHolder) {
        holder.container.alpha = 1f
        holder.textSizeAnimator?.cancel()
        holder.textSizeAnimator = null
        // Force the next bind to set size directly (no animation from a stale value).
        holder.currentTextSp = -1f
    }

    override fun getItemCount(): Int = items.size

    // ---- Completion choreography ------------------------------------------

    /**
     * Optimistically mark an item done the instant the user taps and run the full
     * strike -> move-to-end -> hold -> fade -> remove choreography, without waiting
     * for the backend to echo the new state back. No-op if already completing/done.
     */
    fun markCompletedLocally(id: String) {
        beginCompletionFlow(id)
    }

    private fun beginCompletionFlow(id: String) {
        if (id in activeFlows) return
        val pos = items.indexOfFirst { it.id == id }
        if (pos < 0) return
        if (!items[pos].completed) items[pos] = items[pos].copy(completed = true)
        completedIds.add(id)
        activeFlows.add(id)
        // Tappable-to-undo for the whole flow (until actual removal).
        undoableFlows.add(id)
        // Remember where it started so undo can slide it back to the same spot.
        originalIndex[id] = pos

        // Stage 1: strike in place (change animations are disabled on the recycler,
        // so this rebind shows the strike without a cross-fade flicker).
        notifyItemChanged(pos)

        // Stage 2: after a brief beat, slide the item to the end (animated move).
        handler.postDelayed({
            if (id !in activeFlows) return@postDelayed
            moveToEnd(id)
            // Stage 3 + 4: let the move settle, hold struck at the bottom, then fade.
            handler.postDelayed({
                if (id !in activeFlows) return@postDelayed
                fadeAndRemove(id)
            }, MOVE_SETTLE_MS + END_HOLD_MS)
        }, STRIKE_HOLD_MS)
    }

    /**
     * Tap again on a completing item to un-mark it: cancel the choreography (strike,
     * move, hold OR fade), clear the completed flag, restore full opacity, and leave it
     * in place. Valid for the WHOLE flow right up until the row is actually removed --
     * including mid-fade. No-op if the id isn't an active flow.
     */
    fun toggleUndoIfPending(id: String): Boolean {
        if (id !in undoableFlows) return false
        // Cancel everything scheduled for this flow (incl. any pending fade tick).
        activeFlows.remove(id)
        undoableFlows.remove(id)
        completedIds.remove(id)
        fadeStartAt.remove(id)  // stops the fade ramp; rowAlpha() returns to 1f
        val origin = originalIndex.remove(id)
        val pos = items.indexOfFirst { it.id == id }
        if (pos >= 0) {
            if (items[pos].completed) items[pos] = items[pos].copy(completed = false)
            // Full rebind restores strike-off, color, AND opacity (rowAlpha -> 1f).
            notifyItemChanged(pos)
            // Slide it back to where it started (mirrors the move-to-end animation),
            // so undo visually reverses the reorder. Clamp into current bounds since
            // the list size may have shifted while the flow ran.
            val target = (origin ?: pos).coerceIn(0, items.lastIndex)
            if (target != pos) {
                val item = items.removeAt(pos)
                items.add(target, item)
                notifyItemMoved(pos, target)
                // Track the cursor across the move so the highlight stays on the
                // user's selected row.
                when (selectedPosition) {
                    pos -> selectedPosition = target
                    in (target until pos) -> selectedPosition += 1   // moved up past these
                    in ((pos + 1)..target) -> selectedPosition -= 1  // moved down past these
                }
            }
        }
        onSelectionChanged?.invoke()
        // If no flows remain, flush any list the backend pushed mid-animation.
        if (activeFlows.isEmpty()) {
            pendingList?.let { stashed -> pendingList = null; applyList(stashed) }
        }
        return true
    }

    /** True while [id] is mid-completion-flow (strike/move/hold/fade) and can be un-marked. */
    fun isUndoable(id: String): Boolean = id in undoableFlows

    /** Slide the completed item to the end; surrounding items shift to react (drag-drop feel). */
    private fun moveToEnd(id: String) {
        val from = items.indexOfFirst { it.id == id }
        if (from < 0) return
        val to = items.lastIndex
        if (from == to) return
        val item = items.removeAt(from)
        items.add(item)
        notifyItemMoved(from, to)
        // Selection bookkeeping: removing at `from` shifts indices in (from, to] down by 1.
        when {
            selectedPosition == from -> {
                // The next item slid up into the cursor's slot; keep the index, refresh
                // the new occupant's highlight (the moved item at the end is completed,
                // so it carries no selection highlight).
                notifyItemChanged(from)
                onSelectionChanged?.invoke()
            }
            selectedPosition in (from + 1)..to -> {
                selectedPosition -= 1
                onSelectionChanged?.invoke()
            }
        }
    }

    /**
     * Fade the row out over FADE_MS, then remove it (list collapses). Driven by a
     * bind-time alpha ramp (rowAlpha) ticked via partial-rebind payloads, NOT a
     * view.animate() on the holder -- the latter gets cancelled by every rebind, which
     * is why the fade was invisible. This survives rebinds, recycling and scrolling.
     */
    private fun fadeAndRemove(id: String) {
        if (id !in activeFlows) return
        // Stays undoable through the whole fade -- a tap before the row is actually
        // removed cancels it and restores full opacity.
        fadeStartAt[id] = android.os.SystemClock.uptimeMillis()
        tickFade(id)
    }

    private fun tickFade(id: String) {
        val start = fadeStartAt[id]
        if (id !in activeFlows || start == null) return
        val pos = items.indexOfFirst { it.id == id }
        if (pos < 0) { removeItem(id); finishFlow(id); return }
        // Repaint just this row's alpha.
        notifyItemChanged(pos, FADE_PAYLOAD)
        if (android.os.SystemClock.uptimeMillis() - start >= FADE_MS) {
            fadeStartAt.remove(id)
            removeItem(id)
            finishFlow(id)
            return
        }
        // ~30fps repaint cadence: smooth over 5s, cheap on this device.
        handler.postDelayed({ tickFade(id) }, 33L)
    }

    private fun removeItem(id: String) {
        val p = items.indexOfFirst { it.id == id }
        if (p >= 0) {
            items.removeAt(p)
            notifyItemRemoved(p)
            if (selectedPosition >= items.size) {
                selectedPosition = if (items.isNotEmpty()) items.size - 1 else -1
            }
            onSelectionChanged?.invoke()
        }
        removedRecently.add(id)
        handler.postDelayed({ removedRecently.remove(id) }, REMOVED_GUARD_MS)
    }

    private fun finishFlow(id: String) {
        activeFlows.remove(id)
        undoableFlows.remove(id)
        fadeStartAt.remove(id)
        originalIndex.remove(id)
        if (activeFlows.isEmpty()) {
            pendingList?.let { stashed ->
                pendingList = null
                applyList(stashed)
            }
        }
    }

    // ---- Data load ---------------------------------------------------------

    fun submitList(newItems: List<TodoItem>) {
        // `initial` only affects whether pre-completed rows animate; it must NEVER gate
        // whether a list renders. The very first load renders, and so does every load
        // after it.
        val initial = !seeded
        seeded = true
        // Don't disturb in-flight move/fade animations: stash and apply when they drain.
        // Guard is strictly scoped to "an animation is actually running" -- never to the
        // first load, never to a list as a whole. With no active flow the list applies
        // immediately, so a normal server push always renders.
        if (activeFlows.isNotEmpty()) {
            pendingList = newItems
            return
        }
        // Any previously stashed list is superseded by this fresher one.
        pendingList = null
        applyList(newItems, initial = initial)
    }

    private fun applyList(list: List<TodoItem>, initial: Boolean = false) {
        // Drop ids we just removed (lagging backend echo) so they don't resurrect.
        // This only ever subtracts the handful of ids inside the short REMOVED_GUARD_MS
        // window -- it can never blank a whole fresh list.
        val filtered = list.filter { it.id !in removedRecently }
        // Backend-driven completions (e.g. a task completed by voice) animate too.
        val newlyCompleted = if (initial) emptyList()
            else filtered.filter { it.completed && it.id !in completedIds && it.id !in activeFlows }

        // If the visible list already matches the incoming one (same ids, order, text and
        // completed flags), this is a redundant reconciliation -- skip notifyDataSetChanged
        // entirely so the layout does NOT snap/jump (e.g. right after a move-to-end the
        // backend echoes a list we already reflect). Only kick off any new completion
        // flows the echo revealed. Compared field-for-field (text included) so a real
        // server edit is never mistaken for a no-op. Never short-circuits a genuine change
        // from a non-empty incoming list, so a real server list always renders.
        val sameAsShown = filtered.size == items.size &&
            filtered.indices.all {
                filtered[it].id == items[it].id &&
                    filtered[it].completed == items[it].completed &&
                    filtered[it].text == items[it].text
            }
        if (sameAsShown) {
            for (it in newlyCompleted) beginCompletionFlow(it.id)
            return
        }

        items.clear()
        items.addAll(filtered)
        for (it in filtered) if (it.completed) completedIds.add(it.id)
        if (selectedPosition >= items.size) {
            selectedPosition = if (items.isNotEmpty()) 0 else -1
        }
        notifyDataSetChanged()
        onSelectionChanged?.invoke()

        for (it in newlyCompleted) beginCompletionFlow(it.id)
    }

    override fun selectPosition(pos: Int) {
        if (pos < 0 || pos >= items.size) return
        val old = selectedPosition
        selectedPosition = pos
        if (old in items.indices) notifyItemChanged(old)
        notifyItemChanged(pos)
        onSelectionChanged?.invoke()
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
        onSelectionChanged?.invoke()
    }

    fun getSelectedItem(): TodoItem? {
        return if (selectedPosition in items.indices) items[selectedPosition] else null
    }
}
