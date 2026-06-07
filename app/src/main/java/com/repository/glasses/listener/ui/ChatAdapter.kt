package com.repository.glasses.listener.ui

import android.content.res.Resources
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.repository.glasses.listener.R

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.MessageViewHolder>() {

    companion object {
        private const val MAX_MESSAGES = 500
        private const val TYPE_USER = 0
        private const val TYPE_ASSISTANT = 1
        private const val TYPE_TOOL = 2
        private const val TYPE_SYSTEM = 3
        private const val TYPE_IMAGE = 4

        private fun Int.dpToPx(): Int =
            (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()

        private fun Float.dpToPx(): Float =
            this * Resources.getSystem().displayMetrics.density
    }

    private val messages = mutableListOf<ChatMessage>()
    var selectedPosition: Int = -1
        private set

    /** User bubble: trace fill, no border, rounded 12dp */
    private val userBubbleDrawable: GradientDrawable
        get() = GradientDrawable().apply {
            setColor(Lum.TRACE)
            cornerRadius = 12f.dpToPx()
        }

    class MessageViewHolder(
        val textView: TextView,
        val container: ViewGroup,
        val toolIcon: ImageView? = null,
        val innerRow: LinearLayout? = null,
        val imageView: ImageView? = null,
        val col: LinearLayout? = null
    ) : RecyclerView.ViewHolder(container)

    override fun getItemViewType(position: Int): Int {
        val msg = messages[position]
        if (msg.role == ChatMessage.Role.TOOL) return TYPE_TOOL
        if (msg.role == ChatMessage.Role.USER) return TYPE_USER
        if (msg.imageBitmap != null) return TYPE_IMAGE
        return when (msg.role) {
            ChatMessage.Role.ASSISTANT -> TYPE_ASSISTANT
            ChatMessage.Role.SYSTEM -> TYPE_SYSTEM
            else -> TYPE_ASSISTANT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val maxBubbleWidth = (parent.width * 0.85).toInt().coerceAtLeast(200)

        if (viewType == TYPE_IMAGE) {
            val imgView = ImageView(parent.context).apply {
                setBackgroundColor(Lum.VOID)
                scaleType = ImageView.ScaleType.FIT_START
                adjustViewBounds = true
            }

            val caption = TextView(parent.context).apply {
                typeface = Typeface.MONOSPACE
                setBackgroundColor(Lum.VOID)
                setTextColor(Lum.GHOST)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setPadding(0, 2.dpToPx(), 0, 0)
            }

            val col = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Lum.VOID)
                addView(imgView, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    80.dpToPx()
                ))
                addView(caption, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }

            val container = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 4.dpToPx() }
                setBackgroundColor(Lum.VOID)
                addView(col, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ))
            }

            return MessageViewHolder(caption, container, imageView = imgView)
        }

        if (viewType == TYPE_TOOL) {
            // Tool messages: icon + text in a horizontal row, optional thumbnail below
            val icon = ImageView(parent.context).apply {
                val d = ContextCompat.getDrawable(parent.context, R.drawable.ic_tool)
                setImageDrawable(d)
                setColorFilter(Lum.DIM, PorterDuff.Mode.SRC_IN)
                setBackgroundColor(Lum.VOID)
            }

            val textView = TextView(parent.context).apply {
                typeface = Typeface.MONOSPACE
                setBackgroundColor(Lum.VOID)
                maxWidth = maxBubbleWidth
            }

            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Lum.VOID)
                addView(icon, LinearLayout.LayoutParams(10.dpToPx(), 10.dpToPx()).apply {
                    marginEnd = 4.dpToPx()
                })
                addView(textView, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }

            val toolThumb = ImageView(parent.context).apply {
                setBackgroundColor(Lum.VOID)
                scaleType = ImageView.ScaleType.FIT_START
                adjustViewBounds = true
                visibility = View.GONE
            }

            val col = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Lum.VOID)
                setPadding(4.dpToPx(), 2.dpToPx(), 4.dpToPx(), 2.dpToPx())
                addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                addView(toolThumb, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    60.dpToPx()
                ).apply { topMargin = 2.dpToPx() })
            }

            val container = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 4.dpToPx() }
                setBackgroundColor(Lum.VOID)
                addView(col, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ))
            }

            return MessageViewHolder(textView, container, icon, row, toolThumb, col)
        }

        if (viewType == TYPE_USER) {
            val textView = TextView(parent.context).apply {
                typeface = Typeface.MONOSPACE
                setBackgroundColor(Lum.VOID)
                maxWidth = maxBubbleWidth
            }

            val thumb = ImageView(parent.context).apply {
                setBackgroundColor(Lum.VOID)
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
            }

            val col = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                setBackgroundColor(Lum.VOID)
                addView(thumb, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    80.dpToPx()
                ))
                addView(textView, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }

            val container = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 6.dpToPx() }
                setBackgroundColor(Lum.VOID)
                addView(col, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ))
            }

            return MessageViewHolder(textView, container, imageView = thumb, col = col)
        }

        val textView = TextView(parent.context).apply {
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Lum.VOID)
            maxWidth = maxBubbleWidth
        }

        val container = FrameLayout(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 6.dpToPx()
            }
            setBackgroundColor(Lum.VOID)
            addView(textView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        return MessageViewHolder(textView, container)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val msg = messages[position]

        // Reset animation state in case a cancelled animateAdd left alpha/translation stuck
        holder.itemView.alpha = 1f
        holder.itemView.translationY = 0f

        // TYPE_IMAGE messages: standalone image with caption (not user/tool thumbnails)
        if (msg.role != ChatMessage.Role.USER && msg.role != ChatMessage.Role.TOOL
            && msg.imageBitmap != null && holder.imageView != null) {
            holder.imageView.setImageBitmap(msg.imageBitmap)
            holder.textView.text = msg.text
            val lp = (holder.imageView.parent as? LinearLayout)?.layoutParams as? FrameLayout.LayoutParams
            if (lp != null) {
                lp.gravity = Gravity.START
                (holder.imageView.parent as LinearLayout).layoutParams = lp
            }
            return
        }

        when (msg.role) {
            ChatMessage.Role.USER -> {
                holder.textView.text = msg.text
                holder.textView.setTextColor(Lum.BRIGHT)
                holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, com.repository.glasses.listener.config.GlassesConfig.chatFontSize)
                holder.textView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                holder.textView.setBackgroundColor(Lum.VOID)
                holder.textView.setPadding(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
                // Bubble background on col (wraps both thumbnail + text)
                val col = holder.col
                if (col != null) {
                    col.background = userBubbleDrawable
                    col.setPadding(0, 0, 0, 0)
                    val lp = col.layoutParams as FrameLayout.LayoutParams
                    lp.gravity = Gravity.END
                    lp.width = FrameLayout.LayoutParams.WRAP_CONTENT
                    col.layoutParams = lp
                } else {
                    holder.textView.background = userBubbleDrawable
                    val lp = holder.textView.layoutParams as FrameLayout.LayoutParams
                    lp.gravity = Gravity.END
                    lp.width = FrameLayout.LayoutParams.WRAP_CONTENT
                    holder.textView.layoutParams = lp
                }
                // Show photo thumbnail if attached
                holder.imageView?.let { thumbView ->
                    if (msg.imageBitmap != null) {
                        thumbView.setImageBitmap(msg.imageBitmap)
                        thumbView.visibility = View.VISIBLE
                    } else {
                        thumbView.visibility = View.GONE
                    }
                }
            }
            ChatMessage.Role.ASSISTANT -> {
                if (msg.responseTimeMs != null || msg.tokenCount != null) {
                    val builder = SpannableStringBuilder(msg.text)
                    val meta = buildString {
                        append("\n")
                        msg.responseTimeMs?.let { append("%.1fs".format(it / 1000.0)) }
                        msg.tokenCount?.let {
                            if (msg.responseTimeMs != null) append(" | ")
                            append("$it tokens")
                        }
                    }
                    val start = builder.length
                    builder.append(meta)
                    builder.setSpan(ForegroundColorSpan(Lum.GHOST), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    builder.setSpan(AbsoluteSizeSpan(10, true), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    holder.textView.text = builder
                } else {
                    holder.textView.text = msg.text
                }
                holder.textView.setTextColor(Lum.MID)
                holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, com.repository.glasses.listener.config.GlassesConfig.chatFontSize)
                holder.textView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                holder.textView.setBackgroundColor(Lum.VOID)
                holder.textView.setPadding(8.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
                val lp = holder.textView.layoutParams as FrameLayout.LayoutParams
                lp.gravity = Gravity.START
                lp.width = FrameLayout.LayoutParams.WRAP_CONTENT
                holder.textView.layoutParams = lp
            }
            ChatMessage.Role.TOOL -> {
                holder.textView.text = msg.text
                holder.textView.setTextColor(Lum.DIM)
                holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                holder.textView.setTypeface(Typeface.MONOSPACE, Typeface.ITALIC)
                holder.textView.setBackgroundColor(Lum.VOID)
                holder.textView.setPadding(0, 2.dpToPx(), 0, 2.dpToPx())
                val lp = holder.innerRow?.layoutParams as? FrameLayout.LayoutParams
                if (lp != null) {
                    lp.gravity = Gravity.START
                    lp.width = FrameLayout.LayoutParams.WRAP_CONTENT
                    holder.innerRow.layoutParams = lp
                }
                // Show tool thumbnail if present
                holder.imageView?.let { thumbView ->
                    if (msg.imageBitmap != null) {
                        thumbView.setImageBitmap(msg.imageBitmap)
                        thumbView.visibility = View.VISIBLE
                    } else {
                        thumbView.visibility = View.GONE
                    }
                }
                // Animate tool icon while active
                holder.toolIcon?.let { icon ->
                    if (msg.isAnimating) {
                        val rotate = RotateAnimation(0f, 360f,
                            Animation.RELATIVE_TO_SELF, 0.5f,
                            Animation.RELATIVE_TO_SELF, 0.5f).apply {
                            duration = 1200
                            repeatCount = Animation.INFINITE
                            interpolator = LinearInterpolator()
                        }
                        icon.startAnimation(rotate)
                    } else {
                        icon.clearAnimation()
                    }
                }
            }
            ChatMessage.Role.SYSTEM -> {
                holder.textView.text = msg.text
                holder.textView.setTextColor(Lum.GHOST)
                holder.textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                holder.textView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                holder.textView.setBackgroundColor(Lum.VOID)
                holder.textView.setPadding(4.dpToPx(), 2.dpToPx(), 4.dpToPx(), 2.dpToPx())
                val lp = holder.textView.layoutParams as FrameLayout.LayoutParams
                lp.gravity = Gravity.CENTER_HORIZONTAL
                lp.width = FrameLayout.LayoutParams.WRAP_CONTENT
                holder.textView.layoutParams = lp
            }
        }

        // Selection border on the bubble itself (not the full-width container)
        // Center message selection: 0.5dp ghost border per design system
        val selectionTarget = when (msg.role) {
            ChatMessage.Role.USER -> holder.col ?: holder.textView
            ChatMessage.Role.TOOL -> holder.col ?: holder.textView
            else -> holder.textView
        }
        val radius = when (msg.role) {
            ChatMessage.Role.TOOL -> 8f.dpToPx()
            else -> 12f.dpToPx()
        }
        if (position == selectedPosition) {
            val isUser = msg.role == ChatMessage.Role.USER
            selectionTarget.background = GradientDrawable().apply {
                setColor(if (isUser) Lum.TRACE else Lum.VOID)
                cornerRadius = radius
                setStroke(1.dpToPx(), Lum.GHOST)
            }
        } else {
            when (msg.role) {
                ChatMessage.Role.USER -> selectionTarget.background = userBubbleDrawable
                ChatMessage.Role.TOOL -> selectionTarget.background = GradientDrawable().apply {
                    setColor(Lum.VOID)
                    cornerRadius = radius
                    setStroke(1.dpToPx(), Lum.VOID) // invisible, prevents layout shift
                }
                else -> selectionTarget.setBackgroundColor(Lum.VOID)
            }
        }
    }

    override fun getItemCount(): Int = messages.size

    private fun evictIfNeeded() {
        // Evict from front before appending so insert indices stay clean.
        if (messages.size >= MAX_MESSAGES) {
            val removeCount = messages.size - MAX_MESSAGES + 1
            messages.subList(0, removeCount).clear()
            if (selectedPosition >= 0) {
                selectedPosition = if (selectedPosition < removeCount) -1
                    else selectedPosition - removeCount
            }
            notifyItemRangeRemoved(0, removeCount)
        }
    }

    fun addMessage(msg: ChatMessage) {
        evictIfNeeded()
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastMessage(text: String) {
        if (messages.isNotEmpty()) {
            messages.last().text = text
            notifyItemChanged(messages.size - 1)
        }
    }

    fun findMessageIndexByRequestId(requestId: String): Int {
        return messages.indexOfLast { it.requestId == requestId }
    }

    fun findAssistantByRequestId(requestId: String): Int {
        return messages.indexOfLast { it.requestId == requestId && it.role == ChatMessage.Role.ASSISTANT }
    }

    fun findUserByRequestId(requestId: String): Int {
        return messages.indexOfLast { it.requestId == requestId && it.role == ChatMessage.Role.USER }
    }

    fun setMessageMeta(index: Int, responseTimeMs: Long, tokenCount: Int) {
        if (index in messages.indices) {
            messages[index].responseTimeMs = responseTimeMs
            messages[index].tokenCount = tokenCount
            notifyItemChanged(index)
        }
    }

    fun updateMessageAt(index: Int, text: String) {
        if (index in messages.indices) {
            messages[index].text = text
            notifyItemChanged(index)
        }
    }

    fun removeMessagesByRequestId(requestId: String) {
        val iter = messages.listIterator(messages.size)
        while (iter.hasPrevious()) {
            val msg = iter.previous()
            if (msg.requestId == requestId) {
                val idx = iter.nextIndex()
                iter.remove()
                notifyItemRemoved(idx)
                // Keep selectedPosition in sync with shifted indices
                if (selectedPosition > idx) {
                    selectedPosition--
                } else if (selectedPosition == idx) {
                    selectedPosition = -1
                }
            }
        }
    }

    fun upsertToolMessage(upsertId: String, parentRequestId: String, toolName: String): Int {
        val existing = messages.indexOfLast { it.role == ChatMessage.Role.TOOL && it.id == upsertId }
        if (existing >= 0) {
            messages[existing].text = toolName
            notifyItemChanged(existing)
            return existing
        }
        val msg = ChatMessage(
            id = upsertId,
            role = ChatMessage.Role.TOOL,
            text = toolName,
            requestId = parentRequestId,
            isAnimating = true
        )
        evictIfNeeded()
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
        return messages.size - 1
    }

    fun stopToolAnimation(requestId: String) {
        for (i in messages.indices) {
            if (messages[i].role == ChatMessage.Role.TOOL && messages[i].requestId == requestId && messages[i].isAnimating) {
                messages[i].isAnimating = false
                notifyItemChanged(i)
            }
        }
    }

    fun setToolThumbnail(requestId: String, bitmap: android.graphics.Bitmap): Boolean {
        val idx = messages.indexOfLast { it.role == ChatMessage.Role.TOOL && it.requestId == requestId }
        if (idx >= 0) {
            messages[idx].imageBitmap = bitmap
            notifyItemChanged(idx)
            return true
        }
        return false
    }

    fun setThumbnail(requestId: String, bitmap: android.graphics.Bitmap): Boolean {
        val idx = messages.indexOfLast { it.role == ChatMessage.Role.USER && it.requestId == requestId }
        if (idx >= 0) {
            messages[idx].imageBitmap = bitmap
            notifyItemChanged(idx)
            return true
        }
        return false
    }

    fun removeLoadingMessages() {
        removeMessagesByRequestId("loading")
    }

    fun selectPosition(pos: Int) {
        if (pos == selectedPosition) return
        val old = selectedPosition
        selectedPosition = pos
        if (old in messages.indices) notifyItemChanged(old)
        if (pos in messages.indices) notifyItemChanged(pos)
    }

    fun clearSelection() {
        if (selectedPosition < 0) return
        selectedPosition = -1
        // Full rebind to ensure no stale selection borders remain
        notifyDataSetChanged()
    }

    fun nextSelectablePosition(current: Int, direction: Int): Int {
        var pos = current + direction
        while (pos in messages.indices) {
            if (messages[pos].role != ChatMessage.Role.SYSTEM) return pos
            pos += direction
        }
        return current
    }

    fun lastSelectablePosition(): Int {
        return messages.indexOfLast { it.role != ChatMessage.Role.SYSTEM }
    }

    fun getMessages(): List<ChatMessage> = messages.toList()

    fun clear() {
        val size = messages.size
        messages.clear()
        selectedPosition = -1
        notifyItemRangeRemoved(0, size)
    }
}
