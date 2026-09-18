package com.biometric.app.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.data.entity.AttendancePunch
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.LocalDailySummary
import com.biometric.app.sync.AdminRealtimeCoordinator
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.google.android.material.appbar.MaterialToolbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class AdminAttendanceActivity : MotionBaseActivity() {
    @Inject lateinit var sharedViewModel: SharedViewModel
    @Inject lateinit var realtimeCoordinator: AdminRealtimeCoordinator

    private lateinit var adapter: AttendanceAdapter
    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private var from = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    private var to = Calendar.getInstance()
    private lateinit var tvFrom: TextView
    private lateinit var tvTo: TextView
    private lateinit var progress: View
    private lateinit var empty: TextView
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_attendance)
        
        applyWindowInsets(findViewById(R.id.clAdminAttendanceRoot))
        
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        tvFrom = findViewById(R.id.tvFrom)
        tvTo = findViewById(R.id.tvTo)
        progress = findViewById(R.id.progress)
        empty = findViewById(R.id.empty)
        summary = findViewById(R.id.tvSummary)
        tvFrom.text = fmt.format(from.time)
        tvTo.text = fmt.format(to.time)
        tvFrom.setOnClickListener { pick(from) { from = it; tvFrom.text = fmt.format(from.time); render() } }
        tvTo.setOnClickListener { pick(to) { to = it; tvTo.text = fmt.format(to.time); render() } }
        findViewById<View>(R.id.btnGenerate).setOnClickListener { render() }
        adapter = AttendanceAdapter()
        findViewById<RecyclerView>(R.id.rvAttendance).layoutManager = LinearLayoutManager(this)
        findViewById<RecyclerView>(R.id.rvAttendance).adapter = adapter

        lifecycleScope.launch {
            sharedViewModel.allDailySummaries.collectLatest { render() }
        }
        lifecycleScope.launch {
            sharedViewModel.allEmployees.collectLatest { render() }
        }
        lifecycleScope.launch {
            sharedViewModel.allAttendancePunches.collectLatest { render() }
        }
        realtimeCoordinator.start {
            if (!isFinishing && !isDestroyed) {
                sharedViewModel.warmUpDashboard()
                render()
            }
        }
        sharedViewModel.warmUpDashboard()
        render()
    }

    override fun onDestroy() {
        realtimeCoordinator.stop()
        super.onDestroy()
    }

    private fun pick(base: Calendar, done: (Calendar) -> Unit) {
        DatePickerDialog(this, { _, y, m, d ->
            done(Calendar.getInstance().apply { set(y, m, d) })
        }, base.get(Calendar.YEAR), base.get(Calendar.MONTH), base.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun render() {
        if (!::adapter.isInitialized) return
        progress.isVisible = false
        val f = fmt.format(from.time)
        val t = fmt.format(to.time)
        val employees = sharedViewModel.allEmployees.value.associateBy { it.employeeId.toIntOrNull() ?: -1 }
        val summaries = sharedViewModel.allDailySummaries.value.filter { it.shiftDate >= f && it.shiftDate <= t }
        val punches = sharedViewModel.allAttendancePunches.value.groupBy { it.date }
        val rows = summaries.map { s ->
            val emp = employees[s.employeeId]
            AdminAttendanceRow(
                employeeID = s.employeeId,
                employeeName = emp?.name ?: "Employee #${s.employeeId}",
                date = s.shiftDate,
                status = s.status.ifBlank { "Absent" },
                workedHours = s.earnedStandardHours,
                overtimeMinutes = s.totalOvertimeMs / 60000.0,
                penaltyMinutes = s.totalPenaltyMs / 60000.0,
                latenessMinutes = s.totalLatenessMs / 60000.0,
                breakPenaltyMinutes = s.totalBreakPenaltyMs / 60000.0,
                scheduledMinutes = s.scheduledShiftDurationMs / 60000.0,
                punches = punches[s.shiftDate].orEmpty()
                    .filter { it.staffId == s.employeeId.toString() }
                    .sortedBy { it.timestamp }
                    .joinToString("  •  ") { p ->
                        val time = SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(p.timestamp))
                        "$time ${p.type}"
                    }
            )
        }.sortedWith(compareByDescending<AdminAttendanceRow> { it.date }.thenBy { it.employeeName })
        adapter.submit(rows)
        empty.isVisible = rows.isEmpty()

        val company = summaries
        val employeesProcessed = company.map { it.employeeId }.distinct().count()
        val scheduled = company.sumOf { it.scheduledShiftDurationMs } / 60000.0
        val worked = company.sumOf { it.earnedStandardHours }
        val ot = company.sumOf { it.totalOvertimeMs } / 60000.0
        val penalty = company.sumOf { it.totalPenaltyMs } / 60000.0
        val lateness = company.sumOf { it.totalLatenessMs } / 60000.0
        val breakPenalty = company.sumOf { it.totalBreakPenaltyMs } / 60000.0

        summary.text = if (company.isEmpty()) "" else
            "👥 $employeesProcessed employees  •  ⏱️ Worked ${String.format(Locale.US, "%.2f", worked)}h  •  📅 Sch ${String.format(Locale.US, "%.1f", scheduled / 60)}h\n" +
            "🚀 OT ${minutes(ot)}  •  ⚠️ Pen ${minutes(penalty)}  •  ⏰ Late ${minutes(lateness)}  •  🥪 Break ${minutes(breakPenalty)}"
    }

    private fun minutes(v: Double) = String.format(Locale.US, "%dh %02dm", (v / 60).toInt(), (v % 60).toInt())

    data class AdminAttendanceRow(
        val employeeID: Int, val employeeName: String, val date: String, val status: String,
        val workedHours: Double, val overtimeMinutes: Double, val penaltyMinutes: Double,
        val latenessMinutes: Double, val breakPenaltyMinutes: Double, val scheduledMinutes: Double,
        val punches: String
    )

    private class AttendanceAdapter : RecyclerView.Adapter<Holder>() {
        private val data = mutableListOf<AdminAttendanceRow>()
        fun submit(rows: List<AdminAttendanceRow>) { data.clear(); data.addAll(rows); notifyDataSetChanged() }
        override fun onCreateViewHolder(p: ViewGroup, v: Int) = Holder(LayoutInflater.from(p.context).inflate(R.layout.item_admin_attendance, p, false))
        override fun onBindViewHolder(h: Holder, i: Int) {
            val x = data[i]
            h.title.text = "${x.employeeName}  •  ${x.date}"
            h.status.text = x.status
            h.details.text = "Worked ${String.format(Locale.US, "%.2f", x.workedHours)}h  •  OT ${String.format(Locale.US, "%.0f", x.overtimeMinutes)}m  •  Penalty ${String.format(Locale.US, "%.0f", x.penaltyMinutes)}m\n${x.punches}"
        }
        override fun getItemCount() = data.size
    }

    private class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val title = v.findViewById<TextView>(R.id.tvTitle)
        val status = v.findViewById<TextView>(R.id.tvStatus)
        val details = v.findViewById<TextView>(R.id.tvDetails)
    }
}
