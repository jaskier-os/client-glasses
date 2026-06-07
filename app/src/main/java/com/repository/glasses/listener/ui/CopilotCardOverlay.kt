package com.repository.glasses.listener.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * WindowManager overlay that draws Copilot helper cards on the HUD.
 *
 * Pixel-matched to the React "RealtimeCopilot / HelperRow" design: no chrome,
 * just rows. Each card is a horizontal row of a 3px leading bar + a text column
 * (a note line with *highlight* spans, plus an uppercase meta line). Cards stack
 * vertically, newest on TOP, max 5.
 *
 * Each card is its own TYPE_APPLICATION_OVERLAY window stacked from the top.
 * Cards are keyed by id so the orchestrator can dismiss a specific one once the
 * wearer uses its information. Monochrome green on pure black per the waveguide
 * rules (black = transparent). FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE keeps key
 * input flowing into MainActivity.
 *
 * Animations step the GREEN channel through Lum levels only (NEVER alpha) so the
 * waveguide stays pure-green-on-black. "Fade"/"brightness ramp" == ramping
 * VOID<->BRIGHT/GLOW via lerpLum. No View.setAlpha, no non-FF ARGB anywhere.
 */
class CopilotCardOverlay(private val context: Context) {

    var remoteLog: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dp = Resources.getSystem().displayMetrics.density

    private class Card(
        val view: LinearLayout,
        val params: WindowManager.LayoutParams,
        // `bar` is the dim full-height track of the TTL progress bar; `barFill`
        // is the bright fill anchored to the bottom whose height drains 100%->0%
        // over the card's TTL.
        val bar: FrameLayout,
        val barFill: View,
        val note: TextView,
        val meta: TextView,
        var heard: String,
        var noteText: String,
        var whyText: String,
        var kind: String,
        val why: TextView,
        val textCol: LinearLayout
    ) {
        var appearAnimator: ValueAnimator? = null
        var yAnimator: ValueAnimator? = null
        var dismissAnimator: ValueAnimator? = null
        // Long-running TTL drain animator that shrinks barFill height to 0.
        var ttlAnimator: ValueAnimator? = null
        var dismissing: Boolean = false
        var pending: Boolean = false
        // The spinner shown while a pending card waits to resolve. Animated via its
        // own ValueAnimator inside SpinnerView; cancelled on every removal path.
        var spinner: SpinnerView? = null
        // 5-minute self-removal backstop (set when the card is shown).
        var ttlRunnable: Runnable? = null

        fun cancelAnimators() {
            appearAnimator?.cancel(); appearAnimator = null
            yAnimator?.cancel(); yAnimator = null
            dismissAnimator?.cancel(); dismissAnimator = null
            ttlAnimator?.cancel(); ttlAnimator = null
            spinner?.stop()
        }
    }

    // id -> attached card. Insertion order is OLDEST..NEWEST; newest renders on top.
    private val cards = LinkedHashMap<String, Card>()

    // Full-width HUD cards. Small text, tight padding; a thin vertical TTL
    // progress bar on the left next to the spinner.
    private val topPadPx = (12 * dp).toInt()
    private val sidePadPx = (8 * dp).toInt()
    private val cardGapPx = (8 * dp).toInt()
    private val barWidthPx = (3 * dp).toInt()
    private val rowGapPx = (6 * dp).toInt()
    private val metaTopMarginPx = (3 * dp).toInt()
    private val whyTopMarginPx = (2 * dp).toInt()

    // Card window width: full screen width (cards span the whole HUD).
    private val cardWidthPx = context.resources.displayMetrics.widthPixels

    private val maxCards = 5
    private val maxAnimatedCards = 3
    // Backstop: each card self-removes after 5 minutes if the phone's dismiss is lost.
    private val cardTtlMs = 5 * 60 * 1000L

    // Light sans for the note (design uses Geist 300). Medium for highlight weight.
    private val sansLight: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val sansMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private val KIND_REPLY = "reply"

