package com.biometric.app.ui

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.api.*
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.MobileSessionStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import java.text.NumberFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class AdminPayrollActivity : MotionBaseActivity() {
    @Inject lateinit var api: MobileApiService
    private lateinit var session: MobileSessionStore
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
        
        applyWindowInsets(findViewById(R.id.clAdminPayrollRoot), findViewById(R.id.appBar))
        
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
        findViewById<Button>(R.id.btnHistory).setOnClickListener {
            status.text = "History is live from Firebase / SSOT"
        }
        findViewById<Button>(R.id.btnFinalize).setOnClickListener { confirmFinalize() }
        observePayrollHistoryRealtime()
    }

    private fun observePayrollHistoryRealtime() {
        lifecycleScope.launch {
            val payrollFlow = firebaseSync.getDataFlow<RealtimePayrollRow>("payroll_history")
            val employeeFlow = firebaseSync.getDataFlow<Employee>("employees")
            kotlinx.coroutines.flow.combine(payrollFlow, employeeFlow) { payroll, employees ->
                val names = employees.associateBy { it.employeeId.toIntOrNull() ?: -1 }.mapValues { it.value.name }
                payroll.filter { it.payYear == (year.selectedItem as? Int ?: Calendar.getInstance().get(Calendar.YEAR)) &&
                        it.payMonth == month.selectedItemPosition + 1 } to names
            }.collectLatest { (rows, names) ->
                val mapped = rows.sortedBy { it.employeeId }.map { row ->
                    AdminPayrollHistoryRowDto(
                        payrollID = row.payrollId, employeeID = row.employeeId, employeeName = names[row.employeeId] ?: "Unknown",
                        payMonth = row.payMonth, payYear = row.payYear, baseSalary = row.baseSalary,
                        totalHoursWorked = row.totalHoursWorked, totalOvertimeMinutes = row.totalOvertimeMs / 60000.0,
                        totalPenaltyMinutes = row.totalPenaltyMs / 60000.0, deductionsHours = row.deductionsHours,
                        deductionsAdvance = row.deductionsAdvance, bonus = row.bonus, tdsDeduction = row.tdsDeduction,
                        totalShiftAllowance = row.totalShiftAllowance, basicComponent = row.basicComponent,
                        pfDeduction = row.pfDeduction, esiDeduction = row.esiDeduction, ptDeduction = row.ptDeduction,
                        absentDays = row.absentDays, manualLeaveDays = row.manualLeaveDays, netSalary = row.netSalary
                    )
                }
                renderHistory(mapped)
                status.text = "History • ${mapped.size} employees • realtime"
                findViewById<Button>(R.id.btnFinalize).isEnabled = false
            }
        }
    }



    private data class RealtimePayrollRow(
        var payrollId: Int = 0,
        var employeeId: Int = 0,
        var payMonth: Int = 0,
        var payYear: Int = 0,
        var baseSalary: Double = 0.0,
        var totalHoursWorked: Double = 0.0,
        var overtimePay: Double = 0.0,
        var deductionsHours: Double = 0.0,
        var deductionsAdvance: Double = 0.0,
        var bonus: Double = 0.0,
        var netSalary: Double = 0.0,
        var manualLeaveDays: Int = 0,
        var absentDays: Int = 0,
        var totalPenaltyMs: Long = 0L,
        var totalOvertimeMs: Long = 0L,
        var hourlyRate: Double = 0.0,
        var basicComponent: Double = 0.0,
        var pfDeduction: Double = 0.0,
        var esiDeduction: Double = 0.0,
        var employerPfContribution: Double = 0.0,
        var employerEsiContribution: Double = 0.0,
        var ptDeduction: Double = 0.0,
        var tdsDeduction: Double = 0.0,
        var totalShiftAllowance: Double = 0.0
    )

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

    private fun renderPreview(){
        var sum=0.0
        list.removeAllViews()
        preview.forEach { row ->
            sum+=row.netPayable
            val detailLine = StringBuilder()
            detailLine.append("Gross ${currency.format(row.earnedPay + row.overtimePay + row.bonus + row.totalShiftAllowance)}")
            if (row.pfDeduction > 0 || row.esiDeduction > 0 || row.ptDeduction > 0 || row.tdsDeduction > 0) {
                detailLine.append("  •  Ded ${currency.format(row.pfDeduction + row.esiDeduction + row.ptDeduction + row.tdsDeduction + row.advanceDeduction)}")
            }
            detailLine.append("  •  Net ${currency.format(row.netPayable)}")
            
            val metaLine = "⏱ ${"%.1f".format(row.earnedStandardHours)}h  •  OT ${"%.1f".format(row.overtimeMinutes/60)}h  •  Abs ${row.absentDays}"
            addRow("👤 ${row.employeeName}", detailLine.toString(), metaLine)
        }
        total.text="Total Net Payable  ${currency.format(sum)}"
    }
    
    private fun renderHistory(rows: List<AdminPayrollHistoryRowDto>){
        var sum=0.0
        list.removeAllViews()
        rows.forEach { row ->
            val net = row.netSalary ?: 0.0
            sum += net
            val detailLine = StringBuilder()
            detailLine.append("Net ${currency.format(net)}")
            detailLine.append("  •  Base ${currency.format(row.baseSalary ?: 0.0)}")
            if (row.deductionsAdvance > 0) detailLine.append("  •  Adv Ded ${currency.format(row.deductionsAdvance)}")
            
            val metaLine = "⏱ ${"%.1f".format(row.totalHoursWorked)}h  •  OT ${"%.1f".format(row.totalOvertimeMinutes/60)}h  •  Abs ${row.absentDays}"
            addRow("👤 ${row.employeeName}", detailLine.toString(), metaLine)
        }
        total.text="History Net  ${currency.format(sum)}"
    }
    private fun addRow(title:String, line:String, meta:String){ val v=layoutInflater.inflate(R.layout.item_admin_payroll_row,list,false); v.findViewById<TextView>(R.id.tvTitle).text=title; v.findViewById<TextView>(R.id.tvLine).text=line; v.findViewById<TextView>(R.id.tvMeta).text=meta; list.addView(v) }
    private fun confirmFinalize(){ if(preview.isEmpty()){toast("Generate a preview first.");return}; AlertDialog.Builder(this).setTitle("Finalize Payroll 💰").setMessage("Save ${preview.size} payroll entries? This follows the Web payroll finalization logic.").setNegativeButton("Cancel ❌",null).setPositiveButton("Finalize ✅"){_,_-> finalizePayroll()}.show() }
    private fun finalizePayroll()=lifecycleScope.launch { busy(true); status.text="Saving to database…"; try{ val(y,m)=period(); val r=api.adminPayrollFinalize(auth(),AdminPayrollFinalizeRequest(y,m,preview)); if(!r.isSuccessful||r.body()?.success!=true)throw Exception(r.body()?.message?:"Finalization failed"); toast("Payroll finalized successfully"); }catch(e:Exception){status.text="Finalization failed";toast(e.message?:"Request failed")}finally{busy(false)} }
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
}
