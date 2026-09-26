package com.biometric.app.ui

import android.content.res.Configuration
import android.os.Bundle
import android.transition.Fade
import android.transition.TransitionSet
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
import dagger.hilt.InstallIn

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
    protected fun applyWindowInsets(
        rootView: View,
        appBarLayout: AppBarLayout? = null,
        scrollContainer: View? = null
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val statusBarTop = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            ).top

            val navBars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars()
            )
            
            // 1. Top Safe Area (Status bar & camera cutout)
            val topBar: View? = appBarLayout 
                ?: findAppBarLayout(v)
                ?: v.findViewById<AppBarLayout?>(R.id.appBar)
                ?: v.findViewById<Toolbar?>(R.id.toolbar)
                ?: v.findViewById<com.google.android.material.appbar.MaterialToolbar?>(R.id.toolbar)

            if (topBar != null) {
                // Ensure AppBarLayout never applies internal duplicate insets
                if (topBar is AppBarLayout) {
                    topBar.fitsSystemWindows = false
                }
                
                // If topBar is a Toolbar without an AppBarLayout parent, ensure its height accommodates the status bar
                if (topBar !is AppBarLayout && topBar.parent !is AppBarLayout) {
                    val baseHeight = (topBar.getTag(R.id.top_inset_base_height) as? Int)
                        ?: topBar.layoutParams?.height?.takeIf { it > 0 }?.also { topBar.setTag(R.id.top_inset_base_height, it) }
                    if (baseHeight != null && baseHeight > 0) {
                        topBar.layoutParams?.height = baseHeight + statusBarTop
                    }
                }

                topBar.updatePadding(top = statusBarTop)
                v.updatePadding(top = 0)
            } else {
                v.updatePadding(top = statusBarTop)
            }

            // 2. Bottom Safe Area (Taskbar / Navigation buttons / Gestures)
            val bottomTarget = scrollContainer ?: v
            val baseBottomPadding = (bottomTarget.getTag(R.id.bottom_inset_base_padding) as? Int)
                ?: bottomTarget.paddingBottom.also { bottomTarget.setTag(R.id.bottom_inset_base_padding, it) }
            bottomTarget.updatePadding(bottom = baseBottomPadding + navBars.bottom)
            if (scrollContainer != null && scrollContainer != v) {
                v.updatePadding(bottom = 0)
            }
            
            insets
        }
        ViewCompat.requestApplyInsets(rootView)
    }

    private fun findAppBarLayout(view: View): AppBarLayout? {
        if (view is AppBarLayout) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findAppBarLayout(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun findScrollableView(view: View): View? {
        if (view is androidx.core.widget.NestedScrollView || 
            view is android.widget.ScrollView || 
            view is androidx.recyclerview.widget.RecyclerView) {
            return view
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val found = findScrollableView(child)
                if (found != null) return found
            }
        }
        return null
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
    @InstallIn(SingletonComponent::class)
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
