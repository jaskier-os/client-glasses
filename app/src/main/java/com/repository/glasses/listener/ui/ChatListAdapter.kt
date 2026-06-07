package com.repository.glasses.listener.ui

import android.animation.ValueAnimator
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.repository.glasses.listener.R

class ChatListAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_NEW_CHAT = 0
        private const val VIEW_TYPE_ASSISTANT = 2
        private const val VIEW_TYPE_CHAT = 1
        // Header rows preceding the chat items: position 0 = "New chat",
        // position 1 = "Assistant". Chats start at HEADER_COUNT.
        private const val HEADER_COUNT = 2

        private fun Int.dpToPx(): Int =
            (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()

        private fun Float.dpToPx(): Float =
            this * Resources.getSystem().displayMetrics.density
    }

    private val items = mutableListOf<ChatSummaryItem>()
    var selectedPosition: Int = -1
        private set
    var focused: Boolean = false
        private set

    private val totalCount get() = items.size + HEADER_COUNT

    // -- View Holders --

    class NewChatViewHolder(
        val container: LinearLayout,
        val label: TextView
    ) : RecyclerView.ViewHolder(container) {
        var borderAnimator: ValueAnimator? = null
        var currentDrawable: GradientDrawable? = null
    }

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

    // -- Adapter overrides --

    override fun getItemViewType(position: Int): Int = when (position) {
        0 -> VIEW_TYPE_NEW_CHAT
        1 -> VIEW_TYPE_ASSISTANT
        else -> VIEW_TYPE_CHAT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_NEW_CHAT -> createHeaderHolder(parent, "+ New chat")
            VIEW_TYPE_ASSISTANT -> createHeaderHolder(parent, "* Assistant")
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
        if (holder is NewChatViewHolder) {
            bindNewChat(holder, position)
        } else if (holder is ChatItemViewHolder) {
            bindChat(holder, position)
        }
    }

    private fun bindNewChat(holder: NewChatViewHolder, position: Int) {
        holder.borderAnimator?.cancel()

        if (focused && position == selectedPosition) {
            animateBorder(holder.container) { drawable, animator ->
                holder.currentDrawable = drawable
                holder.borderAnimator = animator
            }
        } else {
            holder.currentDrawable = null
            holder.container.setBackgroundColor(Lum.VOID)
        }
    }

    private fun bindChat(holder: ChatItemViewHolder, position: Int) {
        val item = items[position - HEADER_COUNT] // offset for header rows
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

        holder.borderAnimator?.cancel()

        if (focused && position == selectedPosition) {
            animateBorder(holder.container) { drawable, animator ->
                holder.currentDrawable = drawable
                holder.borderAnimator = animator
            }
        } else {
            holder.currentDrawable = null
            holder.container.setBackgroundColor(Lum.VOID)
        }
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

    override fun getItemCount(): Int = totalCount

    // -- Public API --

    fun submitList(newItems: List<ChatSummaryItem>) {
        items.clear()
        items.addAll(newItems)
        // Keep selection in bounds; default to 0 ("New chat")
        if (selectedPosition >= totalCount || selectedPosition < 0) {
            selectedPosition = 0
        }
        notifyDataSetChanged()
    }

    fun selectPosition(pos: Int) {
        if (pos < 0 || pos >= totalCount) return
        val old = selectedPosition
        selectedPosition = pos
        if (old in 0 until totalCount) notifyItemChanged(old)
        notifyItemChanged(pos)
    }

    fun moveSelectionDown() {
        if (selectedPosition < totalCount - 1) selectPosition(selectedPosition + 1)
    }

    fun moveSelectionUp() {
        if (selectedPosition > 0) selectPosition(selectedPosition - 1)
    }

    fun setFocused(isFocused: Boolean) {
        if (focused == isFocused) return
        focused = isFocused
        if (selectedPosition in 0 until totalCount) notifyItemChanged(selectedPosition)
    }

    fun isNewChatSelected(): Boolean = selectedPosition == 0

    fun isAssistantSelected(): Boolean = selectedPosition == 1

    fun getSelectedItem(): ChatSummaryItem? {
        val dataIndex = selectedPosition - HEADER_COUNT
        return if (dataIndex in items.indices) items[dataIndex] else null
    }

    fun clear() {
        val size = totalCount
        items.clear()
        selectedPosition = -1
        notifyItemRangeRemoved(0, size)
    }
}
