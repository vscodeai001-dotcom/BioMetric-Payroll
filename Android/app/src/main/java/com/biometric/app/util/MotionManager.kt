package com.biometric.app.util

import android.view.View
import com.airbnb.lottie.LottieAnimationView
import com.biometric.app.R

object MotionManager {

    /**
     * Standard CRUD Feedback Animations
     */
    fun playCreateBlast(lottieView: LottieAnimationView) {
        // Using common lottie resources (Expects files in res/raw)
        try {
            lottieView.apply {
                setAnimation(R.raw.anim_create_blast)
                repeatCount = 0
                visibility = View.VISIBLE
                playAnimation()
            }
        } catch (_: Exception) {
            // Fallback to simple alpha animation if resource missing
            lottieView.alpha = 0f
            lottieView.animate().alpha(1f).setDuration(500).start()
        }
    }

    fun playUpdateMorph(lottieView: LottieAnimationView) {
        try {
            lottieView.apply {
                setAnimation(R.raw.anim_update_morph)
                repeatCount = 0
                visibility = View.VISIBLE
                playAnimation()
            }
        } catch (_: Exception) {
            lottieView.rotation = 0f
            lottieView.animate().rotation(360f).setDuration(500).start()
        }
    }

    fun playDeleteDissolve(lottieView: LottieAnimationView) {
        try {
            lottieView.apply {
                setAnimation(R.raw.anim_delete_dissolve)
                repeatCount = 0
                visibility = View.VISIBLE
                playAnimation()
            }
        } catch (_: Exception) {
            lottieView.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(500).start()
        }
    }

    /**
     * Counter Animation for Currency/Numbers
     */
    fun animateValue(textView: android.widget.TextView, start: Double, end: Double, prefix: String = "", duration: Long = 1000) {
        val animator = android.animation.ValueAnimator.ofFloat(start.toFloat(), end.toFloat())
        animator.duration = duration
        animator.addUpdateListener { animation ->
            val value = animation.animatedValue as Float
            textView.text = String.format(java.util.Locale.getDefault(), "%s%.2f", prefix, value)
        }
        animator.start()
    }

    /**
     * Micro-interaction: Standard Tap Animation
     */
    fun playClickBlast(view: View) {
        view.animate()
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(100)
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(150)
                    .start()
            }
            .start()
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun applyTouchScale(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
                }
                android.view.MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    v.performClick()
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }
            }
            true
        }
    }

    /**
     * Adaptive Feedback: Pulse Animation for Profit/Loss
     */
    fun startPulse(view: View, isPositive: Boolean) {
        val scaleX = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.05f, 1.0f)
        val scaleY = android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.05f, 1.0f)
        
        android.animation.ObjectAnimator.ofPropertyValuesHolder(view, scaleX, scaleY).apply {
            duration = if (isPositive) 2000 else 1000 // Faster pulse for warning
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
    }

    /**
     * Applies standard premium transition to Dialog windows
     */
    fun applyDialogAnimation(dialog: android.app.Dialog) {
        dialog.window?.let { window ->
            window.setWindowAnimations(R.style.PremiumDialogAnimation)
            
            // Add slight fade in for the decor view
            window.decorView.alpha = 0f
            window.decorView.animate().alpha(1f).setDuration(300).start()
        }
    }

    /**
     * Individual card entrance animation for lists
     */
    fun animateCardIn(view: View, delay: Long = 0) {
        view.alpha = 0f
        view.translationX = 100f
        view.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(500)
            .setStartDelay(delay)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }
}
