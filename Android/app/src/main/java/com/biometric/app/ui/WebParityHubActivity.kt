package com.biometric.app.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.biometric.app.R
import com.biometric.app.sync.FirebaseSyncManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Native Android index of the Web Admin application's major modules.
 *
 * This is an additive navigation layer. Existing Android screens remain the
 * owners of their business logic and CRUD workflows. Firebase remains the
 * shared SSOT and every destination is expected to observe the same realtime
 * data model rather than maintaining an Android-only copy.
 */
@AndroidEntryPoint
class WebParityHubActivity : MotionBaseActivity() {

    @Inject lateinit var firebaseSync: FirebaseSyncManager

    private data class Module(
        val icon: String,
        val title: String,
        val subtitle: String,
        val intentFactory: (() -> Intent)
    )

    private val modules by lazy {
        listOf(
            Module("👥", "Employee Records", "Employees • CRUD • details") { Intent(this, StaffActivity::class.java) },
            Module("🕘", "Attendance Log", "Attendance sessions and punches") { Intent(this, AdminAttendanceActivity::class.java) },
            Module("📋", "Company Attendance Report", "Daily/monthly attendance report") { Intent(this, ReportCenterActivity::class.java) },
            Module("✏️", "Punch Corrections", "Manual corrections and approvals") { Intent(this, AdminManualPunchCorrectionActivity::class.java) },
            Module("🔁", "Punch Approval", "Punch correction approval workflow") { Intent(this, PunchCorrectionApprovalActivity::class.java) },
            Module("📝", "Regularization Approval", "Attendance regularization workflow") { Intent(this, RegularizationActivity::class.java) },
            Module("🗓️", "Shift Scheduler", "Schedules and shop shifts") { Intent(this, ShiftManagerActivity::class.java) },
            Module("🏖️", "Leave Management", "Pending • approved • rejected") { Intent(this, LeaveManagementActivity::class.java) },
            Module("💰", "Payroll", "Preview • history • finalization") { Intent(this, AdminPayrollActivity::class.java) },
            Module("🧾", "Payslip / Payroll Detail", "Payroll records and employee payslip data") { Intent(this, AdminPayrollActivity::class.java) },
            Module("💳", "Advance Management", "Salary advances and recovery") { Intent(this, AdminFinanceActivity::class.java) },
            Module("🌟", "Bonus Management", "Bonus entries and history") { Intent(this, AdminFinanceActivity::class.java) },
            Module("🏛️", "Tax Declarations", "Admin review and status") { Intent(this, AdminFinanceActivity::class.java) },
            Module("🎁", "FBP Components", "Flexible benefit component configuration") { Intent(this, FbpComponentsActivity::class.java) },
            Module("📄", "FBP Declaration Approval", "Employee FBP approval workflow") { Intent(this, FbpDeclarationApprovalActivity::class.java) },
            Module("📅", "Year-End Summary", "Annual payroll compliance summary") { Intent(this, YearEndSummaryActivity::class.java) },
            Module("🚪", "Exit Management", "Resignation / exit workflow") { Intent(this, ExitManagementActivity::class.java) },
            Module("🏢", "Company Settings", "Company and statutory configuration") { Intent(this, SettingsActivity::class.java) },
            Module("⚙️", "Feature Settings", "Feature flags and permissions") { Intent(this, SettingsActivity::class.java) },
            Module("🏝️", "Holiday Management", "Shop closed days") { Intent(this, ShopClosedDaysActivity::class.java) },
            Module("👤", "User Management", "Admin • staff • permissions") { Intent(this, UserManagementActivity::class.java) },
            Module("📊", "Report Center", "Native reports and summaries") { Intent(this, ReportCenterActivity::class.java) },
            Module("🧾", "Audit Logs", "Realtime audit trail") { Intent(this, AuditTrailActivity::class.java) },
            Module("♻️", "Recycle Bin", "Restore deleted records") { Intent(this, RecycleBinActivity::class.java) },
            Module("📍", "Live Staff Tracking", "Realtime Firebase live locations") { Intent(this, TrackingMapActivity::class.java) },
            Module("🗺️", "Location History", "GPS history and route replay") { Intent(this, RouteReplayActivity::class.java) },
            Module("📍", "Location Stays", "Location visit/stay analysis") { Intent(this, LocationStaysActivity::class.java) },
            Module("📡", "Attendance Event Monitoring", "Realtime attendance events") { Intent(this, AttendanceEventMonitoringActivity::class.java) },
            Module("📴", "Offline Tracking", "Temporary queued GPS delivery") { Intent(this, OfflineTrackingActivity::class.java) },
            Module("🧭", "Geofence / Office", "Office radius and geofence configuration") { Intent(this, GeofenceManagerActivity::class.java) },
            Module("🔐", "Employee Permissions", "Per-employee access controls") { Intent(this, StaffPermissionActivity::class.java) },
            Module("🤖", "AI / Business Insights", "Application-only assistant") { Intent(this, AiChatActivity::class.java) }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }
        val toolbar = MaterialToolbar(this).apply {
            title = "Admin Modules • Web Mirror 📱"
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { finish() }
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, px(64)))

