package com.repository.touchpadtest

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSnapHelper
import androidx.recyclerview.widget.RecyclerView

private const val TAG_LIFE = "Touchpad:Life"
private const val TAG_SCROLL = "Touchpad:Scroll"
private const val TAG_KEY = "Touchpad:Key"
private const val TAG_DRAIN = "Touchpad:Drain"
private const val TAG_RENDER = "Touchpad:Render"
private const val TAG_RELEASED = "Touchpad:Released"
private const val TAG_HIGHLIGHT = "Touchpad:Highlight"

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TouchpadTest"
        private const val ITEM_COUNT = 200
        private const val START_INDEX = 100
    }

    // Throttling counters for hot paths.
    private var scrollFrameCounter = 0L
    private var scrollLastLogMs = 0L
    private var keyBurstCounter = 0L
    private var keyBurstLastMs = 0L
    private var drainIterCounter = 0L

    private lateinit var list: RecyclerView
    private lateinit var header: TextView
    private lateinit var adapter: ItemAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private var itemWidthPx: Int = 0
    private var currentIndex = START_INDEX
        set(value) {
            if (field != value) {
                field = value
                adapter.setHighlighted(value)
                updateHeader()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG_LIFE, "onCreate:entry")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG_LIFE, "screen_on_flag=set")

        list = findViewById(R.id.list)
        header = findViewById(R.id.header)

        layoutManager = CenterLinearLayoutManager(this)
        list.layoutManager = layoutManager

        adapter = ItemAdapter(ITEM_COUNT) { pos -> pos == currentIndex }
        list.adapter = adapter

        LinearSnapHelper().attachToRecyclerView(list)

        list.post {
            itemWidthPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 96f, resources.displayMetrics
            ).toInt()
            centerOn(currentIndex, animate = false)
        }

        // Update currentIndex as the list scrolls past items (so the header
        // counter tracks where the list visually is, not just where our key
        // presses commanded it to go).
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val t0 = System.nanoTime()
                val lm = rv.layoutManager as LinearLayoutManager
                val centerX = (rv.width - rv.paddingLeft - rv.paddingRight) / 2
                // Find the item closest to the center of the viewport.
                val first = lm.findFirstVisibleItemPosition()
                val last = lm.findLastVisibleItemPosition()
                var best = currentIndex
                var bestDist = Int.MAX_VALUE
                var scanned = 0
                for (pos in first..last) {
                    val v = lm.findViewByPosition(pos) ?: continue
                    val itemCenter = (v.left + v.right) / 2
                    val d = Math.abs(itemCenter - centerX)
                    if (d < bestDist) { bestDist = d; best = pos }
                    scanned++
                }
                currentIndex = best
                scrollFrameCounter++
                val nowMs = System.currentTimeMillis()
                if (scrollFrameCounter % 30L == 0L || nowMs - scrollLastLogMs >= 500L) {
                    val durUs = (System.nanoTime() - t0) / 1000L
                    Log.d(
                        TAG_SCROLL,
                        "onScrolled frames=$scrollFrameCounter dx=$dx dy=$dy visible=$scanned best=$best durUs=$durUs"
                    )
                    scrollLastLogMs = nowMs
                }
            }
        })

        // Root view receives keys.
        val root = findViewById<FrameLayout>(android.R.id.content).getChildAt(0) as FrameLayout
        root.isFocusable = true
        root.isFocusableInTouchMode = true
        root.requestFocus()

        updateHeader()
        Log.d(TAG_LIFE, "onCreate:exit itemCount=$ITEM_COUNT startIndex=$START_INDEX")
    }

    override fun onResume() {
        Log.d(TAG_LIFE, "onResume:entry")
        super.onResume()
        window.decorView.requestFocus()
        Log.d(TAG_LIFE, "onResume:exit")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val nowMs = System.currentTimeMillis()
        val gapMs = if (keyBurstLastMs == 0L) -1L else nowMs - keyBurstLastMs
        // Detect burst boundary: >150ms of silence marks start of a new burst.
        val isBurstStart = gapMs < 0L || gapMs > 150L
        if (isBurstStart) {
            // Log the last key of the previous burst (if any) then reset.
            if (keyBurstCounter > 0L) {
                Log.d(TAG_KEY, "onKeyDown burst_end total=$keyBurstCounter gapMs=$gapMs")
            }
            keyBurstCounter = 0L
        }
        keyBurstCounter++
        keyBurstLastMs = nowMs
        if (isBurstStart || keyBurstCounter % 10L == 0L) {
            Log.d(TAG_KEY, "onKeyDown keyCode=$keyCode burstIdx=$keyBurstCounter gapMs=$gapMs")
        }
        when (keyCode) {
            KeyEvent.KEYCODE_NUMPAD_0 -> { step(+1); return true }
            KeyEvent.KEYCODE_NUMPAD_1 -> { step(-1); return true }
            KeyEvent.KEYCODE_NUMPAD_2 -> { onReleased(); return true }
            // Swallow the hardware swipe keycodes so the PSoC's pulse-train
            // terminators (LEFT/RIGHT/UP/DOWN) do not double-scroll on top of
            // the daemon's synthetic stream.
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // Pixel scroll buffer. Each keypress deposits pixels here; the drain
    // ticker consumes them over time, guaranteeing every keypress contributes
    // visible scroll (smoothScrollBy cancels prior animations and loses half
    // the presses during rapid bursts).
    private var pendingScrollPx = 0
    private val scrollDrainer = object : Runnable {
        override fun run() {
            if (pendingScrollPx == 0) {
                Log.d(TAG_DRAIN, "run:exit pending=0 iter=$drainIterCounter")
                drainIterCounter = 0L
                return
            }
            val sign = if (pendingScrollPx > 0) 1 else -1
            val mag = Math.abs(pendingScrollPx)
            // Drain rate: ~25% of remaining per frame, min 3 px, max 60 px.
            val this_tick = ((mag * 25) / 100).coerceIn(3, 60).coerceAtMost(mag)
            val dx = sign * this_tick
            list.scrollBy(dx, 0)
            pendingScrollPx -= dx
            drainIterCounter++
            if (drainIterCounter % 30L == 0L) {
                Log.d(TAG_DRAIN, "run:tick iter=$drainIterCounter pending=$pendingScrollPx dx=$dx")
            }
            if (pendingScrollPx != 0) list.postOnAnimation(this)
        }
    }

    private fun step(delta: Int) {
        val w = if (itemWidthPx > 0) itemWidthPx else 96
        pendingScrollPx += delta * w
        list.removeCallbacks(scrollDrainer)
        list.postOnAnimation(scrollDrainer)
        Log.d(TAG_DRAIN, "step delta=$delta pending=$pendingScrollPx itemW=$w")
    }

    private fun onReleased() {
        // Finger lifted. Let the ticker drain any residual pending pixels,
        // then the snap helper will settle on the nearest item. Do NOT zero
        // pendingScrollPx here -- we want the trailing glide to complete.
        Log.d(TAG_RELEASED, "onReleased idx=$currentIndex pending=$pendingScrollPx")
    }

    private fun centerOn(index: Int, animate: Boolean) {
        if (animate) {
            list.smoothScrollToPosition(index)
        } else {
            list.scrollToPosition(index)
            list.post { centerScrollTo(index) }
        }
    }

    /**
     * LinearSnapHelper handles the final snap, but on initial load we want the
     * item centered even before any scroll animation. Nudge the offset manually.
     */
    private fun centerScrollTo(index: Int) {
        val lm = list.layoutManager as LinearLayoutManager
        val itemView = lm.findViewByPosition(index) ?: return
        val listCenter = (list.width - list.paddingLeft - list.paddingRight) / 2
        val itemCenter = (itemView.left + itemView.right) / 2
        list.scrollBy(itemCenter - listCenter, 0)
    }

    private fun updateHeader() {
        header.text = "CURRENT: %03d".format(currentIndex)
    }

    /** Horizontal LayoutManager with symmetric padding so every item can sit in the middle. */
    private class CenterLinearLayoutManager(ctx: android.content.Context) :
        LinearLayoutManager(ctx, HORIZONTAL, false)

    private class ItemAdapter(
        private val count: Int,
        private val isHighlighted: (Int) -> Boolean
    ) : RecyclerView.Adapter<ItemAdapter.VH>() {

        private var highlightedIndex = -1
        private var bindCounter = 0L

        fun setHighlighted(index: Int) {
            val old = highlightedIndex
            highlightedIndex = index
            if (old >= 0) notifyItemChanged(old)
            notifyItemChanged(index)
            Log.d(TAG_HIGHLIGHT, "setHighlighted old=$old new=$index")
        }

        class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, 96f, resources.displayMetrics
                    ).toInt(),
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                gravity = Gravity.CENTER
                setBackgroundColor(Color.BLACK)
                textSize = 28f
            }
            return VH(tv)
        }

        override fun getItemCount(): Int = count

        override fun onBindViewHolder(holder: VH, position: Int) {
            val t0 = System.nanoTime()
            holder.tv.text = "%03d".format(position)
            val hi = isHighlighted(position) || position == highlightedIndex
            holder.tv.setTextColor(if (hi) Color.parseColor("#00FF00") else Color.parseColor("#00AA00"))
            holder.tv.alpha = if (hi) 1.0f else 0.55f
            bindCounter++
            if (bindCounter % 10L == 0L) {
                val durUs = (System.nanoTime() - t0) / 1000L
                Log.d(TAG_RENDER, "onBindViewHolder binds=$bindCounter pos=$position hi=$hi durUs=$durUs")
            }
        }
    }
}
