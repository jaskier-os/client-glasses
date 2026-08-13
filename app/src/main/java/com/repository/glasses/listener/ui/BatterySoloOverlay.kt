package com.repository.glasses.listener.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The battery indicator, rendered as an OVERLAY WINDOW for the low-battery solo.
 *
 * ## Why this exists rather than reusing the activity's own indicator
 *
 * The solo blackout works by MainActivity hiding its own content root. Anything inside
 * that activity -- including the tab-bar battery indicator -- is hidden with it, and
 * nothing done to a child View can change that: the cover is a sibling higher in the same
 * hierarchy, and `bringToFront()` only reorders a view among ITS OWN siblings. Attempts to
 * lift the indicator out from under the cover therefore cannot work in principle, which is
 * exactly how [NotificationOverlay] already describes the constraint:
 * "a backend overlay window cannot occlude the activity window".
 *
 * So the same answer the Telegram card already uses applies here. The card is a
 * TYPE_APPLICATION_OVERLAY window owned by the SERVICE, which sits above the activity;
 * the activity blacks itself out underneath. This class is that, for the battery
 * indicator: identical window type and flags, so the two layer consistently and a card
 * arriving during a low-battery solo simply renders alongside it.
 *
 * ## Appearance
 *
 * Deliberately a copy of the tab-bar indicator's geometry (16x10dp body, 2dp terminal nub,
 * 2dp inner margin, 9sp monospace label) so the wearer sees the SAME object they are used
 * to, in the same place, rather than a second battery widget that happens to look similar.
 */
class BatterySoloOverlay(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dp = context.resources.displayMetrics.density

    private var isAttached = false

    var remoteLog: ((String) -> Unit)? = null

    private val fill = View(context).apply { setBackgroundColor(GREEN) }
    private val label = TextView(context).apply {
        setTextColor(GREEN)
        textSize = 9f
        typeface = android.graphics.Typeface.MONOSPACE
        setBackgroundColor(Color.BLACK)
    }

    private val body = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        addView(
            View(context).apply {
                background = androidx.core.content.ContextCompat.getDrawable(
                    context,
                    com.repository.glasses.listener.R.drawable.battery_body_shape,
                )
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            fill,
            FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setMargins(px(2), px(2), px(2), px(2))
            },
        )
    }

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.BLACK)
        setPadding(px(2), px(2), px(2), px(2))
        addView(body, LinearLayout.LayoutParams(px(16), px(10)))
        // Terminal nub, matching the layout's.
        addView(
            View(context).apply { setBackgroundColor(GREEN) },
            LinearLayout.LayoutParams(px(2), px(4)),
        )
        addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = px(3) },
        )
    }

    /**
     * Bottom-right, where the tab-bar indicator sits, so the solo does not appear to MOVE
     * the battery readout -- it is the same thing in the same place, with everything else
     * switched off around it.
     */
    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.END
        x = px(2)
        y = px(6)
    }

    private fun px(v: Int): Int = (v * dp).toInt()

    /** Show (or update) the indicator at [pct]. Idempotent. */
    fun show(pct: Int) {
        handler.post {
            try {
                label.text = "$pct%"
                // Same fill arithmetic as MainActivity.updateBatteryUI: the inner width
                // less the 2dp margin on each side, scaled by the percentage.
                val inner = px(16) - 2 * px(2)
                fill.layoutParams = (fill.layoutParams as FrameLayout.LayoutParams).apply {
                    width = (inner * pct / 100).coerceAtLeast(0)
                }
                fill.requestLayout()
                if (!isAttached) {
                    wm.addView(root, layoutParams)
                    isAttached = true
                    remoteLog?.invoke("BatterySolo: overlay attached pct=$pct")
                }
            } catch (t: Throwable) {
                remoteLog?.invoke("BatterySolo: show failed: ${t.message}")
            }
        }
    }

    /** Remove the overlay. Idempotent -- called from several teardown paths. */
    fun hide() {
        handler.post {
            if (!isAttached) return@post
            try {
                wm.removeView(root)
            } catch (t: Throwable) {
                remoteLog?.invoke("BatterySolo: remove failed: ${t.message}")
            }
            isAttached = false
            remoteLog?.invoke("BatterySolo: overlay removed")
        }
    }

    private companion object {
        const val GREEN = 0xFF00FF00.toInt()
    }
}