        val scroll = androidx.core.widget.NestedScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16),px(16),px(16),px(16))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        addHero(content)
        addSearch(content)
        addModules(content)
    }

    private lateinit var moduleContainer: LinearLayout
    private lateinit var searchInput: TextInputEditText

    private fun addHero(parent: LinearLayout) {
        val card = MaterialCardView(this).apply {
            radius = px(20).toFloat()
            setCardBackgroundColor(Color.parseColor("#18243A"))
            cardElevation = px(4).toFloat()
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(18),px(18),px(18),px(18)) }
        box.addView(TextView(this).apply {
            text = "✨ Native Web Clone"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = "Same SSOT • same rules • realtime across Web + Android"
            textSize = 14f
            setTextColor(Color.parseColor("#C9D5EA"))
            setPadding(0, px(6), 0, 0)
        })
        box.addView(TextView(this).apply {
            text = "No manual refresh • no reload • no logout/login for data convergence"
            textSize = 12f
            setTextColor(Color.parseColor("#7EE2B8"))
            setPadding(0, px(8), 0, 0)
        })
        card.addView(box)
        parent.addView(card, marginParams(bottom = 14))
    }

    private fun addSearch(parent: LinearLayout) {
        val til = TextInputLayout(this).apply {
            hint = "Search Admin screen 🔎"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        searchInput = TextInputEditText(this)
        til.addView(searchInput)
        parent.addView(til, marginParams(bottom = 14))
        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderModules(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
    }

    private fun addModules(parent: LinearLayout) {
        moduleContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(moduleContainer)
        renderModules("")
    }

    private fun renderModules(query: String) {
        if (!::moduleContainer.isInitialized) return
        moduleContainer.removeAllViews()
        val q = query.trim().lowercase()
        modules.filter { q.isBlank() || "${it.title} ${it.subtitle}".lowercase().contains(q) }
            .forEach { module ->
                val card = MaterialCardView(this).apply {
                    radius = px(18).toFloat()
                    strokeWidth = px(1)
                    strokeColor = Color.parseColor("#2D3A52")
                    setCardBackgroundColor(Color.parseColor("#111A2A"))
                    isClickable = true
                    isFocusable = true
                }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(px(14),px(14),px(14),px(14))
                }
                row.addView(TextView(this).apply {
                    text = module.icon
                    textSize = 28f
                    gravity = android.view.Gravity.CENTER
                }, LinearLayout.LayoutParams(px(48), px(48)))
                val textBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                textBox.addView(TextView(this).apply {
                    text = module.title
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                })
                textBox.addView(TextView(this).apply {
                    text = module.subtitle
                    textSize = 12f
                    setTextColor(Color.parseColor("#AEBBD0"))
                    setPadding(0, px(4), 0, 0)
                })
                row.addView(textBox, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(TextView(this).apply { text = "›"; textSize = 28f; setTextColor(Color.parseColor("#63A4FF")) })
                card.addView(row)
                card.setOnClickListener { startActivity(module.intentFactory()) }
                moduleContainer.addView(card, marginParams(bottom = 10))
            }
    }

    private fun marginParams(bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply { this.bottomMargin = px(bottom) }

    private fun px(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
