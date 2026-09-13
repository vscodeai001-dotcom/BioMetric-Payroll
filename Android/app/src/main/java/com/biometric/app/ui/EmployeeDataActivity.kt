package com.biometric.app.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.api.*
import com.biometric.app.data.MobileSessionStore
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class EmployeeDataActivity : AppCompatActivity() {
    @Inject lateinit var api: MobileApiService
    @Inject lateinit var session: MobileSessionStore
    private lateinit var status: TextView
    private lateinit var content: LinearLayout
    private val currency = NumberFormat.getCurrencyInstance(Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_employee_data)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        status = findViewById(R.id.tvStatus); content = findViewById(R.id.contentContainer)
        load(intent.getStringExtra(EXTRA_SCREEN) ?: "attendance")
    }

    private fun auth() = session.token()?.let { "Bearer $it" }
    private fun load(key: String) {
        findViewById<MaterialToolbar>(R.id.toolbar).title = titleFor(key)
        val token = auth(); if (token == null) { finish(); return }
        status.text = "Loading…"
        lifecycleScope.launch {
            runCatching {
                when (key) {
                    "attendance" -> showAttendance(api.attendance(token, LocalDate.now().withDayOfMonth(1).toString(), LocalDate.now().toString()).body().orEmpty())
                    "payslips" -> showPayslips(api.payslips(token).body().orEmpty())
                    "leaves" -> showLeaves(api.leaves(token).body().orEmpty())
                    "advances" -> showMoney("Salary Advances", api.advances(token).body().orEmpty())
                    "bonuses" -> showMoney("Bonuses", api.bonuses(token).body().orEmpty())
                    "regularizations" -> showRegularizations(api.regularizations(token).body().orEmpty())
                    "resignation" -> showResignation(api.resignation(token).body())
                    "tax" -> showTax(api.tax(token, financialYear()).body())
                    "fbp" -> showFbp(api.fbp(token, financialYear()).body().orEmpty())
                    "shifts" -> showShifts(api.shifts(token, YearMonth.now().toString()).body().orEmpty())
                }
            }.onFailure { status.text = "Unable to load data. ${it.message ?: "Please try again."}" }
        }
    }

    private fun titleFor(k: String) = mapOf("attendance" to "My Attendance", "payslips" to "My Payslips", "leaves" to "Leave", "advances" to "Salary Advances", "bonuses" to "My Bonuses", "regularizations" to "Regularization", "resignation" to "My Resignation", "tax" to "Tax Declaration", "fbp" to "FBP Declaration", "shifts" to "Shift Schedule")[k] ?: "Employee"
    private fun financialYear(): Int = if (LocalDate.now().monthValue >= 4) LocalDate.now().year else LocalDate.now().year - 1

    private fun clear() { content.removeAllViews(); status.text = "" }
    private fun addText(text: String, size: Float = 14f, bold: Boolean = false) { content.addView(TextView(this).apply { this.text = text; textSize = size; if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(4, 8, 4, 8) }) }
    private fun card(text: String) { val c = com.google.android.material.card.MaterialCardView(this).apply { radius = 18f; cardElevation = 3f }; c.addView(TextView(this).apply { this.text=text; textSize=14f; setPadding(18,16,18,16) }); content.addView(c, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=8 }) }

    private fun showAttendance(items: List<AttendanceDayDto>) { clear(); if (items.isEmpty()) { card("No attendance records for the selected period."); return }; items.forEach { d -> card("${d.date}  •  ${d.status}\nScheduled: ${d.scheduledHours} h   Worked: ${d.workedHours} h\nOT: ${d.overtime}   Penalty: ${d.penalty}\n" + d.punches.joinToString("  |  ") { p -> "${p.type} ${p.time}" }) } }
    private fun showPayslips(items: List<PayslipDto>) { clear(); if(items.isEmpty()){card("No payslips available.");return}; items.sortedByDescending { it.year*100+it.month }.forEach { p -> card("${monthName(p.month)} ${p.year}\nGross: ${currency.format(p.baseSalary)}   OT: ${currency.format(p.overtimePay)}   Bonus: ${currency.format(p.bonus)}\nDeductions: ${currency.format(p.advanceDeduction+p.pfDeduction+p.esiDeduction+p.ptDeduction+p.tdsDeduction)}\nNet Salary: ${currency.format(p.netSalary)}") } }
    private fun showLeaves(items: List<LeaveDto>) { clear(); addButton("Request Leave") { showLeaveForm() }; if(items.isEmpty()){card("No leave requests yet.");return}; items.sortedByDescending{it.date}.forEach{card("${it.date}  •  ${it.leaveType}${if(it.halfDay) " (Half Day)" else ""}\nStatus: ${if(it.approved) "Approved" else "Pending / Not Approved"}\n${it.notes.orEmpty()}")}}
    private fun showMoney(title:String, items:List<MoneyEntryDto>){clear(); if(items.isEmpty()){card("No $title records.");return};items.sortedByDescending{it.date}.forEach{card("${it.date}\nAmount: ${currency.format(it.amount)}\n${it.type}${if(it.paid) " • Paid" else " • Unpaid"}\n${it.description.orEmpty()}")}}
    private fun showRegularizations(items:List<RegularizationDto>){clear();addButton("Request Punch Correction"){showRegularizationForm()};if(items.isEmpty()){card("No regularization requests.");return};items.sortedByDescending{it.date}.forEach{card("${it.date} • ${if(it.inPunch) "IN" else "OUT"} ${it.punchTime}\nStatus: ${it.status}\nReason: ${it.reason}\n${it.remarks.orEmpty()}")}}
    private fun showResignation(item:ResignationDto?){clear();if(item==null){card("No resignation request found.");addButton("Submit Resignation"){showResignationForm()}}else{card("Submitted: ${item.submissionDate}\nLast Working Day: ${item.desiredLastWorkingDay}\nStatus: ${item.status}\n${item.adminRemarks.orEmpty()}")}}
    private fun showTax(item:TaxDeclarationDto?){clear();if(item!=null)card("FY ${item.financialYear}-${item.financialYear+1}\nRegime: ${item.regime}\n80C: ${currency.format(item.section80C)}\n80D: ${currency.format(item.section80D)}\nHRA Rent: ${currency.format(item.hraRentPaid)}\nOther: ${currency.format(item.otherExemptions)}\nStatus: ${item.status}");addButton(if(item==null) "Submit Tax Declaration" else "Update Tax Declaration"){showTaxForm(item)}}
    private fun showFbp(items:List<FbpDto>){clear();addButton("Add FBP Allocation"){showFbpForm()};if(items.isEmpty()){card("No FBP declarations for this financial year.");return};items.forEach{card("${it.componentName}\nAnnual: ${currency.format(it.annualAllocatedAmount)}   Monthly: ${currency.format(it.monthlyAllocatedAmount)}\nStatus: ${it.status}\n${it.adminRemarks.orEmpty()}")}}
    private fun showShifts(items:List<ShiftDto>){clear();if(items.isEmpty()){card("No specific shifts scheduled this month. Default shift may apply.");return};items.forEach{card("${it.date} • ${it.day}\n${it.startTime} - ${it.endTime}\n${it.status}")}}

    private fun addButton(label:String, click:()->Unit){content.addView(MaterialButton(this).apply{text=label;setOnClickListener{click()}},LinearLayout.LayoutParams(-1,56).apply{bottomMargin=10})}
    private fun edit(hint:String):TextInputEditText=TextInputEditText(this).apply{this.hint=hint;setPadding(16,12,16,12)}
    private fun input(parent:LinearLayout,hint:String):TextInputEditText{val box=TextInputLayout(this);box.hint=hint;val e=edit(hint);box.addView(e);parent.addView(box,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=10});return e}
    private fun showLeaveForm(){clear();val date=input(content,"Leave date (YYYY-MM-DD)");val type=input(content,"Leave type (Sick / Vacation)");val notes=input(content,"Notes");val half=CheckBox(this).apply{text="Half day"};content.addView(half);addButton("Submit Leave"){val t=auth()?:return@addButton;lifecycleScope.launch{val r=api.createLeave(t,LeaveCreateRequest(date.text.toString(),type.text.toString(),half.isChecked,notes.text?.toString()));if(r.isSuccessful){Toast.makeText(this@EmployeeDataActivity,"Leave submitted",Toast.LENGTH_SHORT).show();load("leaves")}else status.text="Unable to submit leave (${r.code()})"}}}
    private fun showRegularizationForm(){clear();val date=input(content,"Punch date (YYYY-MM-DD)");val time=input(content,"Correct time (HH:MM)");val reason=input(content,"Reason");val inPunch=RadioButton(this).apply{text="Correct IN punch";isChecked=true};val outPunch=RadioButton(this).apply{text="Correct OUT punch"};content.addView(inPunch);content.addView(outPunch);addButton("Submit Correction"){val t=auth()?:return@addButton;lifecycleScope.launch{val r=api.createRegularization(t,RegularizationCreateRequest(date.text.toString(),inPunch.isChecked,time.text.toString(),reason.text.toString()));if(r.isSuccessful){Toast.makeText(this@EmployeeDataActivity,"Correction submitted",Toast.LENGTH_SHORT).show();load("regularizations")}else status.text="Unable to submit (${r.code()})"}}}
    private fun showResignationForm(){clear();val date=input(content,"Desired last working day (YYYY-MM-DD)");val reason=input(content,"Reason");addButton("Submit Resignation"){val t=auth()?:return@addButton;lifecycleScope.launch{val r=api.createResignation(t,ResignationCreateRequest(date.text.toString(),reason.text.toString()));if(r.isSuccessful){Toast.makeText(this@EmployeeDataActivity,"Resignation submitted",Toast.LENGTH_SHORT).show();load("resignation")}else status.text="Unable to submit (${r.code()})"}}}
    private fun showTaxForm(existing:TaxDeclarationDto?){clear();val regime=input(content,"Regime (Old / New)");regime.setText(existing?.regime?:"New");val c=input(content,"Section 80C");c.setText(existing?.section80C?.toString() ?:  "0");val d=input(content,"Section 80D");d.setText(existing?.section80D?.toString() ?:  "0");val h=input(content,"HRA rent paid");h.setText(existing?.hraRentPaid?.toString() ?:  "0");val o=input(content,"Other exemptions");o.setText(existing?.otherExemptions?.toString() ?:  "0");addButton("Save Tax Declaration"){val t=auth()?:return@addButton;lifecycleScope.launch{val r=api.saveTax(t,TaxDeclarationRequest(financialYear(),regime.text.toString(),c.number(),d.number(),h.number(),o.number()));if(r.isSuccessful){Toast.makeText(this@EmployeeDataActivity,"Tax declaration saved",Toast.LENGTH_SHORT).show();load("tax")}else status.text="Unable to save (${r.code()})"}}}
    private fun showFbpForm(){clear();val name=input(content,"Component name");val annual=input(content,"Annual amount");addButton("Save FBP"){val t=auth()?:return@addButton;lifecycleScope.launch{val r=api.saveFbp(t,FbpRequest(financialYear(),name.text.toString(),annual.number()));if(r.isSuccessful){Toast.makeText(this@EmployeeDataActivity,"FBP saved",Toast.LENGTH_SHORT).show();load("fbp")}else status.text="Unable to save (${r.code()})"}}}
    private fun TextInputEditText.number()=text?.toString()?.toDoubleOrNull()?:0.0
    private fun monthName(m:Int)=java.time.Month.of(m).name.lowercase().replaceFirstChar{it.uppercase()}
    companion object{const val EXTRA_SCREEN="employee_screen"}
}
