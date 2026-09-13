package com.biometric.app.util

import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.RotateAnimation
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.airbnb.lottie.LottieAnimationView
import com.biometric.app.R
import com.biometric.app.databinding.LayoutBrewingLoaderBinding
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.*
import java.util.Locale

object PremiumLoader {

    enum class ScreenType {
        GENERIC, ATTENDANCE, STAFF, SALARY, AUDIT, RECYCLE_BIN, RISK
    }

    private val activeJobs = mutableMapOf<Int, Job>()

    fun show(
        binding: LayoutBrewingLoaderBinding, 
        type: ScreenType = ScreenType.GENERIC,
        scope: CoroutineScope? = null,
        immediate: Boolean = false,
        lifecycleOwner: LifecycleOwner? = null
    ) {
        showInternal(binding.root, type, scope, immediate, binding.hashCode(), lifecycleOwner)
    }

    private fun showInternal(
        root: View, 
        type: ScreenType, 
        scope: CoroutineScope?, 
        immediate: Boolean,
        bindingId: Int,
        lifecycleOwner: LifecycleOwner?
    ) {
        if (activeJobs.containsKey(bindingId) && root.visibility == View.VISIBLE) {
            return
        }
        activeJobs[bindingId]?.cancel()
        
        if (!immediate) {
            root.alpha = 0f
        } else {
            root.alpha = 1f
            root.visibility = View.VISIBLE
        }
        
        lifecycleOwner?.lifecycle?.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                activeJobs[bindingId]?.cancel()
                activeJobs.remove(bindingId)
            }
        })
        
        scope?.let { s ->
            val job = s.launch {
                if (!immediate) {
                    delay(150)
                    withContext(Dispatchers.Main) {
                        setupUI(root, type)
                        root.visibility = View.VISIBLE
                        root.animate().alpha(1f).setDuration(400).start()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        setupUI(root, type)
                        root.visibility = View.VISIBLE
                        root.alpha = 1f
                    }
                }
                runProgressLoop(root, bindingId)
            }
            activeJobs[bindingId] = job
        }
    }

    private fun setupUI(root: View, type: ScreenType) {
        val lottieAnim = root.findViewById<LottieAnimationView>(R.id.lottieLoadingAnim)
        val tvBrewingLabel = root.findViewById<TextView>(R.id.tvBrewingLabel)
        val tvLoaderIcon = root.findViewById<TextView>(R.id.tvLoaderIcon)
        val ivNeonRing = root.findViewById<ImageView>(R.id.ivNeonRing)
        val cardLoader = root.findViewById<View>(R.id.cardLoader)
        val progressIndicator = root.findViewById<CircularProgressIndicator>(R.id.progressIndicator)
        val tvProgressPercent = root.findViewById<TextView>(R.id.tvProgressPercent)

        progressIndicator?.progress = 0
        tvProgressPercent?.text = "0%"

        val (animRes, emoji) = when(type) {
            ScreenType.ATTENDANCE -> R.raw.anim_attendance to "👥"
            ScreenType.AUDIT -> R.raw.anim_audit_trail to "🛡️"
            ScreenType.RECYCLE_BIN -> R.raw.anim_bg_particles to "🗑️"
            else -> R.raw.anim_bg_particles to "🏢"
        }
        lottieAnim?.setAnimation(animRes)
        lottieAnim?.playAnimation()
        tvLoaderIcon?.text = emoji

        val label = when(type) {
            ScreenType.ATTENDANCE -> "👥 Staff Records..."
            ScreenType.AUDIT -> "🛡️ Security Audit..."
            ScreenType.STAFF -> "🧑‍💼 Employee Data..."
            ScreenType.SALARY -> "💰 Payroll Engine..."
            else -> "🏢 Workforce Hub..."
        }
        tvBrewingLabel?.text = label

        ivNeonRing?.let {
            val rotate = RotateAnimation(0f, 360f, 1, 0.5f, 1, 0.5f).apply {
                duration = 1500; repeatCount = -1; interpolator = LinearInterpolator()
            }
            it.startAnimation(rotate)
        }

        cardLoader?.let {
            it.scaleX = 0.5f; it.scaleY = 0.5f
            it.animate().scaleX(1f).scaleY(1f).setDuration(700).setInterpolator(
                OvershootInterpolator(1.3f)
            ).start()
        }
    }

    private suspend fun runProgressLoop(root: View, bindingId: Int) {
        val tvSubLabel = root.findViewById<TextView>(R.id.tvSubLabel)
        val tvProgressPercent = root.findViewById<TextView>(R.id.tvProgressPercent)
        val progressIndicator = root.findViewById<CircularProgressIndicator>(R.id.progressIndicator)
        
        val tips = listOf(
            "Tip: Long-press a workplace to manage its staff.",
            "Tip: Cloud backup is automated every 24 hours.",
            "Tip: Check 'Approvals' for pending leave requests.",
            "Tip: Real-time GPS tracking ensures field safety."
        )
        var tipIndex = 0
        var progress = 0
        
        coroutineScope {
            while (isActive && root.visibility == View.VISIBLE) {
                if (progress % 25 == 0) {
                    withContext(Dispatchers.Main) {
                        tvSubLabel?.animate()?.alpha(0f)?.setDuration(400)?.withEndAction {
                            tvSubLabel.text = tips[tipIndex % tips.size]
                            tipIndex++
                            tvSubLabel.animate().alpha(0.85f).setDuration(500).start()
                        }?.start()
                    }
                }
                if (progress < 100) {
                    progress += (2..5).random()
                    if (progress > 100) progress = 100
                    withContext(Dispatchers.Main) {
                        progressIndicator?.setProgress(progress, true)
                        tvProgressPercent?.text = String.format(Locale.getDefault(), "%d%%", progress)
                    }
                }
                delay(150)
            }
        }
        activeJobs.remove(bindingId)
    }

    fun hide(binding: LayoutBrewingLoaderBinding) {
        val id = binding.hashCode()
        activeJobs[id]?.cancel()
        activeJobs.remove(id)
        binding.root.visibility = View.GONE
    }
}