    /** Interpolate ONLY the green channel between two Lum colors. No alpha lerp. */
    private fun lerpLum(from: Int, to: Int, t: Float): Int {
        val gf = (from shr 8) and 0xFF
        val gt = (to shr 8) and 0xFF
        val g = (gf + (gt - gf) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (g shl 8)
    }

    /**
     * Build the note Spannable. Mirrors the React Cue component: split on '*',
     * odd indices are highlighted (GLOW + medium weight); the rest take baseColor
     * (BRIGHT normally, DIM when used). Asterisks are stripped from display.
     */
    private fun buildNote(text: String, baseColor: Int, used: Boolean, quoted: Boolean = false): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        if (quoted) {
            val q = "\u201C"
            sb.append(q)
            sb.setSpan(ForegroundColorSpan(baseColor), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val parts = text.split('*')
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty()) continue
            val start = sb.length
            sb.append(part)
            val end = sb.length
            if (i % 2 == 1) {
                sb.setSpan(ForegroundColorSpan(Lum.GLOW), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                sb.setSpan(ForegroundColorSpan(baseColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (quoted) {
            val start = sb.length
            sb.append("\u201D")
            sb.setSpan(ForegroundColorSpan(baseColor), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (used) sb.setSpan(StrikethroughSpan(), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    /** Meta line, used state: "v said it - dismissed" all MID (U+2713, U+00B7). */
    private fun metaUsed(): SpannableStringBuilder {
        val sb = SpannableStringBuilder("\u2713 SAID IT \u00B7 DISMISSED")
        sb.setSpan(ForegroundColorSpan(Lum.MID), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    /**
     * Show (or replace) a card. `kind` is "reply" (a line to SAY -- rendered
     * quoted + brighter) or anything else treated as "note" (info to KNOW --
     * plain text, dimmer). The meta line is always "↑ HEARD \"phrase\"" for both
     * kinds. `heard` is the trigger phrase, `note` the helper text (may contain
     * *highlight* spans), `why` the short rationale shown small/dim beneath.
     */
    fun showCard(id: String, kind: String, heard: String, note: String, why: String = "") {
        handler.post {
            val cardKind = if (kind == KIND_REPLY) KIND_REPLY else "note"
            // Replace an existing card with the same id in place.
            cards.remove(id)?.let { existing ->
                existing.cancelAnimators()
                existing.ttlRunnable?.let { handler.removeCallbacks(it) }; existing.ttlRunnable = null
                try { wm.removeView(existing.view) } catch (_: Exception) {}
            }

            val built = buildCardViews()
            val card = Card(
                built.row, built.params, built.bar, built.barFill, built.note, built.meta,
                heard, note, why, cardKind, built.why, built.textCol
            )

            // Initial content at VOID (ramped up by animateAppear).
            built.note.text = buildNote(note, Lum.VOID, used = false, quoted = cardKind == KIND_REPLY)
            built.meta.text = metaHeardAtLum(heard, Lum.VOID, Lum.VOID, cardKind)
            applyWhy(card, Lum.VOID)

            try {
                wm.addView(built.row, built.params)
                attachCard(id, card)
                relayout()
                animateAppear(card)
                remoteLog?.invoke("CopilotCardOverlay: show id=$id (${cards.size} active)")
            } catch (e: Exception) {
                remoteLog?.invoke("CopilotCardOverlay: addView failed: ${e.message}")
            }
        }
    }

    /** Bundle of the row views shared by showCard and showPending. */
    private class BuiltViews(
        val row: LinearLayout,
        val params: WindowManager.LayoutParams,
        val bar: FrameLayout,
        val barFill: View,
        val note: TextView,
        val meta: TextView,
        val why: TextView,
        val textCol: LinearLayout,
        val spinnerSlot: FrameLayout
    )

    /**
     * Build the row skeleton: leading bar + text column (note / why / meta) +
     * spinner slot on the RIGHT. Everything starts at VOID so it ramps up cleanly.
     */
    private fun buildCardViews(): BuiltViews {
        // Leading bar = vertical TTL progress bar: a dim full-height track with a
        // bright fill anchored to the bottom whose height drains 100%->0% over the
        // card's TTL. barWidthPx wide, stretches to full row height.
        val barFill = View(context).apply {
            setBackgroundColor(Lum.VOID) // ramps to BRIGHT/GLOW in animateAppear
        }
        val bar = FrameLayout(context).apply {
            setBackgroundColor(Lum.TRACE) // dim track background (always visible)
            addView(
                barFill,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.BOTTOM
                )
            )
        }
        // Spinner slot sits just RIGHT of the bar (on the LEFT of the card), so a
        // pending card's spinner is close to the progress bar.
        val spinnerSlot = FrameLayout(context).apply { setBackgroundColor(Lum.VOID) }

        val noteTv = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = sansLight
            setLineSpacing(0f, 1.2f)
            setBackgroundColor(Lum.VOID)
            includeFontPadding = false
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        // The "why" rationale: small + dim, plain text, no quotes, no highlight.
        val whyTv = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
            typeface = sansLight
            setLineSpacing(0f, 1.2f)
            setBackgroundColor(Lum.VOID)
            includeFontPadding = false
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE // shown only when whyText is non-blank
        }
        val metaTv = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
            typeface = sansLight
            letterSpacing = 0.08f
            setBackgroundColor(Lum.VOID)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Lum.VOID)
            // Order: note -> meta (HEARD) -> why. (heard and why swapped per design.)
            addView(
                noteTv,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                metaTv,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = metaTopMarginPx }
            )
            addView(
                whyTv,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = whyTopMarginPx }
            )
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Lum.VOID)
            // [TTL bar][spinner slot][text]. Bar + slot stretch to full row height.
            addView(
                bar,
                LinearLayout.LayoutParams(barWidthPx, LinearLayout.LayoutParams.MATCH_PARENT)
            )
            addView(
                spinnerSlot,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply { marginStart = rowGapPx }
            )
            addView(
                textCol,
                LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { marginStart = rowGapPx }
            )
        }

        val params = WindowManager.LayoutParams(
            cardWidthPx,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0 // recomputed in relayout()
        }

        return BuiltViews(row, params, bar, barFill, noteTv, metaTv, whyTv, textCol, spinnerSlot)
    }

    /**
     * Register a freshly-added card: insert into the map, arm the 5-min TTL
     * backstop, and FIFO-evict beyond the cap. Shared by showCard and showPending.
     */
    private fun attachCard(id: String, card: Card) {
        cards[id] = card
        // 5-minute backstop: self-remove this card if still present (phone
        // dismiss lost, or a pending card that never resolved). Cancelled in
        // dismissCard/cancelPending/resolvePending/hideAll/same-id replace.
        val ttl = Runnable {
            if (cards[id] === card && !card.dismissing) {
                remoteLog?.invoke("CopilotCardOverlay: TTL backstop removing id=$id")
                if (card.pending) cancelPending(id) else dismissCard(id)
            }
        }
        card.ttlRunnable = ttl
        handler.postDelayed(ttl, cardTtlMs)
        startTtlDrain(card, cardTtlMs)
        // Enforce max 5: FIFO-evict oldest (first inserted) beyond the cap.
        while (cards.size > maxCards) {
            val oldestId = cards.keys.first()
            val oldest = cards.remove(oldestId) ?: break
            oldest.cancelAnimators()
            oldest.ttlRunnable?.let { handler.removeCallbacks(it) }; oldest.ttlRunnable = null
            try { wm.removeView(oldest.view) } catch (_: Exception) {}
        }
    }

    /**
     * Drain the vertical TTL progress bar fill from 100% to 0% over [durationMs].
     * The fill is bottom-anchored, so shrinking its height empties the bar from the
     * top down. The dim track (bar background, Lum.TRACE) stays visible underneath.
     * Runs on the UI thread; cancelled via card.cancelAnimators() on every removal.
     */
    private fun startTtlDrain(card: Card, durationMs: Long) {
        card.ttlAnimator?.cancel()
        // Measure the bar's full height; fall back to the row height if not laid out.
        card.view.post {
            val fullH = (if (card.bar.height > 0) card.bar.height else card.view.height)
                .coerceAtLeast(1)
            val anim = ValueAnimator.ofFloat(1f, 0f).apply {
                duration = durationMs
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { va ->
                    val frac = va.animatedValue as Float
                    val lp = card.barFill.layoutParams
                    lp.height = (fullH * frac).toInt().coerceAtLeast(0)
                    card.barFill.layoutParams = lp
                }
            }
            card.ttlAnimator = anim
            anim.start()
        }
    }

    /**
     * Render the why line at the given green level (plain, no quotes, no spans).
     * Blank why => the line is GONE and contributes no height.
     */
    private fun applyWhy(card: Card, lum: Int) {
        if (card.whyText.isBlank()) {
            card.why.visibility = View.GONE
            card.why.text = ""
            return
        }
        card.why.visibility = View.VISIBLE
        val sb = SpannableStringBuilder(card.whyText)
        sb.setSpan(ForegroundColorSpan(lum), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        card.why.text = sb
    }

    /**
     * Meta line: always "↑ HEARD \"phrase\"" -- it quotes the trigger phrase that
     * was HEARD, for BOTH kinds. (The reply-vs-note distinction is carried by the
     * note styling + the why line, not here. "SAY" never belongs on the meta line
     * because it always quotes what was heard, not what to say.)
     */
    private fun metaHeardAtLum(heard: String, midLum: Int, brightLum: Int, kind: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val arrow = "\u2191 HEARD "
        sb.append(arrow)
        sb.setSpan(ForegroundColorSpan(midLum), 0, arrow.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val quoted = "\"" + heard.uppercase() + "\""
        val qs = sb.length
        sb.append(quoted)
        sb.setSpan(ForegroundColorSpan(brightLum), qs, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    /**
     * Entrance "materialize" (float-up): translationY 8px->0 + brightness ramp
     * over 250ms ease-out. Bar VOID->BRIGHT, note base VOID->BRIGHT, meta arrow
     * VOID->MID + quote VOID->BRIGHT. Green-channel only via lerpLum, NO alpha.
     */
    private fun animateAppear(card: Card) {
        card.appearAnimator?.cancel()
        val isReply = card.kind == KIND_REPLY
        // reply = a line to SAY: brighter (bar GLOW, note BRIGHT, quoted).
        // note = info to KNOW: bar BRIGHT, note MID.
        val barTarget = if (isReply) Lum.GLOW else Lum.BRIGHT
        val noteTarget = if (isReply) Lum.BRIGHT else Lum.MID
        val startY = 8 * dp
        card.view.translationY = startY
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250L
            interpolator = DecelerateInterpolator()
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                card.view.translationY = startY * (1f - t)
                card.barFill.setBackgroundColor(lerpLum(Lum.VOID, barTarget, t))
                card.note.text = buildNote(card.noteText, lerpLum(Lum.VOID, noteTarget, t), used = false, quoted = isReply)
                applyWhy(card, lerpLum(Lum.VOID, Lum.DIM, t))
                card.meta.text = metaHeardAtLum(
                    card.heard,
                    lerpLum(Lum.VOID, Lum.MID, t),
                    lerpLum(Lum.VOID, Lum.BRIGHT, t),
                    card.kind
                )
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    card.view.translationY = 0f
                    if (card.appearAnimator === animation) card.appearAnimator = null
                }
            })
        }
        card.appearAnimator = anim
        anim.start()
    }

    /**
     * Dismiss a card (the wearer used its info). Three phases like the design's
     * useCard:
     *   phase 1 (immediate): mark used -> note ramps BRIGHT->DIM + strikethrough,
     *                        bar ramps BRIGHT->GLOW, meta swaps to "v said it - dismissed".
     *   phase 2 (~720ms):    collapse -> animate row height to 0 AND ramp all
     *                        greens down to VOID over ~500ms (brightness fade, no alpha).
     *   phase 3 (~1300ms):   removeView + relayout so rows above glide down.
     */
    fun dismissCard(id: String) {
        handler.post {
            val card = cards[id] ?: return@post
            if (card.dismissing) return@post
            card.dismissing = true
            // Cancel the TTL backstop -- this card is on its way out.
            card.ttlRunnable?.let { handler.removeCallbacks(it) }; card.ttlRunnable = null
            card.ttlAnimator?.cancel(); card.ttlAnimator = null
            // Bar is being dismissed: show it full so the phase animations read cleanly.
            card.barFill.layoutParams = card.barFill.layoutParams.apply { height = FrameLayout.LayoutParams.MATCH_PARENT }
            card.appearAnimator?.cancel(); card.appearAnimator = null
            card.yAnimator?.cancel(); card.yAnimator = null
            card.view.translationY = 0f

            val isReply = card.kind == KIND_REPLY
            val barLit = if (isReply) Lum.GLOW else Lum.BRIGHT
            val noteLit = if (isReply) Lum.BRIGHT else Lum.MID

            // Phase 1: ramp to used state (note->DIM, bar->GLOW).
            val phase1 = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 250L
                interpolator = DecelerateInterpolator()
                addUpdateListener { va ->
                    val t = va.animatedValue as Float
                    card.barFill.setBackgroundColor(lerpLum(barLit, Lum.GLOW, t))
                    card.note.text = buildNote(card.noteText, lerpLum(noteLit, Lum.DIM, t), used = true, quoted = isReply)
                    applyWhy(card, lerpLum(Lum.DIM, Lum.GHOST, t))
                    card.meta.text = metaUsed()
                }
            }
            phase1.start()

            // Phase 2 (~720ms): collapse height to 0 + ramp all greens DIM/GLOW->VOID.
            handler.postDelayed({
                if (!card.dismissing) return@postDelayed
                card.view.measure(
                    View.MeasureSpec.makeMeasureSpec(cardWidthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val startH = card.view.measuredHeight.coerceAtLeast(1)
                val collapse = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 500L
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { va ->
                        val t = va.animatedValue as Float
                        // Brightness fade (green channel), never alpha.
                        card.barFill.setBackgroundColor(lerpLum(Lum.GLOW, Lum.VOID, t))
                        card.note.text = buildNote(card.noteText, lerpLum(Lum.DIM, Lum.VOID, t), used = true, quoted = isReply)
                        applyWhy(card, lerpLum(Lum.GHOST, Lum.VOID, t))
                        card.meta.text = SpannableStringBuilder("\u2713 SAID IT \u00B7 DISMISSED").apply {
                            setSpan(ForegroundColorSpan(lerpLum(Lum.MID, Lum.VOID, t)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        // Collapse window height so survivors begin to take its place.
                        val h = (startH * (1f - t)).toInt().coerceAtLeast(1)
                        if (card.params.height != h) {
                            card.params.height = h
                            try { wm.updateViewLayout(card.view, card.params) } catch (_: Exception) {}
                        }
                    }
                }
                card.dismissAnimator = collapse
                collapse.start()
            }, 720L)

            // Phase 3 (~1300ms): remove + relayout (survivors glide down into place).
            handler.postDelayed({
                // Only unmap the id if it still points at THIS card -- a same-id
                // showCard/showPending during the dismiss may have installed a new
                // card under the same key, which must not be evicted here.
                if (cards[id] === card) cards.remove(id)
                card.cancelAnimators()
                try { wm.removeView(card.view) } catch (_: Exception) {}
                relayout()
                remoteLog?.invoke("CopilotCardOverlay: dismiss id=$id (${cards.size} active)")
            }, 1300L)
        }
    }

    /**
     * Show a PENDING card at the TOP (newest position) with a leading bar and a
     * green rotating-arc spinner on the RIGHT of the row, no body text yet. The
     * card lands in the same `cards` map keyed by `id`, marked pending, counts
     * toward the cap-5/FIFO, gets the float-up entrance, and carries a 5-min TTL
     * backstop so a never-resolved pending card is cleaned up. The spinner's
     * ValueAnimator is cancelled on resolve/cancel/dismiss/evict/hideAll.
     */
    fun showPending(id: String) {
        handler.post {
            // Replace any existing card with this id in place.
            cards.remove(id)?.let { existing ->
                existing.cancelAnimators()
                existing.ttlRunnable?.let { handler.removeCallbacks(it) }; existing.ttlRunnable = null
                try { wm.removeView(existing.view) } catch (_: Exception) {}
            }

            val built = buildCardViews()
            val card = Card(
                built.row, built.params, built.bar, built.barFill, built.note, built.meta,
                "", "", "", "note", built.why, built.textCol
            )
            card.pending = true

            // Empty body while pending (why/meta blank). Bar ramps up via appear.
            built.note.text = ""
            built.note.visibility = View.GONE
            built.why.visibility = View.GONE
            built.meta.text = ""
            built.meta.visibility = View.GONE

            // Spinner on the LEFT, in the slot next to the TTL bar. Green arc, no alpha.
            val spinner = SpinnerView(context, sizeDp = 14)
            card.spinner = spinner
            built.spinnerSlot.addView(
                spinner,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )

            try {
                wm.addView(built.row, built.params)
                attachCard(id, card)
                relayout()
                animateAppear(card)
                spinner.start()
                remoteLog?.invoke("CopilotCardOverlay: pending id=$id (${cards.size} active)")
            } catch (e: Exception) {
                remoteLog?.invoke("CopilotCardOverlay: pending addView failed: ${e.message}")
            }
        }
    }

    /**
     * Morph a pending card IN PLACE into a real card: stop+remove the spinner,
     * reveal the note/why/meta children, re-key the map from pendingId to realId
     * (so dismissCard(realId) later works), update the bar color for the kind, and
     * run the appear brightness ramp on the new content so it fills in smoothly.
     * Falls back to showCard(realId, ...) if the pending card no longer exists.
     */
    fun resolvePending(pendingId: String, realId: String, kind: String, heard: String, note: String, why: String) {
        handler.post {
            val card = cards[pendingId]
            if (card == null || !card.pending) {
                // Pending card was evicted/expired/dismissed -- just show a fresh one.
                showCard(realId, kind, heard, note, why)
                return@post
            }

            // Stop the spinner and remove it from its slot.
            card.spinner?.let { sp ->
                sp.stop()
                try { (sp.parent as? android.view.ViewGroup)?.removeView(sp) } catch (_: Exception) {}
            }
            card.spinner = null
            card.pending = false

            // Re-key the map: drop the pending key, insert under realId at the TOP
            // (it is already the newest; re-inserting keeps it newest).
            cards.remove(pendingId)
            cards[realId] = card

            // Repoint the TTL backstop at the real id so it still self-removes.
            card.ttlRunnable?.let { handler.removeCallbacks(it) }
            val ttl = Runnable {
                if (cards[realId] === card && !card.dismissing) {
                    remoteLog?.invoke("CopilotCardOverlay: TTL backstop removing id=$realId")
                    dismissCard(realId)
                }
            }
            card.ttlRunnable = ttl
            handler.postDelayed(ttl, cardTtlMs)
            // Restart the TTL drain from full now that the card resolved.
            startTtlDrain(card, cardTtlMs)

            // Fill in the real content.
            val cardKind = if (kind == KIND_REPLY) KIND_REPLY else "note"
            card.kind = cardKind
            card.heard = heard
            card.noteText = note
            card.whyText = why

            card.note.visibility = View.VISIBLE
            card.meta.visibility = View.VISIBLE
            card.note.text = buildNote(note, Lum.VOID, used = false, quoted = cardKind == KIND_REPLY)
            card.meta.text = metaHeardAtLum(heard, Lum.VOID, Lum.VOID, cardKind)
            applyWhy(card, Lum.VOID)

            relayout()
            // Brightness ramp on the new content (and bar to the kind's color).
            animateAppear(card)
            remoteLog?.invoke("CopilotCardOverlay: resolve $pendingId -> $realId (${cards.size} active)")
        }
    }

    /**
     * Remove a pending card cleanly via the normal removal path (cancel spinner +
     * animators + TTL, removeView, relayout). Used when the AI returned nothing or
     * the batch failed. No-op if no card with that id exists.
     */
    fun cancelPending(pendingId: String) {
        handler.post {
            val card = cards.remove(pendingId) ?: return@post
            card.dismissing = true
            card.cancelAnimators()
            card.ttlRunnable?.let { handler.removeCallbacks(it) }; card.ttlRunnable = null
            card.spinner?.let { sp ->
                sp.stop()
                try { card.view.removeView(sp) } catch (_: Exception) {}
            }
            card.spinner = null
            try { wm.removeView(card.view) } catch (_: Exception) {}
            relayout()
            remoteLog?.invoke("CopilotCardOverlay: cancelPending id=$pendingId (${cards.size} active)")
        }
    }

    /**
     * Remove all cards (Copilot session stopped). Teardown path -- instant, no fade.
     */
    fun hideAll() {
        handler.post {
            for ((_, card) in cards) {
                card.cancelAnimators()
                card.ttlRunnable?.let { handler.removeCallbacks(it) }; card.ttlRunnable = null
                try { wm.removeView(card.view) } catch (_: Exception) {}
            }
            cards.clear()
            remoteLog?.invoke("CopilotCardOverlay: hideAll")
        }
    }

    /**
     * Re-stack cards vertically from the top, NEWEST on TOP. Each card's measured
     * height determines the next y offset so they never overlap. Visible cards
     * glide to their new y; if more than maxAnimatedCards are visible, snap. Cards
     * that would run off the bottom of the HUD are hidden (off-screen guard).
     */
    private fun relayout() {
        val screenHeightPx = context.resources.displayMetrics.heightPixels
        val offscreenGuardPx = 640

        // Newest first (reverse of insertion order).
        val ordered = cards.values.toList().asReversed()

        // Count visible cards to decide animate-vs-snap.
        var visibleCount = 0
        run {
            var y = topPadPx
            for (card in ordered) {
                card.view.measure(
                    View.MeasureSpec.makeMeasureSpec(cardWidthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val h = card.view.measuredHeight
                if (y + h > screenHeightPx || y > offscreenGuardPx) continue
                visibleCount++
                y += h + cardGapPx
            }
        }
        val animate = visibleCount in 1..maxAnimatedCards

        var y = topPadPx
        for (card in ordered) {
            card.view.measure(
                View.MeasureSpec.makeMeasureSpec(cardWidthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val h = card.view.measuredHeight

            // Off-screen bounds guard: hide rather than render half-off-screen.
            if ((y + h > screenHeightPx || y > offscreenGuardPx) && card.view.visibility != View.GONE) {
                card.yAnimator?.cancel(); card.yAnimator = null
                card.view.visibility = View.GONE
                try { wm.updateViewLayout(card.view, card.params) } catch (_: Exception) {}
                continue
            }
            if (card.view.visibility == View.GONE && (y + h > screenHeightPx || y > offscreenGuardPx)) {
                continue
            }
            if (card.view.visibility != View.VISIBLE) card.view.visibility = View.VISIBLE

            val targetY = y
            card.yAnimator?.cancel(); card.yAnimator = null
            if (!animate || card.params.y == targetY || card.dismissing) {
                card.params.y = targetY
                try { wm.updateViewLayout(card.view, card.params) } catch (_: Exception) {}
            } else {
                val fromY = card.params.y
                val anim = ValueAnimator.ofInt(fromY, targetY).apply {
                    duration = 180L
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { va ->
                        card.params.y = va.animatedValue as Int
                        try { wm.updateViewLayout(card.view, card.params) } catch (_: Exception) {}
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            card.params.y = targetY
                            try { wm.updateViewLayout(card.view, card.params) } catch (_: Exception) {}
                            if (card.yAnimator === animation) card.yAnimator = null
                        }
                    })
                }
                card.yAnimator = anim
                anim.start()
            }
            y += h + cardGapPx
        }
    }
}
