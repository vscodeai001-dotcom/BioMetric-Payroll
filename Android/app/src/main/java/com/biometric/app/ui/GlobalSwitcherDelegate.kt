package com.biometric.app.ui

import com.biometric.app.sync.AdminRealtimeCoordinator
import com.biometric.app.sync.ThemePreferenceSync
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.biometric.app.api.MobileApiService
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.UserRole
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.biometric.app.R
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.util.ThemeManager
import com.google.firebase.auth.FirebaseAuth
import java.io.File

object GlobalSwitcherDelegate {

    private var activePopup: PopupWindow? = null

    fun dismissPopup() {
        activePopup?.dismiss()
        activePopup = null
    }

    fun inflateMenu(
        menuInflater: MenuInflater, 
        menu: Menu?, 
        showShopSwitcher: Boolean = true,
        extraActions: List<ActionItem> = emptyList(),
        activity: AppCompatActivity? = null,
    ) {
        menuInflater.inflate(R.menu.global_switch_menu, menu)
        
        // HIDE menu items that are now managed by the Premium Toolbar Header
        menu?.findItem(R.id.action_theme)?.isVisible = false
        menu?.findItem(R.id.action_shop)?.isVisible = false
        
        // Hide "More" icon if it will be empty
        // Condition: Not MainActivity (where Sync lives) AND no extra actions provided
        val isMain = activity is MainActivity
        if (!isMain && extraActions.isEmpty()) {
            menu?.findItem(R.id.action_more_global)?.isVisible = false
            menu?.findItem(R.id.action_kitchen)?.isVisible = false
        }
    }

    fun handleOptionsItemSelected(
        activity: AppCompatActivity,
        item: MenuItem,
        sharedViewModel: SharedViewModel,
        extraActions: List<ActionItem> = emptyList(),
    ): Boolean {
        if (activity.isFinishing || activity.isDestroyed) return false
        
        when (item.itemId) {
            R.id.action_theme -> {
                // Stagger the theme change to allow the menu to close and animations to finish
                val view = activity.findViewById<View>(R.id.action_theme) ?: activity.window.decorView
                view.postDelayed({
                    val localTheme = ThemeManager.toggleTheme(
                        activity,
                        resolveThemeUserKey(activity)
                    )
                    activity.lifecycleScope.launch {
                        runCatching {
                            themeSync(activity).persist(localTheme)
                        }.onFailure {
                            // Local cache remains active; next authenticated login/start
                            // will reconcile with the server preference.
                        }
                    }
                }, 200)
                return true
            }
            R.id.action_shop -> {
                handleShopSwitch(activity, sharedViewModel)
                return true
            }
            R.id.action_more_global, R.id.action_kitchen -> {
                val toolbar = activity.findViewById<View>(R.id.toolbar)
                val actionView = activity.findViewById(item.itemId) 
                    ?: toolbar?.findViewById(item.itemId)
                    ?: toolbar
                    ?: activity.window.decorView

                val profileRole = sharedViewModel.userProfile.value?.role.orEmpty()
                val storedRole = activity.applicationContext
                    .getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
                    .getString("user_role", "")
                    .orEmpty()
                val isAdmin = profileRole.contains("Admin", true) ||
                    storedRole == UserRole.ADMIN.name ||
                    storedRole == UserRole.SUPER_ADMIN.name
                
                showQuickActionPopup(activity, actionView, extraActions, isAdmin)
                return true
            }
        }
        return false
    }


    private fun resolveThemeUserKey(activity: AppCompatActivity): String =
        activity.getSharedPreferences("mobile_session", Context.MODE_PRIVATE)
            .getString("email", "")
            .orEmpty()
            .ifBlank {
                activity.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                    .getString("user_uid", "default")
                    .orEmpty()
            }

    private fun themeSync(activity: AppCompatActivity): ThemePreferenceSync {
        val entryPoint = EntryPointAccessors.fromApplication(
            activity.applicationContext,
            ThemeEntryPoint::class.java
        )
        return entryPoint.themePreferenceSync
    }

