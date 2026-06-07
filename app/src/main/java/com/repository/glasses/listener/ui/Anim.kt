package com.repository.glasses.listener.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.Resources
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.repository.glasses.listener.R
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

object Anim {

    private val decelerate = DecelerateInterpolator()
    private val smooth = AccelerateDecelerateInterpolator()

    // Spring config for tab pill/scale: snappy with slight bounce
    private const val TAB_STIFFNESS = 1200f
    private const val TAB_DAMPING = 0.65f

    private fun Float.dpToPx(): Float =
        this * Resources.getSystem().displayMetrics.density

    /** Fade a view in from alpha 0 to 1. Sets VISIBLE before starting. */
    fun fadeIn(view: View, duration: Long = 250L, onEnd: (() -> Unit)? = null) {
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(decelerate)
            .withEndAction { onEnd?.invoke() }
            .start()
    }

    /** Fade a view out from current alpha to 0. Optionally set GONE on end. */
    fun fadeOut(view: View, duration: Long = 150L, gone: Boolean = true, onEnd: (() -> Unit)? = null) {
        view.animate()
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(decelerate)
            .withEndAction {
                if (gone) view.visibility = View.GONE
                onEnd?.invoke()
            }
            .start()
    }

    /**
     * Crossfade: fade out one view, fade in another simultaneously.
     * Both views emit light during overlap -- use fadeOutThenSlideIn for waveguide-safe sequential transition.
     */
    fun crossfade(outView: View, inView: View, duration: Long = 200L, onEnd: (() -> Unit)? = null) {
        fadeOut(outView, duration, gone = true)
        fadeIn(inView, duration, onEnd)
    }

    /** Slide a view up from an offset while fading in. */
    fun slideUp(view: View, distanceDp: Float = 8f, duration: Long = 250L, onEnd: (() -> Unit)? = null) {
        view.alpha = 0f
        view.translationY = distanceDp.dpToPx()
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(duration)
            .setInterpolator(decelerate)
            .withEndAction { onEnd?.invoke() }
            .start()
    }

    /** Fade out then slide in a replacement (sequential, not simultaneous). */
    fun fadeOutThenSlideIn(
        outView: View,
        inView: View,
        fadeDuration: Long = 150L,
        slideDuration: Long = 200L,
        slideDistDp: Float = 4f,
        onEnd: (() -> Unit)? = null
    ) {
        fadeOut(outView, fadeDuration, gone = true) {
            slideUp(inView, slideDistDp, slideDuration, onEnd)
        }
    }

