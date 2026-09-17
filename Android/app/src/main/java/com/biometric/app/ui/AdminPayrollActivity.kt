package com.biometric.app.ui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.api.*
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.sync.AdminRealtimeCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import java.text.NumberFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class AdminPayrollActivity : AppCompatActivity() {
    @Inject lateinit var api: MobileApiService
    private lateinit var session: MobileSessionStore
    @Inject lateinit var realtimeCoordinator: AdminRealtimeCoordinator
    @Inject lateinit var firebaseSync: com.biometric.app.sync.FirebaseSyncManager
    private lateinit var month: Spinner
    private lateinit var year: Spinner
    private lateinit var status: TextView
    private lateinit var total: TextView
    private lateinit var list: LinearLayout
    private lateinit var progress: ProgressBar
    private var preview = emptyList<AdminPayrollRowDto>()
    private val currency = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_payroll)
        session = MobileSessionStore(this)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        month = findViewById(R.id.spMonth); year = findViewById(R.id.spYear); status = findViewById(R.id.tvStatus)
        total = findViewById(R.id.tvTotal); list = findViewById(R.id.llRows); progress = findViewById(R.id.progressBar)
        val now = Calendar.getInstance()
        month.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, (1..12).map { java.text.DateFormatSymbols().months[it-1] })
        month.setSelection(now.get(Calendar.MONTH))
        year.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, (now.get(Calendar.YEAR)-1..now.get(Calendar.YEAR)+1).toList())
        year.setSelection(1)
        findViewById<Button>(R.id.btnPreview).setOnClickListener { loadPreview() }
        findViewById<Button>(R.id.btnHistory).setOnClickListener { loadHistory() }
        findViewById<Button>(R.id.btnFinalize).setOnClickListener { confirmFinalize() }
        loadHistory()
        realtimeCoordinator.start { if (!isFinishing && !isDestroyed) loadHistory() }
    }

    override fun onDestroy() {
        realtimeCoordinator.stop()
        super.onDestroy()
    }

    private fun auth() = "Bearer ${session.token().orEmpty()}"
    private fun period() = Pair(year.selectedItem as Int, month.selectedItemPosition + 1)
    private fun busy(v: Boolean) { progress.isVisible = v; findViewById<Button>(R.id.btnPreview).isEnabled = !v; findViewById<Button>(R.id.btnHistory).isEnabled = !v }

    private fun loadPreview() = lifecycleScope.launch {
        busy(true); status.text = "Calculating payroll preview…"; list.removeAllViews(); preview = emptyList()
        try {
            val (y,m)=period(); val r=api.adminPayrollPreview(auth(), AdminPayrollPeriodRequest(y,m))
            if (!r.isSuccessful || r.body()?.success != true) throw Exception(r.body()?.message ?: "Unable to generate preview")
            preview=r.body()!!.rows; renderPreview(); status.text="Preview • ${preview.size} employees"; findViewById<Button>(R.id.btnFinalize).isEnabled=preview.isNotEmpty()
        } catch(e:Exception){ status.text="Payroll preview failed"; toast(e.message ?: "Request failed") } finally { busy(false) }
    }

    private fun loadHistory() = lifecycleScope.launch {
        busy(true); status.text="Loading payroll history…"; list.removeAllViews()
        try {
            val (y,m)=period()
            val ref = firebaseSync.getOwnerRef()?.child("payroll_history")
                ?: throw Exception("Firebase session is not initialized")
            val snapshot = ref.get().await()
            val employeeSnapshot = firebaseSync.getOwnerRef()?.child("employees")?.get()?.await()
            val names = employeeSnapshot?.children?.associate {
                val id = it.child("employeeId").value?.toString()?.toIntOrNull() ?: it.key?.toIntOrNull() ?: 0
                id to (it.child("name").value?.toString() ?: "Unknown")
            }.orEmpty()
            val rows = snapshot.children.mapNotNull { it.toAdminPayrollHistoryRow() }
                .filter { it.payYear == y && it.payMonth == m }
                .map { it.copy(employeeName = names[it.employeeID] ?: it.employeeName ?: "Unknown") }
                .sortedBy { it.employeeID }
            renderHistory(rows); status.text="History • ${rows.size} employees"; findViewById<Button>(R.id.btnFinalize).isEnabled=false
        } catch(e:Exception){ status.text="History load failed"; toast(e.message ?: "Request failed") } finally { busy(false) }
    }

    private fun DataSnapshot.toAdminPayrollHistoryRow(): AdminPayrollHistoryRowDto? {
        fun d(name: String): Double = child(name).value?.toString()?.toDoubleOrNull() ?: 0.0
        fun i(name: String): Int = child(name).value?.toString()?.toIntOrNull() ?: 0
        val employeeId = i("employeeId")
        if (employeeId <= 0) return null
        return AdminPayrollHistoryRowDto(
            payrollID = i("payrollId"), employeeID = employeeId, employeeName = child("employeeName").value?.toString(),
            payMonth = i("payMonth"), payYear = i("payYear"), baseSalary = d("baseSalary"),
            totalHoursWorked = d("totalHoursWorked"), totalOvertimeMinutes = d("totalOvertimeMs") / 60000.0,
            totalPenaltyMinutes = d("totalPenaltyMs") / 60000.0, deductionsHours = d("deductionsHours"),
            deductionsAdvance = d("deductionsAdvance"), bonus = d("bonus"), tdsDeduction = d("tdsDeduction"),
            totalShiftAllowance = d("totalShiftAllowance"), basicComponent = d("basicComponent"),
            pfDeduction = d("pfDeduction"), esiDeduction = d("esiDeduction"), ptDeduction = d("ptDeduction"),
            absentDays = i("absentDays"), manualLeaveDays = i("manualLeaveDays"), netSalary = d("netSalary")
        )
    }

    private fun renderPreview(){ var sum=0.0; list.removeAllViews(); preview.forEach { row -> sum+=row.netPayable; addRow("👤 ${row.employeeName}", "Gross ${currency.format(row.earnedPay+row.overtimePay+row.bonus+row.totalShiftAllowance)}  •  Net ${currency.format(row.netPayable)}", "⏱ ${"%.1f".format(row.earnedStandardHours)}h  •  OT ${"%.1f".format(row.overtimeMinutes/60)}h  •  Absent ${row.absentDays}") }; total.text="Total Net Payable  ${currency.format(sum)}" }
    private fun renderHistory(rows: List<AdminPayrollHistoryRowDto>){ var sum=0.0; rows.forEach { row -> sum+=row.netSalary ?: 0.0; addRow("👤 ${row.employeeName}", "Net ${currency.format(row.netSalary ?: 0.0)}  •  Base ${currency.format(row.baseSalary ?: 0.0)}", "⏱ ${"%.1f".format(row.totalHoursWorked)}h  •  OT ${"%.1f".format(row.totalOvertimeMinutes/60)}h  •  Absent ${row.absentDays}") }; total.text="History Net  ${currency.format(sum)}" }
    private fun addRow(title:String, line:String, meta:String){ val v=layoutInflater.inflate(R.layout.item_admin_payroll_row,list,false); v.findViewById<TextView>(R.id.tvTitle).text=title; v.findViewById<TextView>(R.id.tvLine).text=line; v.findViewById<TextView>(R.id.tvMeta).text=meta; list.addView(v) }
    private fun confirmFinalize(){ if(preview.isEmpty()){toast("Generate a preview first.");return}; AlertDialog.Builder(this).setTitle("Finalize Payroll").setMessage("Save ${preview.size} payroll entries? This follows the Web payroll finalization logic.").setNegativeButton("Cancel",null).setPositiveButton("Finalize"){_,_-> finalizePayroll()}.show() }
    private fun finalizePayroll()=lifecycleScope.launch { busy(true); status.text="Saving to database…"; try{ val(y,m)=period(); val r=api.adminPayrollFinalize(auth(),AdminPayrollFinalizeRequest(y,m,preview)); if(!r.isSuccessful||r.body()?.success!=true)throw Exception(r.body()?.message?:"Finalization failed"); toast("Payroll finalized successfully"); loadHistory() }catch(e:Exception){status.text="Finalization failed";toast(e.message?:"Request failed")}finally{busy(false)} }
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
}
