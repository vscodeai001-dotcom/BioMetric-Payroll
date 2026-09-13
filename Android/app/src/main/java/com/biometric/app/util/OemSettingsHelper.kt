package com.biometric.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object OemSettingsHelper {

    private val AUTO_START_INTENTS = arrayOf(
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        Intent().setComponent(ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity")),
        Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")),
        Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.g3.appstartmgr.AppControlStartMgrActivity")),
        Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")),
        Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")),
        Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")),
        Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")),
        Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")),
        Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")),
        Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
        Intent().setComponent(ComponentName("com.htc.pitroad", "com.htc.pitroad.landingpage.LandingPageActivity")),
        Intent().setComponent(ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity")).setData(Uri.parse("mobilemanager://launch_only?entry=one_touch_optimize"))
    )

    fun openAutoStartSettings(context: Context) {
        for (intent in AUTO_START_INTENTS) {
            if (isIntentCallable(context, intent)) {
                try {
                    context.startActivity(intent)
                    return
                } catch (e: Exception) {
                    // Try next
                }
            }
        }
        // Fallback to app details
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        context.startActivity(intent)
    }

    private fun isIntentCallable(context: Context, intent: Intent): Boolean {
        val list = context.packageManager.queryIntentActivities(intent, 0)
        return list.size > 0
    }

    fun showOemSettingsDialog(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        if (manufacturer == "google" || manufacturer == "pixel") return

        MaterialAlertDialogBuilder(context)
            .setTitle("Device Specific Optimization 🛠️")
            .setMessage("Your device ($manufacturer) might kill background tracking to save battery. Please enable 'Auto-start' or 'Background Activity' for this app.")
            .setPositiveButton("Open Settings") { _, _ ->
                openAutoStartSettings(context)
            }
            .setNegativeButton("Ignore", null)
            .show()
    }
}
