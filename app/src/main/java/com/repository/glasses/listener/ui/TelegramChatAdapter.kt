package com.repository.glasses.listener.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.Base64
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class TelegramChatAdapter : RecyclerView.Adapter<TelegramChatAdapter.MsgViewHolder>() {

    companion object {
        private const val TYPE_INCOMING = 0
        private const val TYPE_OUTGOING = 1
        private fun Int.dpToPx(): Int =
            (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()

        private fun Float.dpToPx(): Float =
            this * Resources.getSystem().displayMetrics.density
    }

    private val imageCache = object : LruCache<Int, Bitmap>(20) {
        override fun entryRemoved(evicted: Boolean, key: Int, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && !oldValue.isRecycled) oldValue.recycle()
        }
    }
    private val items = mutableListOf<TelegramMessage>()
    private var chatType: String = "user"  // "user", "group", "channel"
    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        recyclerView = rv
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        super.onDetachedFromRecyclerView(rv)
        recyclerView = null
    }

    class MsgViewHolder(
        val outerLayout: LinearLayout,
        val bubbleLayout: LinearLayout,
        val senderView: TextView,
        val imageView: ImageView,
        val textView: TextView,
        val timeStatusRow: LinearLayout,
        val timeView: TextView,
        val statusView: ImageView
    ) : RecyclerView.ViewHolder(outerLayout)

    override fun getItemViewType(position: Int): Int {
        return if (items[position].isOutgoing) TYPE_OUTGOING else TYPE_INCOMING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MsgViewHolder {
        val isOutgoing = viewType == TYPE_OUTGOING

        val senderView = TextView(parent.context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Lum.DIM)
            maxLines = 1
            visibility = View.GONE
        }

        val imageView = ImageView(parent.context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            maxHeight = 150.dpToPx()
            visibility = View.GONE
        }

        val textView = TextView(parent.context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(if (isOutgoing) Lum.BRIGHT else Lum.MID)
        }

        val timeView = TextView(parent.context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setTextColor(Lum.DIM)
        }

        val statusView = ImageView(parent.context).apply {
            visibility = View.GONE
        }

        val timeStatusRow = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(timeView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(statusView, LinearLayout.LayoutParams(
                10.dpToPx(), 10.dpToPx()
            ).apply {
                marginStart = 3.dpToPx()
            })
        }

        val bubbleBg = GradientDrawable().apply {
            setColor(Lum.TRACE)
            cornerRadius = 12f.dpToPx()
        }

        val bubbleLayout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            background = bubbleBg
            setPadding(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
            addView(senderView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(imageView, LinearLayout.LayoutParams(
                200.dpToPx(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 4.dpToPx()
            })
            addView(textView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(timeStatusRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 2.dpToPx()
            })
        }

        val outerLayout = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isOutgoing) Gravity.END else Gravity.START
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 3.dpToPx()
            }
            setPadding(
                if (isOutgoing) 40.dpToPx() else 4.dpToPx(),
                0,
                if (isOutgoing) 4.dpToPx() else 40.dpToPx(),
                0
            )
            setBackgroundColor(Lum.VOID)
            addView(bubbleLayout, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        return MsgViewHolder(outerLayout, bubbleLayout, senderView, imageView, textView, timeStatusRow, timeView, statusView)
    }

    override fun onBindViewHolder(holder: MsgViewHolder, position: Int) {
        val msg = items[position]

        // Show sender in groups only, for incoming messages
        if (chatType == "group" && !msg.isOutgoing && msg.sender.isNotEmpty()) {
            holder.senderView.text = msg.sender
            holder.senderView.visibility = View.VISIBLE
        } else {
            holder.senderView.visibility = View.GONE
        }

        // Inline image with LruCache
        if (msg.imageBase64 != null) {
            val cached = imageCache.get(msg.id)
            if (cached != null && !cached.isRecycled) {
                holder.imageView.setImageBitmap(cached)
                holder.imageView.visibility = View.VISIBLE
            } else {
                try {
                    val bytes = Base64.decode(msg.imageBase64, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        imageCache.put(msg.id, bitmap)
                        holder.imageView.setImageBitmap(bitmap)
                        holder.imageView.visibility = View.VISIBLE
                    } else {
                        holder.imageView.setImageDrawable(null)
                        holder.imageView.visibility = View.GONE
                    }
                } catch (_: Exception) {
                    holder.imageView.setImageDrawable(null)
                    holder.imageView.visibility = View.GONE
                }
            }
        } else {
            holder.imageView.setImageDrawable(null)
            holder.imageView.visibility = View.GONE
        }

        // Text (caption or media label)
        if (msg.text.isNotEmpty() && msg.text != "[Photo]") {
            holder.textView.text = msg.text
            holder.textView.visibility = View.VISIBLE
        } else if (msg.imageBase64 == null) {
            holder.textView.text = msg.text
            holder.textView.visibility = View.VISIBLE
        } else {
            holder.textView.visibility = View.GONE
        }

        // Extract time from ISO date or show raw
        val time = if (msg.date.length >= 16) msg.date.substring(11, 16) else msg.date
        holder.timeView.text = time

        // Send status indicator for outgoing messages
        if (msg.isOutgoing && msg.sendStatus.isNotEmpty()) {
            holder.statusView.visibility = View.VISIBLE
            when (msg.sendStatus) {
                "sending" -> {
                    holder.statusView.setImageBitmap(getStatusIcon(StatusIcon.SENDING))
                    holder.statusView.setColorFilter(Lum.GHOST)
                }
                "sent" -> {
                    holder.statusView.setImageBitmap(getStatusIcon(StatusIcon.SENT))
                    holder.statusView.setColorFilter(Lum.GHOST)
                }
                "read" -> {
                    holder.statusView.setImageBitmap(getStatusIcon(StatusIcon.READ))
                    holder.statusView.setColorFilter(Lum.DIM)
                }
                "failed" -> {
                    holder.statusView.setImageBitmap(getStatusIcon(StatusIcon.FAILED))
                    holder.statusView.setColorFilter(Lum.GLOW)
                }
                else -> holder.statusView.visibility = View.GONE
            }
        } else {
            holder.statusView.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newMessages: List<TelegramMessage>, type: String = "user") {
        chatType = type
        items.clear()
        items.addAll(newMessages)
        imageCache.evictAll()
        notifyDataSetChanged()
        scrollToBottom()
    }

    fun addMessage(msg: TelegramMessage) {
        items.add(msg)
        notifyItemInserted(items.size - 1)
        scrollToBottom()
    }

    fun confirmPendingSend(realId: Int) {
        val idx = items.indexOfFirst { it.id == -1 && it.isOutgoing }
        if (idx >= 0) {
            items[idx] = items[idx].copy(id = realId, sendStatus = "sent")
            notifyItemChanged(idx)
        }
    }

    fun removePendingSend() {
        val idx = items.indexOfFirst { it.id == -1 && it.isOutgoing }
        if (idx >= 0) {
            items[idx] = items[idx].copy(sendStatus = "failed")
            notifyItemChanged(idx)
        }
    }

    fun getOldestMessageId(): Int = items.firstOrNull()?.id ?: 0

    fun prependMessages(olderMessages: List<TelegramMessage>) {
        if (olderMessages.isEmpty()) return
        val layoutManager = recyclerView?.layoutManager as? LinearLayoutManager ?: return
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val firstView = layoutManager.findViewByPosition(firstVisible)
        val topOffset = firstView?.top ?: 0

        items.addAll(0, olderMessages)
        notifyItemRangeInserted(0, olderMessages.size)

        if (items.size > 1000) {
            items.subList(1000, items.size).clear()
        }

        // Restore scroll position so visible messages don't jump
        layoutManager.scrollToPositionWithOffset(firstVisible + olderMessages.size, topOffset)
    }

    private fun scrollToBottom() {
        if (items.isNotEmpty()) {
            recyclerView?.post {
                recyclerView?.scrollToPosition(items.size - 1)
            }
        }
    }

    // --- Status icon rendering ---

    private enum class StatusIcon { SENDING, SENT, READ, FAILED }

    private val iconCache = mutableMapOf<StatusIcon, Bitmap>()

    private fun getStatusIcon(icon: StatusIcon): Bitmap {
        return iconCache.getOrPut(icon) { renderStatusIcon(icon) }
    }

    private fun renderStatusIcon(icon: StatusIcon): Bitmap {
        val size = 10.dpToPx()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * Resources.getSystem().displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val w = size.toFloat()
        val h = size.toFloat()

        when (icon) {
            StatusIcon.SENDING -> {
                // Three dots
                paint.style = Paint.Style.FILL
                val dotR = w * 0.06f
                canvas.drawCircle(w * 0.25f, h * 0.5f, dotR, paint)
                canvas.drawCircle(w * 0.5f, h * 0.5f, dotR, paint)
                canvas.drawCircle(w * 0.75f, h * 0.5f, dotR, paint)
            }
            StatusIcon.SENT -> {
                // Single checkmark
                val path = Path()
                path.moveTo(w * 0.2f, h * 0.5f)
                path.lineTo(w * 0.4f, h * 0.7f)
                path.lineTo(w * 0.8f, h * 0.3f)
                canvas.drawPath(path, paint)
            }
            StatusIcon.READ -> {
                // Double checkmark
                val path1 = Path()
                path1.moveTo(w * 0.05f, h * 0.5f)
                path1.lineTo(w * 0.25f, h * 0.7f)
                path1.lineTo(w * 0.55f, h * 0.3f)
                canvas.drawPath(path1, paint)
                val path2 = Path()
                path2.moveTo(w * 0.35f, h * 0.5f)
                path2.lineTo(w * 0.55f, h * 0.7f)
                path2.lineTo(w * 0.95f, h * 0.3f)
                canvas.drawPath(path2, paint)
            }
            StatusIcon.FAILED -> {
                // X mark
                canvas.drawLine(w * 0.25f, h * 0.25f, w * 0.75f, h * 0.75f, paint)
                canvas.drawLine(w * 0.75f, h * 0.25f, w * 0.25f, h * 0.75f, paint)
            }
        }
        return bitmap
    }
}
