package com.biometric.app.ui

import android.content.res.Configuration
import android.os.Bundle
import android.transition.Fade
import android.transition.Slide
import android.transition.TransitionSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnticipateOvershootInterpolator
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.biometric.app.R
import com.biometric.app.util.MotionManager
import com.biometric.app.util.ThemeManager
import com.biometric.app.sync.ThemePreferenceSync
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.components.SingletonComponent
import com.google.android.material.appbar.AppBarLayout

import androidx.core.view.WindowInsetsControllerCompat

/**
 * Base activity providing standard motion transitions and Edge-to-Edge support.
 */
abstract class MotionBaseActivity : SecurityBaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Step 1: Enable system-level edge-to-edge
        enableEdgeToEdge()
        
        window.setBackgroundDrawableResource(R.color.window_background)
        window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
        
        // Handle Status Bar Icon Color based on theme
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNight
            isAppearanceLightNavigationBars = !isNight
        }
        
        val enterTransition = TransitionSet().apply {
            addTransition(Fade())
            duration = 300
            interpolator = AccelerateDecelerateInterpolator()
        }
        val exitTransition = TransitionSet().apply {
            addTransition(Fade())
            duration = 250
        }
        
        window.enterTransition = enterTransition
        window.exitTransition = exitTransition
        window.reenterTransition = Fade().apply { duration = 300 }
        
        window.allowEnterTransitionOverlap = true
        window.allowReturnTransitionOverlap = true
        
        super.onCreate(savedInstanceState)
    }

    /**
     * Standardized inset application. Call this in onCreate after setContentView.
     * Ensures top content (like Toolbar) and bottom content (like Navigation) respect safe areas.
     */
    protected fun applyWindowInsets(rootView: View, appBarLayout: AppBarLayout? = null) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            // If AppBarLayout is provided, apply top padding to it for a clean header look
            if (appBarLayout != null) {
                appBarLayout.updatePadding(top = systemBars.top)
                v.updatePadding(bottom = systemBars.bottom)
            } else {
                // Fallback: apply both top and bottom to the root view
                v.updatePadding(
                    top = systemBars.top,
                    bottom = systemBars.bottom
                )
            }
            insets
        }
    }

    protected fun animateContentEntry(viewGroup: ViewGroup) {
        val count = viewGroup.childCount
        for (i in 0 until count) {
            val child = viewGroup.getChildAt(i)
            child.alpha = 0f
            child.translationY = 30f
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(450)
                .setStartDelay((i * 30).toLong())
                .setInterpolator(AnticipateOvershootInterpolator(0.8f))
                .start()
        }
    }

    protected fun setupMotionFeedback(vararg views: View) {
        views.forEach { MotionManager.applyTouchScale(it) }
    }

    protected fun setupDualHeader(toolbar: Toolbar, line1: String, line2: String): HeaderRefs {
        toolbar.title = ""
        toolbar.subtitle = ""
        
        supportActionBar?.let {
            it.title = ""
            it.subtitle = ""
            it.setDisplayShowTitleEnabled(false)
        }
        
        // Clean up previous header views to prevent duplicates on rotation/theme toggle
        val existingHeader = toolbar.findViewById<View>(R.id.llHeaderContainer)
        if (existingHeader != null) {
            toolbar.removeView(existingHeader)
        }
        
        val headerView = layoutInflater.inflate(R.layout.layout_toolbar_dual, toolbar, false)
        val lp = Toolbar.LayoutParams(Toolbar.LayoutParams.MATCH_PARENT, Toolbar.LayoutParams.WRAP_CONTENT)
        toolbar.addView(headerView, lp)
        
        val tvLine1 = headerView.findViewById<TextView>(R.id.tvHeaderLine1)
        val tvLine2 = headerView.findViewById<TextView>(R.id.tvHeaderLine2)
        val tvStatus = headerView.findViewById<TextView>(R.id.tvHeaderStatus)
        val tvLine3 = headerView.findViewById<TextView>(R.id.tvHeaderLine3)
        
        val btnTheme = headerView.findViewById<ImageButton>(R.id.btnThemeToggle)
        val btnShop = headerView.findViewById<ImageButton>(R.id.btnShopToggle)
        
        btnTheme?.visibility = View.VISIBLE
        btnShop?.visibility = View.GONE
        
        tvLine1.text = line1
        tvLine2.text = line2
        
        btnTheme?.setOnClickListener {
            MotionManager.playClickBlast(it)
            // Stagger the theme change to allow the click feedback and ripple to complete,
            // which helps prevent main-thread hangs during Activity recreation.
            it.postDelayed({
                val theme = ThemeManager.toggleTheme(
                    this,
                    getSharedPreferences("mobile_session", MODE_PRIVATE)
                        .getString("email", "")
                        .orEmpty()
                        .ifBlank {
                            getSharedPreferences("user_prefs", MODE_PRIVATE)
                                .getString("user_uid", "default")
                                .orEmpty()
                        }
                )
                lifecycleScope.launch {
                    runCatching {
                        EntryPointAccessors.fromApplication(
                            applicationContext,
                            ThemeEntryPoint::class.java
                        ).themePreferenceSync.persist(theme)
                    }
                }
            }, 200)
        }
        
        return HeaderRefs(tvLine1, tvLine2, tvStatus, tvLine3, btnTheme, btnShop)
    }

    @EntryPoint
    @dagger.hilt.InstallIn(SingletonComponent::class)
    interface ThemeEntryPoint {
        val themePreferenceSync: ThemePreferenceSync
    }

    data class HeaderRefs(
        val line1: TextView,
        val line2: TextView,
        val status: TextView,
        val line3: TextView,
        val btnTheme: ImageButton? = null,
        val btnShop: ImageButton? = null,
    )
}
