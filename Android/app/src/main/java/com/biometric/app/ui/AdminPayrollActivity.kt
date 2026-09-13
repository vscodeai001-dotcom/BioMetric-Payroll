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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class AdminPayrollActivity : AppCompatActivity() {
    @Inject lateinit var api: MobileApiService
    private lateinit var session: MobileSessionStore
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
            val (y,m)=period(); val r=api.adminPayrollHistory(auth(),y,m)
            if (!r.isSuccessful || r.body()?.success != true) throw Exception(r.body()?.message ?: "Unable to load history")
            val rows=r.body()!!.rows; renderHistory(rows); status.text="History • ${rows.size} employees"; findViewById<Button>(R.id.btnFinalize).isEnabled=false
        } catch(e:Exception){ status.text="History load failed"; toast(e.message ?: "Request failed") } finally { busy(false) }
    }

    private fun renderPreview(){ var sum=0.0; list.removeAllViews(); preview.forEach { row -> sum+=row.netPayable; addRow("👤 ${row.employeeName}", "Gross ${currency.format(row.earnedPay+row.overtimePay+row.bonus+row.totalShiftAllowance)}  •  Net ${currency.format(row.netPayable)}", "⏱ ${"%.1f".format(row.earnedStandardHours)}h  •  OT ${"%.1f".format(row.overtimeMinutes/60)}h  •  Absent ${row.absentDays}") }; total.text="Total Net Payable  ${currency.format(sum)}" }
    private fun renderHistory(rows: List<AdminPayrollHistoryRowDto>){ var sum=0.0; rows.forEach { row -> sum+=row.netSalary ?: 0.0; addRow("👤 ${row.employeeName}", "Net ${currency.format(row.netSalary ?: 0.0)}  •  Base ${currency.format(row.baseSalary ?: 0.0)}", "⏱ ${"%.1f".format(row.totalHoursWorked)}h  •  OT ${"%.1f".format(row.totalOvertimeMinutes/60)}h  •  Absent ${row.absentDays}") }; total.text="History Net  ${currency.format(sum)}" }
    private fun addRow(title:String, line:String, meta:String){ val v=layoutInflater.inflate(R.layout.item_admin_payroll_row,list,false); v.findViewById<TextView>(R.id.tvTitle).text=title; v.findViewById<TextView>(R.id.tvLine).text=line; v.findViewById<TextView>(R.id.tvMeta).text=meta; list.addView(v) }
    private fun confirmFinalize(){ if(preview.isEmpty()){toast("Generate a preview first.");return}; AlertDialog.Builder(this).setTitle("Finalize Payroll").setMessage("Save ${preview.size} payroll entries? This follows the Web payroll finalization logic.").setNegativeButton("Cancel",null).setPositiveButton("Finalize"){_,_-> finalizePayroll()}.show() }
    private fun finalizePayroll()=lifecycleScope.launch { busy(true); status.text="Saving to database…"; try{ val(y,m)=period(); val r=api.adminPayrollFinalize(auth(),AdminPayrollFinalizeRequest(y,m,preview)); if(!r.isSuccessful||r.body()?.success!=true)throw Exception(r.body()?.message?:"Finalization failed"); toast("Payroll finalized successfully"); loadHistory() }catch(e:Exception){status.text="Finalization failed";toast(e.message?:"Request failed")}finally{busy(false)} }
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
}
