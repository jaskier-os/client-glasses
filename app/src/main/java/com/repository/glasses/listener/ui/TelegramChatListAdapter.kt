package com.repository.glasses.listener.ui

import android.animation.ValueAnimator
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Base64
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TelegramChatListAdapter : RecyclerView.Adapter<TelegramChatListAdapter.ChatViewHolder>(), SelectableAdapter {

    companion object {
        private fun Int.dpToPx(): Int =
            (this * Resources.getSystem().displayMetrics.density + 0.5f).toInt()

        private fun Float.dpToPx(): Float =
            this * Resources.getSystem().displayMetrics.density

        private fun makeCircularBitmap(src: Bitmap): Bitmap {
            val size = minOf(src.width, src.height)
            val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            paint.shader = shader
            val radius = size / 2f
            canvas.drawCircle(radius, radius, radius, paint)
            return output
        }
    }

    private val avatarCache = LruCache<String, Bitmap>(30)
    private val items = mutableListOf<TelegramChat>()
    override var selectedPosition: Int = -1
        private set
    var focused: Boolean = false
        private set

    override val adapterItemCount: Int get() = items.size

    class ChatViewHolder(
        val container: LinearLayout,
        val avatarContainer: FrameLayout,
        val avatarImage: ImageView,
        val initialView: TextView,
        val onlineDot: View,
        val nameView: TextView,
        val previewView: TextView,
        val badgeView: TextView
    ) : RecyclerView.ViewHolder(container) {
        var borderAnimator: ValueAnimator? = null
        var currentDrawable: GradientDrawable? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        // Avatar image (circular)
        val avatarImage = ImageView(parent.context).apply {
            setBackgroundColor(Lum.VOID)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }

        // Letter initial circle (fallback when no avatar)
        val initialView = TextView(parent.context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Lum.BRIGHT)
            gravity = Gravity.CENTER
            setBackgroundColor(Lum.VOID)
            val bg = GradientDrawable().apply {
                setColor(Lum.GHOST)
                cornerRadius = 12f.dpToPx()
            }
            background = bg
        }

        // Online dot (green circle)
        val onlineDot = View(parent.context).apply {
            val bg = GradientDrawable().apply {
                setColor(Lum.GLOW)
                cornerRadius = 4f.dpToPx()
            }
            background = bg
            visibility = View.GONE
        }

        // Container for avatar + online dot
        val avatarContainer = FrameLayout(parent.context).apply {
            setBackgroundColor(Lum.VOID)
            addView(initialView, FrameLayout.LayoutParams(24.dpToPx(), 24.dpToPx()))
            addView(avatarImage, FrameLayout.LayoutParams(24.dpToPx(), 24.dpToPx()))
            addView(onlineDot, FrameLayout.LayoutParams(8.dpToPx(), 8.dpToPx()).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            })
        }

        // Name + preview column
        val nameView = TextView(parent.context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Lum.BRIGHT)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setBackgroundColor(Lum.VOID)
        }

        val previewView = TextView(parent.context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(Lum.DIM)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setBackgroundColor(Lum.VOID)
        }

        val textColumn = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Lum.VOID)
            addView(nameView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(previewView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        // Unread badge
        val badgeView = TextView(parent.context).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setTextColor(Lum.GLOW)
            gravity = Gravity.CENTER
            setBackgroundColor(Lum.VOID)
            val bg = GradientDrawable().apply {
                setColor(Lum.TRACE)
                cornerRadius = 6f.dpToPx()
            }
            background = bg
            setPadding(4.dpToPx(), 1.dpToPx(), 4.dpToPx(), 1.dpToPx())
        }

        val container = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 3.dpToPx()
            }
            setPadding(6.dpToPx(), 5.dpToPx(), 6.dpToPx(), 5.dpToPx())
            setBackgroundColor(Lum.VOID)

            addView(avatarContainer, LinearLayout.LayoutParams(
                24.dpToPx(), 24.dpToPx()
            ).apply {
                marginEnd = 6.dpToPx()
            })
            addView(textColumn, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ))
            addView(badgeView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 4.dpToPx()
            })
        }

        return ChatViewHolder(container, avatarContainer, avatarImage, initialView, onlineDot, nameView, previewView, badgeView)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val item = items[position]

        // Avatar: show cached/decoded image if available, otherwise letter initial
        val initial = item.title.firstOrNull()?.uppercase() ?: "?"
        if (item.avatar != null) {
            val cached = avatarCache.get(item.chatId)
            if (cached != null && !cached.isRecycled) {
                holder.avatarImage.setImageBitmap(cached)
                holder.avatarImage.visibility = View.VISIBLE
                holder.initialView.visibility = View.GONE
            } else {
                try {
                    val bytes = Base64.decode(item.avatar, Base64.DEFAULT)
                    val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (src != null) {
                        val circular = makeCircularBitmap(src)
                        src.recycle()
                        avatarCache.put(item.chatId, circular)
                        holder.avatarImage.setImageBitmap(circular)
                        holder.avatarImage.visibility = View.VISIBLE
                        holder.initialView.visibility = View.GONE
                    } else {
                        holder.avatarImage.setImageDrawable(null)
                        holder.avatarImage.visibility = View.GONE
                        holder.initialView.visibility = View.VISIBLE
                        holder.initialView.text = initial
                    }
                } catch (_: Exception) {
                    holder.avatarImage.setImageDrawable(null)
                    holder.avatarImage.visibility = View.GONE
                    holder.initialView.visibility = View.VISIBLE
                    holder.initialView.text = initial
                }
            }
        } else {
            holder.avatarImage.setImageDrawable(null)
            holder.avatarImage.visibility = View.GONE
            holder.initialView.visibility = View.VISIBLE
            holder.initialView.text = initial
        }

        // Online dot
        holder.onlineDot.visibility = if (item.isOnline) View.VISIBLE else View.GONE

        holder.nameView.text = item.title
        holder.previewView.text = if (item.lastMessageSender.isNotEmpty() && item.chatType != "user") {
            "${item.lastMessageSender}: ${item.lastMessage}"
        } else {
            item.lastMessage
        }

        if (item.unreadCount > 0) {
            holder.badgeView.text = if (item.unreadCount > 99) "99+" else item.unreadCount.toString()
            holder.badgeView.visibility = View.VISIBLE
        } else {
            holder.badgeView.visibility = View.GONE
        }

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
            setStroke(0, Lum.GHOST)
        }
        container.background = drawable

        val targetWidth = 1.dpToPx()
        val animator = ValueAnimator.ofInt(0, targetWidth).apply {
            duration = 150L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                drawable.setStroke(it.animatedValue as Int, Lum.GHOST)
            }
            start()
        }
        onCreated(drawable, animator)
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newChats: List<TelegramChat>) {
        items.clear()
        items.addAll(newChats)
        if (selectedPosition < 0 || selectedPosition >= items.size) {
            selectedPosition = if (items.isNotEmpty()) 0 else -1
        }
        notifyDataSetChanged()
    }

    fun getSelectedChat(): TelegramChat? {
        return if (selectedPosition in items.indices) items[selectedPosition] else null
    }

    fun updateChat(chatId: String, lastMessage: String, lastSender: String, unreadCount: Int? = null) {
        val idx = items.indexOfFirst { it.chatId == chatId }
        if (idx >= 0) {
            items[idx] = items[idx].copy(
                lastMessage = lastMessage,
                lastMessageSender = lastSender,
                unreadCount = unreadCount ?: items[idx].unreadCount
            )
            notifyItemChanged(idx)
        }
    }

    override fun selectPosition(pos: Int) {
        if (pos < 0 || pos >= items.size) return
        val old = selectedPosition
        selectedPosition = pos
        if (old in items.indices) notifyItemChanged(old)
        notifyItemChanged(pos)
    }

    override fun moveSelectionDown() {
        if (items.isEmpty()) return
        if (selectedPosition < items.size - 1) selectPosition(selectedPosition + 1)
    }

    override fun moveSelectionUp() {
        if (items.isEmpty()) return
        if (selectedPosition > 0) selectPosition(selectedPosition - 1)
    }

    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(rv: RecyclerView) {
        super.onAttachedToRecyclerView(rv)
        recyclerView = rv
    }

    override fun onDetachedFromRecyclerView(rv: RecyclerView) {
        super.onDetachedFromRecyclerView(rv)
        recyclerView = null
    }

    override fun setFocused(isFocused: Boolean) {
        if (focused == isFocused) return
        focused = isFocused
        // Directly clear/set borders on visible ViewHolders
        val rv = recyclerView ?: return
        for (i in 0 until rv.childCount) {
            val holder = rv.getChildViewHolder(rv.getChildAt(i)) as? ChatViewHolder ?: continue
            holder.borderAnimator?.cancel()
            if (focused && holder.adapterPosition == selectedPosition) {
                animateBorder(holder.container) { drawable, animator ->
                    holder.currentDrawable = drawable
                    holder.borderAnimator = animator
                }
            } else {
                holder.currentDrawable = null
                holder.container.setBackgroundColor(Lum.VOID)
            }
        }
    }
}