    /** Pulse a TextView color between two values in a loop. Returns the animator so caller can cancel. */
    fun pulse(textView: TextView, fromColor: Int, toColor: Int, duration: Long = 1500L): ValueAnimator {
        return ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor).apply {
            this.duration = duration
            interpolator = smooth
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { textView.setTextColor(it.animatedValue as Int) }
            start()
        }
    }

    /** Animate a view's translationX to a target value. */
    fun translateX(view: View, targetX: Float, duration: Long = 200L, onEnd: (() -> Unit)? = null) {
        view.animate()
            .translationX(targetX)
            .setDuration(duration)
            .setInterpolator(decelerate)
            .withEndAction { onEnd?.invoke() }
            .start()
    }

    /** Animate an ImageView tint color. */
    fun tintColor(view: View, fromColor: Int, toColor: Int, duration: Long = 200L): ValueAnimator {
        return ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor).apply {
            this.duration = duration
            interpolator = decelerate
            addUpdateListener {
                val color = it.animatedValue as Int
                if (view is android.widget.ImageView) {
                    view.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
                }
            }
            start()
        }
    }

    /**
     * Pulse a button container: outline appears and fades, background lights up and dims.
     * Single cycle (not looping). Uses GradientDrawable for outline + fill.
     */
    fun pulseButton(container: View, duration: Long = 400L, outlineColor: Int = Lum.GLOW, fillColor: Int = Lum.TRACE) {
        val density = Resources.getSystem().displayMetrics.density
        val maxStroke = (2f * density + 0.5f).toInt()
        val cornerR = 4f * density
        val drawable = android.graphics.drawable.GradientDrawable().apply {
            setColor(Lum.VOID)
            cornerRadius = cornerR
            setStroke(0, outlineColor)
        }
        container.background = drawable

        val half = duration / 2
        // Phase 1: in
        ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = half
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val f = it.animatedFraction
                val stroke = (maxStroke * f).toInt()
                val fill = android.graphics.Color.argb(
                    (android.graphics.Color.alpha(fillColor) * f * 0.5f).toInt().coerceIn(0, 255),
                    android.graphics.Color.red(fillColor),
                    android.graphics.Color.green(fillColor),
                    android.graphics.Color.blue(fillColor)
                )
                drawable.setStroke(stroke, outlineColor)
                drawable.setColor(fill)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Phase 2: out
                    ValueAnimator.ofFloat(1f, 0f).apply {
                        this.duration = half
                        interpolator = android.view.animation.LinearInterpolator()
                        addUpdateListener {
                            val f = it.animatedFraction
                            val stroke = (maxStroke * (1f - f)).toInt()
                            val fill = android.graphics.Color.argb(
                                (android.graphics.Color.alpha(fillColor) * (1f - f) * 0.5f).toInt().coerceIn(0, 255),
                                android.graphics.Color.red(fillColor),
                                android.graphics.Color.green(fillColor),
                                android.graphics.Color.blue(fillColor)
                            )
                            drawable.setStroke(stroke, outlineColor)
                            drawable.setColor(fill)
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                container.setBackgroundColor(Lum.VOID)
                            }
                        })
                        start()
                    }
                }
            })
            start()
        }
    }

    /** Crossfade text on a TextView: fade out, update text, fade in. */
    fun crossfadeText(textView: TextView, newText: String, duration: Long = 150L, onEnd: (() -> Unit)? = null) {
        crossfadeText(textView, newText as CharSequence, duration, onEnd)
    }

    /** Crossfade text on a TextView: fade out, update text, fade in. CharSequence variant for Spannables. */
    fun crossfadeText(textView: TextView, newText: CharSequence, duration: Long = 150L, onEnd: (() -> Unit)? = null) {
        textView.animate()
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(decelerate)
            .withEndAction {
                textView.text = newText
                textView.animate()
                    .alpha(1f)
                    .setDuration(duration)
                    .setInterpolator(decelerate)
                    .withEndAction { onEnd?.invoke() }
                    .start()
            }
            .start()
    }

    // --- Spring animations for tab switcher ---

    /** Spring-animate a view's translationX. Returns the SpringAnimation so caller can cancel. */
    fun springTranslateX(view: View, targetX: Float): SpringAnimation {
        val existing = view.getTag(R.id.spring_translate_x) as? SpringAnimation
        existing?.cancel()
        return SpringAnimation(view, DynamicAnimation.TRANSLATION_X, targetX).apply {
            spring = SpringForce(targetX)
                .setStiffness(TAB_STIFFNESS)
                .setDampingRatio(TAB_DAMPING)
            view.setTag(R.id.spring_translate_x, this)
            start()
        }
    }

    /** Spring-animate a view's scaleX + scaleY together. */
    fun springScale(view: View, targetScale: Float): Pair<SpringAnimation, SpringAnimation> {
        val existingX = view.getTag(R.id.spring_scale_x) as? SpringAnimation
        val existingY = view.getTag(R.id.spring_scale_y) as? SpringAnimation
        existingX?.cancel()
        existingY?.cancel()
        val sx = SpringAnimation(view, DynamicAnimation.SCALE_X, targetScale).apply {
            spring = SpringForce(targetScale)
                .setStiffness(TAB_STIFFNESS)
                .setDampingRatio(TAB_DAMPING)
            view.setTag(R.id.spring_scale_x, this)
            start()
        }
        val sy = SpringAnimation(view, DynamicAnimation.SCALE_Y, targetScale).apply {
            spring = SpringForce(targetScale)
                .setStiffness(TAB_STIFFNESS)
                .setDampingRatio(TAB_DAMPING)
            view.setTag(R.id.spring_scale_y, this)
            start()
        }
        return sx to sy
    }

}
