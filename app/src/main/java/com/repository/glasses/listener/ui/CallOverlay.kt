package com.repository.glasses.listener.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.repository.glasses.listener.R

/**
 * WindowManager-based overlay for incoming calls. Centered rounded modal
 * with a phone glyph + contact name row and number beneath. Keeps
 * FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE so key input continues to
 * flow into MainActivity.
 */
class CallOverlay(private val context: Context) {

    var remoteLog: ((String) -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var isAttached = false

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dp = Resources.getSystem().displayMetrics.density

    private val phoneIcon: ImageView
    private val nameLabel: TextView
    private val numberLabel: TextView
    private val box: LinearLayout

    private val layoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        x = 0
        val screenHeightPx = context.resources.displayMetrics.heightPixels
        y = (60 * context.resources.displayMetrics.density).toInt() + (screenHeightPx * 0.2).toInt()
    }

    private val helperLabel: TextView

    init {
        val boxBg = GradientDrawable().apply {
            setColor(android.graphics.Color.BLACK)
            setStroke((2 * dp).toInt(), Lum.GLOW)
            cornerRadius = 20 * dp
        }

        box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = boxBg
            val pad = (10 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.CENTER_HORIZONTAL
            minimumWidth = (180 * dp).toInt()
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        phoneIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_phone_glow)
        }
        val iconLp = LinearLayout.LayoutParams(
            (18 * dp).toInt(),
            (18 * dp).toInt()
        ).apply {
            rightMargin = (6 * dp).toInt()
            gravity = Gravity.CENTER_VERTICAL
        }

        nameLabel = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Lum.GLOW)
            setBackgroundColor(0x00000000)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        val nameLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_VERTICAL }

        row.addView(phoneIcon, iconLp)
        row.addView(nameLabel, nameLp)

        numberLabel = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setTextColor(Lum.GLOW)
            setBackgroundColor(0x00000000)
            setPadding(0, (4 * dp).toInt(), 0, 0)
            gravity = Gravity.CENTER_HORIZONTAL
            setSingleLine(true)
        }

        helperLabel = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            typeface = Typeface.MONOSPACE
            setTextColor(Lum.GLOW)
            alpha = 0.6f
            setBackgroundColor(0x00000000)
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 2
            text = "One-tap to accept, double-tap to decline"
        }

        val rowLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val numberLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val helperLp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = (6 * dp).toInt()
        }

        box.addView(row, rowLp)
        box.addView(numberLabel, numberLp)
        box.addView(helperLabel, helperLp)
    }

    fun showIncoming(name: String, number: String) {
        handler.post {
            remoteLog?.invoke("CallOverlay: show name='$name' number='$number'")
            if (name.isNotBlank()) {
                nameLabel.text = name
                nameLabel.visibility = View.VISIBLE
            } else {
                nameLabel.text = ""
                nameLabel.visibility = View.GONE
            }
            if (number.isNotBlank()) {
                numberLabel.text = number
                numberLabel.visibility = View.VISIBLE
            } else {
                numberLabel.text = ""
                numberLabel.visibility = View.GONE
            }
            helperLabel.visibility = View.VISIBLE

            try {
                if (isAttached) {
                    wm.updateViewLayout(box, layoutParams)
                } else {
                    wm.addView(box, layoutParams)
                    isAttached = true
                }
                box.visibility = View.VISIBLE
            } catch (e: Exception) {
                remoteLog?.invoke("CallOverlay: addView failed: ${e.message}")
            }
        }
    }

    fun hide() {
        handler.post {
            try {
                if (isAttached) {
                    wm.removeView(box)
                    isAttached = false
                    remoteLog?.invoke("CallOverlay: hidden")
                }
            } catch (e: Exception) {
                remoteLog?.invoke("CallOverlay: removeView failed: ${e.message}")
            }
        }
    }
}
