package com.biometric.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.YearEndSummaryRecord
import com.biometric.app.sync.FirebaseSyncManager
import com.biometric.app.util.HapticUtil
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.text.NumberFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class YearEndSummaryActivity : MotionBaseActivity() {

    @Inject lateinit var sync: FirebaseSyncManager

    private lateinit var spTaxYear: Spinner
    private lateinit var btnSearch: MaterialButton
    private lateinit var btnExportCsv: MaterialButton
    private lateinit var tvStatus: TextView

    private lateinit var llKpiContainer: View
    private lateinit var tvKpiEmployees: TextView
    private lateinit var tvKpiGrossTaxable: TextView
    private lateinit var tvKpiTds: TextView
    private lateinit var tvKpiPf: TextView

    private lateinit var llEmptyState: View
    private lateinit var llRows: LinearLayout

    private var allSummaries: List<YearEndSummaryRecord> = emptyList()
    private var allEmployees: List<Employee> = emptyList()
    private var currentFilteredSummaries: List<YearEndSummaryRecord> = emptyList()

    private val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    private var selectedTaxYear: Int = if (Calendar.getInstance().get(Calendar.MONTH) < Calendar.APRIL) currentYear - 1 else currentYear
    private val currency = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_year_end_summary)

        applyWindowInsets(findViewById(R.id.clYearEndSummaryRoot), findViewById(R.id.appBar))

        initViews()
        setupTaxYearSpinner()
        setupListeners()
        observeYearEndDataRealtime()
    }

    private fun initViews() {
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener {
            HapticUtil.vibrateClick(it)
            finish()
        }

        spTaxYear = findViewById(R.id.spTaxYear)
        btnSearch = findViewById(R.id.btnSearch)
        btnExportCsv = findViewById(R.id.btnExportCsv)
        tvStatus = findViewById(R.id.tvStatus)

        llKpiContainer = findViewById(R.id.llKpiContainer)
        tvKpiEmployees = findViewById(R.id.tvKpiEmployees)
        tvKpiGrossTaxable = findViewById(R.id.tvKpiGrossTaxable)
        tvKpiTds = findViewById(R.id.tvKpiTds)
        tvKpiPf = findViewById(R.id.tvKpiPf)

        llEmptyState = findViewById(R.id.llEmptyState)
        llRows = findViewById(R.id.llRows)
    }

    private fun setupTaxYearSpinner() {
        // e.g., 2026 (2025-2026), 2025 (2024-2025), 2024 (2023-2024)
        val years = (currentYear downTo currentYear - 3).toList()
        val labels = years.map { "${it - 1} - $it" }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        spTaxYear.adapter = adapter

        val defaultIndex = years.indexOf(selectedTaxYear).coerceAtLeast(0)
        spTaxYear.setSelection(defaultIndex)

        spTaxYear.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedTaxYear = years[position]
                applyFilterAndRender()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupListeners() {
        btnSearch.setOnClickListener {
            HapticUtil.vibrateClick(it)
            applyFilterAndRender()
            Toast.makeText(this, "Year-End summary refreshed 🔄", Toast.LENGTH_SHORT).show()
        }

        btnExportCsv.setOnClickListener {
            HapticUtil.vibrateClick(it)
            exportSummaryToCsv()
        }
    }

    private fun observeYearEndDataRealtime() {
        lifecycleScope.launch {
            val summaryFlow = sync.getDataFlow<YearEndSummaryRecord>("year_end_summaries")
            val employeeFlow = sync.getDataFlow<Employee>("employees")

            combine(summaryFlow, employeeFlow) { summaries, employees ->
                summaries to employees
            }.collectLatest { (summaries, employees) ->
                allSummaries = summaries
                allEmployees = employees
                applyFilterAndRender()
            }
        }
    }

    private fun applyFilterAndRender() {
        currentFilteredSummaries = allSummaries.filter { it.taxYear == selectedTaxYear }
        val namesMap = allEmployees.associateBy { it.employeeId.toIntOrNull() ?: -1 }.mapValues { it.value.name }

        llRows.removeAllViews()
        val hasData = currentFilteredSummaries.isNotEmpty()
        llEmptyState.isVisible = !hasData
        llKpiContainer.isVisible = hasData

        if (!hasData) {
            tvStatus.text = "No consolidated year-end records for FY ${selectedTaxYear - 1} - $selectedTaxYear"
            return
        }

        var totalGrossTaxable = 0.0
        var totalTds = 0.0
        var totalPf = 0.0

        val inflater = LayoutInflater.from(this)

        currentFilteredSummaries.forEach { record ->
            totalGrossTaxable += record.grossTaxableSalary
            totalTds += record.totalTdsDeducted
            totalPf += record.totalPfContributionEmployee

            val card = inflater.inflate(R.layout.item_year_end_summary_row, llRows, false)

            val empName = namesMap[record.employeeId] ?: "Staff #${record.employeeId}"
            card.findViewById<TextView>(R.id.tvEmployeeName).text = empName
            card.findViewById<TextView>(R.id.tvEmployeeId).text = "EMP #${record.employeeId} • FY ${selectedTaxYear - 1}-$selectedTaxYear"
            card.findViewById<TextView>(R.id.tvGrossTaxable).text = currency.format(record.grossTaxableSalary)

            val chipTds = card.findViewById<TextView>(R.id.chipTds)
            chipTds.text = "🏷️ TDS: ${currency.format(record.totalTdsDeducted)}"
            chipTds.isVisible = record.totalTdsDeducted > 0

            val chipPf = card.findViewById<TextView>(R.id.chipPf)
            chipPf.text = "🛡️ PF (Emp): ${currency.format(record.totalPfContributionEmployee)}"
            chipPf.isVisible = record.totalPfContributionEmployee > 0

            val chipOt = card.findViewById<TextView>(R.id.chipOt)
            chipOt.text = "⏰ Annual OT: ${currency.format(record.totalAnnualOtPay)}"
            chipOt.isVisible = record.totalAnnualOtPay > 0

            val chipAbsent = card.findViewById<TextView>(R.id.chipAbsent)
            chipAbsent.text = "📅 Absent: ${record.totalAnnualAbsentDays} days"

            val chipState = card.findViewById<TextView>(R.id.chipState)
            chipState.text = if (record.state.isNotBlank()) "📍 ${record.state}" else "🏛️ Form-16"

            card.findViewById<MaterialButton>(R.id.btnViewForm16Details).setOnClickListener {
                HapticUtil.vibrateClick(it)
                showForm16DetailsDialog(empName, record)
            }

            llRows.addView(card)
        }

        tvKpiEmployees.text = currentFilteredSummaries.size.toString()
        tvKpiGrossTaxable.text = currency.format(totalGrossTaxable)
        tvKpiTds.text = currency.format(totalTds)
        tvKpiPf.text = currency.format(totalPf)
        tvStatus.text = "Showing ${currentFilteredSummaries.size} consolidated records for FY ${selectedTaxYear - 1} - $selectedTaxYear"
    }

    private fun showForm16DetailsDialog(employeeName: String, record: YearEndSummaryRecord) {
        val message = buildString {
            append("👤 Employee: ").append(employeeName).append(" (#${record.employeeId})\n")
            append("📅 Financial Year: ").append(selectedTaxYear - 1).append(" - ").append(selectedTaxYear).append("\n\n")
            append("💵 Gross Taxable Salary: ").append(currency.format(record.grossTaxableSalary)).append("\n")
            append("🏷️ Total TDS Withheld: ").append(currency.format(record.totalTdsDeducted)).append("\n")
            append("🛡️ Total PF (Employee): ").append(currency.format(record.totalPfContributionEmployee)).append("\n")
            append("⏰ Annual Overtime Pay: ").append(currency.format(record.totalAnnualOtPay)).append("\n")
            append("📅 Total Absent Days: ").append(record.totalAnnualAbsentDays).append(" days\n")
            if (record.state.isNotBlank()) {
                append("📍 State Jurisdiction: ").append(record.state).append("\n")
            }
            append("\n⚡ Calculation Authority: ").append(record.calculationSource.ifBlank { "Web Central Payroll" })
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("📄 Form-16 Compliance Basis")
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun exportSummaryToCsv() {
        if (currentFilteredSummaries.isEmpty()) {
            Toast.makeText(this, "No year-end data to export.", Toast.LENGTH_SHORT).show()
            return
        }

        val namesMap = allEmployees.associateBy { it.employeeId.toIntOrNull() ?: -1 }.mapValues { it.value.name }
        val csvBuilder = StringBuilder()
        csvBuilder.append("Employee ID,Employee Name,Tax Year,Gross Taxable Salary,Total TDS,Total PF Employee,Total Annual OT Pay,Total Absent Days,State\n")

        currentFilteredSummaries.forEach { record ->
            val empName = namesMap[record.employeeId] ?: "Staff #${record.employeeId}"
            csvBuilder.append("${record.employeeId},\"$empName\",${selectedTaxYear - 1}-$selectedTaxYear,${record.grossTaxableSalary},${record.totalTdsDeducted},${record.totalPfContributionEmployee},${record.totalAnnualOtPay},${record.totalAnnualAbsentDays},\"${record.state}\"\n")
        }

        try {
            val fileName = "YearEnd_Summary_${selectedTaxYear - 1}_$selectedTaxYear.csv"
            val file = File(cacheDir, fileName)
            val writer = FileWriter(file)
            writer.write(csvBuilder.toString())
            writer.flush()
            writer.close()

            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_SUBJECT, "Year-End Compliance Summary FY ${selectedTaxYear - 1}-$selectedTaxYear")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Year-End CSV 📊"))
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
