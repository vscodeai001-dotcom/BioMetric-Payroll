package com.biometric.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.biometric.app.R
import com.biometric.app.ui.selfservice.ApplyLeaveActivity
import com.biometric.app.ui.selfservice.AttendanceLogsActivity
import com.biometric.app.ui.selfservice.BonusesActivity
import com.biometric.app.ui.selfservice.FbpDeclarationActivity
import com.biometric.app.ui.selfservice.MyLeavesActivity
import com.biometric.app.ui.selfservice.MyRegularizationsActivity
import com.biometric.app.ui.selfservice.MyReportsActivity
import com.biometric.app.ui.selfservice.PayslipListActivity
import com.biometric.app.ui.selfservice.ResignationActivity
import com.biometric.app.ui.selfservice.SalaryAdvancesActivity
import com.biometric.app.ui.selfservice.ShiftScheduleActivity
import com.biometric.app.ui.selfservice.TaxDeclarationActivity
import com.biometric.app.data.entity.FeatureSettings
import com.biometric.app.data.repository.FirebaseEmployeeSelfServiceRepository
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.google.android.material.card.MaterialCardView
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class EmployeeToolsActivity : MotionBaseActivity() {
    @Inject lateinit var selfService: FirebaseEmployeeSelfServiceRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_employee_tools)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        lifecycleScope.launch {
            val featureSettings = runCatching { selfService.featureSettings() }.getOrDefault(FeatureSettings())
            buildTools(featureSettings)
        }
    }

    private fun buildTools(features: FeatureSettings) {
        val container = findViewById<LinearLayout>(R.id.toolsContainer)
        container.removeAllViews()

        val grid = GridLayout(this).apply {
            columnCount = 2
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }

        data class Tool(val title: String, val subtitle: String, val icon: String, val action: () -> Unit)
        val tools = mutableListOf<Tool>()
        if (features.employeeCanViewAttendance) tools += Tool("Attendance", "Logs & history", "▣") { startActivity(Intent(this, AttendanceLogsActivity::class.java)) }
        if (features.enableLeaveManagement && features.employeeCanViewLeave) {
            tools += Tool("Apply Leave", "Request leave", "✦") { startActivity(Intent(this, ApplyLeaveActivity::class.java)) }
            if (features.employeeCanViewLeaveHistory) tools += Tool("Leave History", "Requests & balances", "☷") { startActivity(Intent(this, MyLeavesActivity::class.java)) }
        }
        if (features.enablePayroll && features.employeeCanViewPayslip) tools += Tool("Payslips", "Payroll history", "▤") { startActivity(Intent(this, PayslipListActivity::class.java)) }
        if (features.enableSalaryAdvance && features.employeeCanViewAdvance) tools += Tool("Advances", "Salary advances", "₹") { startActivity(Intent(this, SalaryAdvancesActivity::class.java)) }
        if (features.enableBonusManagement && features.employeeCanViewBonus) tools += Tool("Bonuses", "Bonus history", "◆") { startActivity(Intent(this, BonusesActivity::class.java)) }
        if (features.enablePunchCorrection && features.enableRegularizationRequest) tools += Tool("Correction", "Punch correction", "✎") { startActivity(Intent(this, MyRegularizationsActivity::class.java)) }
        if (features.enableResignationModule && features.employeeCanViewResignation) tools += Tool("Resignation", "Exit request", "↪") { startActivity(Intent(this, ResignationActivity::class.java)) }
        if (features.enableShiftScheduling && features.employeeCanViewShifts) tools += Tool("Shifts", "Roster", "◷") { startActivity(Intent(this, ShiftScheduleActivity::class.java)) }
        if (features.enableTaxDeclarations && features.employeeCanViewTax) tools += Tool("Tax Declaration", "IT declaration", "▣") { startActivity(Intent(this, TaxDeclarationActivity::class.java)) }
        if (features.enableFlexibleBenefits) tools += Tool("FBP Declaration", "Flexible benefits", "✦") { startActivity(Intent(this, FbpDeclarationActivity::class.java)) }
        tools += Tool("Profile", "My details", "●") { startActivity(Intent(this, MyReportsActivity::class.java).putExtra("FRAGMENT_TYPE", "profile")) }
        if (features.enableCustomReporting && features.employeeCanViewReports) tools += Tool("My Reports", "Personal reports", "▥") { startActivity(Intent(this, MyReportsActivity::class.java)) }
        tools += Tool("Offline GPS", "Map, queue & event log", "🛰") { startActivity(Intent(this, OfflineTrackingActivity::class.java)) }

        tools.forEach { tool ->
            val card = MaterialCardView(this).apply {
                radius = 22f
                cardElevation = 5f
                useCompatPadding = true
                isClickable = true
                setOnClickListener { tool.action() }
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(10, 16, 10, 16)
            }
            val icon = TextView(this).apply {
                text = tool.icon
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.colorPrimary))
            }
            val title = TextView(this).apply {
                text = tool.title
                textSize = 13f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
            }
            val subtitle = TextView(this).apply {
                text = tool.subtitle
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(com.google.android.material.color.MaterialColors.getColor(
                    this@EmployeeToolsActivity,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    R.color.text_secondary
                ))
            }
            box.addView(icon)
            box.addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 5 })
            box.addView(subtitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 2 })
            card.addView(box)
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(4, 4, 4, 4)
            }
            grid.addView(card, params)
        }
        container.addView(grid, LinearLayout.LayoutParams(-1, -2))
    }

}