    fun shareApp(activity: AppCompatActivity) {
        try {
            val appInfo = activity.applicationInfo
            val originalApk = File(appInfo.publicSourceDir)
            
            // Generate a clean filename for sharing
            val versionName = try {
                activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
            } catch (_: Exception) { "1.0" }
            
            val tempApk = File(activity.cacheDir, "BiometricPayroll_v$versionName.apk")
            
            if (originalApk.exists()) {
                originalApk.copyTo(tempApk, overwrite = true)
                
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", tempApk)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.android.package-archive"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(Intent.createChooser(intent, "Share Biometric Payroll App 📤"))
            } else {
                Toast.makeText(activity, "Source APK not found.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(activity, "Failed to extract APK: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun handleShopSwitch(activity: AppCompatActivity, sharedViewModel: SharedViewModel) {
        val shops = sharedViewModel.allShops.value
        if (shops.isEmpty()) {
            Toast.makeText(activity, "Loading workplaces... ⏳", Toast.LENGTH_SHORT).show()
            return
        }

        if (shops.size <= 2) {
            // Auto-toggle between shops
            val currentShop = sharedViewModel.selectedShop.value
            val nextShop = if ((currentShop == null) || (currentShop.shopId == shops.last().shopId)) {
                shops.first()
            } else {
                shops.find { it.shopId != currentShop.shopId } ?: shops.first()
            }
            sharedViewModel.setSelectedShop(nextShop)
            Toast.makeText(activity, "Switched to ${nextShop.name} 🏢", Toast.LENGTH_SHORT).show()
        } else {
            // Show selection dialog
            showShopSelectionDialog(activity, sharedViewModel)
        }
    }

    private fun performLogout(activity: AppCompatActivity) {
        val entryPoint = EntryPointAccessors.fromApplication(
            activity.applicationContext,
            LogoutEntryPoint::class.java
        )
        val session = entryPoint.sessionStore
        val api = entryPoint.mobileApi
        val realtime = entryPoint.realtime
        val token = session.token()

        activity.lifecycleScope.launch {
            try {
                if (!token.isNullOrBlank()) {
                    runCatching { api.logout("Bearer $token") }
                }
            } finally {
                try { realtime.stop() } catch (_: Exception) {}
                try { FirebaseAuth.getInstance().signOut() } catch (_: Exception) {}
                session.clearLogin()
                activity.applicationContext
                    .getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
                    .edit().clear().apply()
                activity.applicationContext
                    .getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                    .edit().clear().apply()
                SecurityBaseActivity.clearProcessAuthorization(activity.applicationContext)

                activity.startActivity(Intent(activity, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                activity.finish()
            }
        }
    }

    @EntryPoint
    @dagger.hilt.InstallIn(SingletonComponent::class)
    interface ThemeEntryPoint {
        val themePreferenceSync: ThemePreferenceSync
    }

    @EntryPoint
    @dagger.hilt.InstallIn(SingletonComponent::class)
    interface LogoutEntryPoint {
        val mobileApi: MobileApiService
        val sessionStore: MobileSessionStore
        val realtime: AdminRealtimeCoordinator
    }

    private fun showQuickActionPopup(
        activity: AppCompatActivity,
        anchor: View,
        extraActions: List<ActionItem>,
        showLogoutAsDefault: Boolean = false
    ) {
        dismissPopup() // Dismiss any previous popup to avoid leaks
        
        val popupView = activity.layoutInflater.inflate(
            R.layout.popup_global_actions,
            activity.findViewById(android.R.id.content),
            false
        )
        val container = popupView.findViewById<ViewGroup>(R.id.llActionContainer)
        
        val density = activity.resources.displayMetrics.density
        val iconSizePx = (42 * density).toInt() 
        val totalPaddingPx = (8 * density).toInt() 

        val finalActions = extraActions.toMutableList()
        val hasLogout = finalActions.any { it.emoji == "🚪" }
        
        // Add Logout automatically for Admin/SuperAdmin roles if not already present
        if (!hasLogout && showLogoutAsDefault) {
             finalActions.add(ActionItem("🚪", "Logout") {
                 MaterialAlertDialogBuilder(activity)
                     .setTitle("Logout 🚪")
                     .setMessage("Are you sure you want to sign out?")
                     .setPositiveButton("Logout") { _, _ ->
                         performLogout(activity)
                     }
                     .setNegativeButton("Cancel", null)
                     .show()
             })
        }
        
        val requiredWidth = (finalActions.size * iconSizePx) + totalPaddingPx
        val maxWidth = (activity.resources.displayMetrics.widthPixels * 0.98).toInt() 
        
        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            if (requiredWidth > maxWidth) {
                width = maxWidth
            } else {
                width = ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        activePopup = popupWindow

        popupWindow.elevation = 10f

        // Theme and Shop are now directly in toolbar.
        popupView.findViewById<View>(R.id.btnTheme).visibility = View.GONE
        popupView.findViewById<View>(R.id.btnShop).visibility = View.GONE

        val btnSync = popupView.findViewById<View>(R.id.btnSync)
        val btnLogout = popupView.findViewById<View>(R.id.btnLogout)
        
        btnSync.visibility = View.GONE
        btnLogout.visibility = View.GONE

        // Add Final Actions dynamically
        finalActions.forEach { action ->
            val textView = TextView(activity).apply {
                val size = (42 * activity.resources.displayMetrics.density).toInt() // Matched with container logic
                layoutParams = LinearLayout.LayoutParams(size, size)
                gravity = Gravity.CENTER
                textSize = 24f
                text = action.emoji
                
                val outValue = TypedValue()
                activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
                background = ContextCompat.getDrawable(activity, outValue.resourceId)

                isClickable = true
                isFocusable = true
                setOnClickListener {
                    try {
                        action.onClick()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(activity, "Navigation failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    popupWindow.dismiss()
                }
            }
            container?.addView(textView)
        }

        if (anchor.isAttachedToWindow && anchor.isVisible) {
            popupWindow.showAsDropDown(anchor, 0, 0, Gravity.END)
        } else {
            popupWindow.showAtLocation(activity.window.decorView, Gravity.CENTER, 0, 0)
        }

        // Auto-dismiss when anchor is detached to avoid window leaks
        anchor.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    if (popupWindow.isShowing) {
                        try {
                            popupWindow.dismiss()
                        } catch (_: Exception) {}
                    }
                    anchor.removeOnAttachStateChangeListener(this)
                }
            }
        )
    }

    private fun showShopSelectionDialog(
        activity: AppCompatActivity,
        sharedViewModel: SharedViewModel
    ) {
        val shops = sharedViewModel.allShops.value
        val shopNames = shops.map { it.name }.toTypedArray()
        
        MaterialAlertDialogBuilder(activity)
            .setTitle("Switch Shop 🏪")
            .setItems(shopNames) { _, which: Int ->
                val selectedShop = shops[which]
                sharedViewModel.setSelectedShop(selectedShop)
                Toast.makeText(activity, "Switched to ${selectedShop.name}", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    data class ActionItem(val emoji: String, val label: String, val onClick: () -> Unit)
}
