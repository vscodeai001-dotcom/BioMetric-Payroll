package com.biometric.app.sync

import android.app.Activity
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-wide UI invalidation bridge.
 *
 * This class deliberately does not recreate Activities, change navigation, or
 * introduce a second data layer. It invokes existing no-argument load/refresh
 * methods on the currently visible screen after the central realtime sync.
 * Screens that do not expose one of the known existing loaders are left alone,
 * so their current flow is untouched.
 */
@Singleton
class RealtimeUiDispatcher @Inject constructor() {
    private var resumedActivity: Activity? = null
    private val resumedFragments = LinkedHashSet<Fragment>()

    private val activityMethods = mapOf(
        "MainActivity" to listOf("refreshFromCentralRealtime"),
        "AdminAttendanceActivity" to listOf("load"),
        "AdminManualPunchCorrectionActivity" to listOf("loadIssues"),
        "AdminPayrollActivity" to listOf("loadPreview", "loadHistory"),
        "AuditTrailActivity" to listOf("refreshData"),
        "EmployeeHomeActivity" to listOf("refreshRealtime"), // Updated for safe real-time sync
        "EmployeePunchActivity" to listOf("loadStatus"),
        "OfflineTrackingActivity" to listOf("refreshOnce"),
        "PunchCorrectionApprovalActivity" to listOf("load"),
        "CompanySetupActivity" to listOf("loadInitialData"),
        "CompanySettingsActivity" to listOf("loadInitialData"),
        "SettingsActivity" to listOf("loadSettings"),
        "ShopClosedDaysActivity" to listOf("refreshData"),
        "StaffActivity" to listOf("refreshRealtime"), // Updated for safe real-time sync
        "StaffDetailActivity" to listOf("refreshData", "refreshStaffListForCurrentFilter", "refreshSelectedEmployeeHistory"),
        "StaffPermissionActivity" to listOf("loadAdminPermissions"),
        "TrackingMapActivity" to listOf("observeLiveLocations"), // Added for live map parity
        "LeaveManagementActivity" to listOf("refreshData"), // Added for approvals parity
        "RegularizationActivity" to listOf("refreshData"), // Added for approvals parity
        "UserManagementActivity" to listOf("loadUsers"), // Added for user parity
        "PayslipListActivity" to emptyList(),
        "ReportCenterActivity" to emptyList()
    )

    private val fragmentMethods = mapOf(
        "ProfileFragment" to listOf("loadProfile"),
        "FbpDeclarationFragment" to listOf("loadFbpData"),
        "TaxDeclarationFragment" to listOf("loadTaxData"),
        "BonusesFragment" to listOf("loadBonuses"),
        "SalaryAdvancesFragment" to listOf("loadAdvances"),
        "ShiftScheduleFragment" to listOf("loadShifts"),
        "MyLeavesFragment" to listOf("loadLeaves", "loadBalances"),
        "ResignationFragment" to listOf("loadResignationStatus"),
        "RegularizationFragment" to listOf("loadRegularizations"),
        "AttendanceLogsFragment" to listOf("loadAttendance"),
        "PayslipListFragment" to listOf("loadPayslips")
    )

    @Synchronized
    fun onActivityResumed(activity: Activity) {
        resumedActivity = activity
        if (activity is FragmentActivity) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentCallbacks, false)
            // The Activity lifecycle callback may register after its current
            // fragments are already RESUMED. Seed the registry so the first
            // central realtime event reaches the visible fragment too.
            synchronized(this) {
                collectResumedFragments(activity.supportFragmentManager)
            }
        }
    }

    @Synchronized
    fun onActivityPaused(activity: Activity) {
        if (activity is FragmentActivity) {
            runCatching {
                activity.supportFragmentManager.unregisterFragmentLifecycleCallbacks(fragmentCallbacks)
            }
        }
        if (resumedActivity === activity) resumedActivity = null
    }

    fun refreshVisible() {
        val activity = synchronized(this) { resumedActivity }
        activity?.let { refreshActivity(it) }
        synchronized(this) {
            resumedFragments.toList().forEach { refreshFragment(it) }
        }
    }

    private fun collectResumedFragments(manager: FragmentManager) {
        manager.fragments.forEach { fragment ->
            if (fragment.lifecycle.currentState == Lifecycle.State.RESUMED) {
                resumedFragments.add(fragment)
            }
            if (fragment.childFragmentManager.fragments.isNotEmpty()) {
                collectResumedFragments(fragment.childFragmentManager)
            }
        }
    }

    private fun refreshActivity(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val names = activityMethods[activity.javaClass.simpleName].orEmpty()
        names.forEach { invokeNoArg(activity, it) }
    }

    private fun refreshFragment(fragment: Fragment) {
        if (!fragment.isAdded || fragment.lifecycle.currentState != Lifecycle.State.RESUMED) return
        val names = fragmentMethods[fragment.javaClass.simpleName].orEmpty()
        names.forEach { invokeNoArg(fragment, it) }
    }

    private fun invokeNoArg(target: Any, methodName: String) {
        runCatching {
            val method = target.javaClass.getDeclaredMethod(methodName)
            method.isAccessible = true
            method.invoke(target)
        }.onFailure {
            // A screen may legitimately change its internal loader signature.
            // Do not crash the UI because realtime invalidation could not invoke it.
            Log.d("RealtimeUiDispatcher", "Skip ${target.javaClass.simpleName}.$methodName", it)
        }
    }

    private val fragmentCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            synchronized(this@RealtimeUiDispatcher) { resumedFragments.add(f) }
        }

        override fun onFragmentPaused(fm: FragmentManager, f: Fragment) {
            synchronized(this@RealtimeUiDispatcher) { resumedFragments.remove(f) }
        }

        override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
            synchronized(this@RealtimeUiDispatcher) { resumedFragments.remove(f) }
        }
    }
}
