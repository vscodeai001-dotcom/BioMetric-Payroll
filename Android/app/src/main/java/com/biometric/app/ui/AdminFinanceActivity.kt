package com.biometric.app.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.api.*
import com.biometric.app.data.MainRepository
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.databinding.ActivityAdminFinanceBinding
import com.biometric.app.databinding.DialogAdminMoneyEntryBinding
import com.biometric.app.sync.AdminRealtimeCoordinator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class AdminFinanceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminFinanceBinding
    
    @Inject lateinit var repository: MainRepository
    @Inject lateinit var mobileApi: MobileApiService
    @Inject lateinit var sessionStore: MobileSessionStore
    @Inject lateinit var realtimeCoordinator: AdminRealtimeCoordinator
    
    private val advances = mutableListOf<MoneyEntryDto>()
    private val bonuses = mutableListOf<MoneyEntryDto>()
    private val taxDeclarations = mutableListOf<TaxDeclarationDto>()
    
    private var currentTab = 0 // 0: Advances, 1: Bonuses, 2: Tax
    private val currencyFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminFinanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupTabs()
        setupRecyclerView()
        loadFinanceData()

        binding.fabAddEntry.setOnClickListener { showAddEntryDialog() }

        realtimeCoordinator.start {
            if (!isFinishing && !isDestroyed) loadFinanceData()
        }
    }

    override fun onDestroy() {
        realtimeCoordinator.stop()
        super.onDestroy()
    }

    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Advances 💳"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Bonuses 🌟"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Tax 🏛️"))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = tab?.position ?: 0
                binding.fabAddEntry.visibility = if (currentTab == 2) View.GONE else View.VISIBLE
                updateList()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupRecyclerView() {
        binding.rvFinanceList.layoutManager = LinearLayoutManager(this)
        binding.rvFinanceList.adapter = FinanceAdapter()
    }

    private fun loadFinanceData() {
        val token = "Bearer ${sessionStore.token()}"
        lifecycleScope.launch {
            try {
                val advRes = mobileApi.adminAdvances(token, false)
                if (advRes.isSuccessful) {
                    advances.clear()
                    advances.addAll(advRes.body() ?: emptyList())
                }

                val bonRes = mobileApi.adminBonuses(token)
                if (bonRes.isSuccessful) {
                    bonuses.clear()
                    bonuses.addAll(bonRes.body() ?: emptyList())
                }

                val year = Calendar.getInstance().get(Calendar.YEAR)
                val taxRes = mobileApi.adminTaxDeclarations(token, year)
                if (taxRes.isSuccessful) {
                    taxDeclarations.clear()
                    taxDeclarations.addAll(taxRes.body() ?: emptyList())
                }

                updateList()
            } catch (e: Exception) {
                Log.e("AdminFinance", "Load failed", e)
            }
        }
    }

    private fun updateList() {
        binding.rvFinanceList.adapter?.notifyDataSetChanged()
    }

    private fun showAddEntryDialog() {
        val dialogBinding = DialogAdminMoneyEntryBinding.inflate(layoutInflater)
        dialogBinding.tvTitle.text = if (currentTab == 0) "Record Salary Advance 💳" else "Record Performance Bonus 🌟"
        
        val employees = repository.allEmployeesFlow.value
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, employees.map { it.name })
        dialogBinding.actvEmployee.setAdapter(adapter)

        val selectedDate = Calendar.getInstance()
        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        dialogBinding.btnDate.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                selectedDate.set(y, m, d)
                dialogBinding.btnDate.text = "Entry Date: ${sdf.format(selectedDate.time)}"
            }, selectedDate.get(Calendar.YEAR), selectedDate.get(Calendar.MONTH), selectedDate.get(Calendar.DAY_OF_MONTH)).show()
        }

        MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Save Entry") { _, _ ->
                val empIdx = employees.indexOfFirst { it.name == dialogBinding.actvEmployee.text.toString() }
                if (empIdx == -1) return@setPositiveButton
                
                val empId = employees[empIdx].employeeId.toIntOrNull() ?: 0
                val amount = dialogBinding.etAmount.text.toString().toDoubleOrNull() ?: 0.0
                val desc = dialogBinding.etDescription.text.toString()
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(selectedDate.time)

                if (currentTab == 0) {
                    submitAdvance(empId, amount, desc, dateStr)
                } else {
                    submitBonus(empId, amount, desc, dateStr)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun submitAdvance(empId: Int, amount: Double, type: String, date: String) {
        lifecycleScope.launch {
            try {
                val token = "Bearer ${sessionStore.token()}"
                val response = mobileApi.createAdminAdvance(token, AdminAdvanceRequest(empId, amount, type, date))
                if (response.isSuccessful) {
                    Toast.makeText(this@AdminFinanceActivity, "Advance recorded ✅", Toast.LENGTH_SHORT).show()
                    loadFinanceData()
                }
            } catch (e: Exception) {
                Toast.makeText(this@AdminFinanceActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun submitBonus(empId: Int, amount: Double, desc: String, date: String) {
        lifecycleScope.launch {
            try {
                val token = "Bearer ${sessionStore.token()}"
                val response = mobileApi.createAdminBonus(token, AdminBonusRequest(empId, amount, desc, date))
                if (response.isSuccessful) {
                    Toast.makeText(this@AdminFinanceActivity, "Bonus recorded ✅", Toast.LENGTH_SHORT).show()
                    loadFinanceData()
                }
            } catch (e: Exception) {
                Toast.makeText(this@AdminFinanceActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleTaxAction(tax: TaxDeclarationDto) {
        val remarks = EditText(this).apply { hint = "Admin Remarks" }
        MaterialAlertDialogBuilder(this)
            .setTitle("Review Tax Declaration")
            .setMessage("Employee: ${tax.employeeId}\nYear: ${tax.financialYear}\nAmount: ${currencyFormat.format(tax.totalInvestmentAmount)}")
            .setView(remarks)
            .setPositiveButton("Approve") { _, _ -> updateTaxStatus(tax.declarationId, true, remarks.text.toString()) }
            .setNegativeButton("Reject") { _, _ -> updateTaxStatus(tax.declarationId, false, remarks.text.toString()) }
            .show()
    }

    private fun updateTaxStatus(id: Int, approve: Boolean, remarks: String) {
        lifecycleScope.launch {
            try {
                val token = "Bearer ${sessionStore.token()}"
                val req = AdminRemarkRequest(remarks)
                val res = if (approve) mobileApi.approveAdminTax(token, id, req) else mobileApi.rejectAdminTax(token, id, req)
                if (res.isSuccessful) {
                    Toast.makeText(this@AdminFinanceActivity, "Tax status updated", Toast.LENGTH_SHORT).show()
                    loadFinanceData()
                }
            } catch (e: Exception) {
                Toast.makeText(this@AdminFinanceActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    inner class FinanceAdapter : RecyclerView.Adapter<FinanceAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val tvIcon = holder.itemView.findViewById<TextView>(R.id.tvHistoryIcon)
            val tvTitle = holder.itemView.findViewById<TextView>(R.id.tvHistoryTitle)
            val tvDate = holder.itemView.findViewById<TextView>(R.id.tvHistoryDate)
            val tvReason = holder.itemView.findViewById<TextView>(R.id.tvHistoryReason)

            when (currentTab) {
                0 -> { // Advances
                    val item = advances[position]
                    tvIcon.text = "💳"
                    tvTitle.text = "Staff ID: ${item.employeeId} - ${item.type}"
                    tvDate.text = item.date
                    tvReason.text = currencyFormat.format(item.amount)
                    tvReason.visibility = View.VISIBLE
                    tvReason.setTextColor(ContextCompat.getColor(this@AdminFinanceActivity, R.color.red_700))
                }
                1 -> { // Bonuses
                    val item = bonuses[position]
                    tvIcon.text = "🌟"
                    tvTitle.text = "Staff ID: ${item.employeeId} - ${item.type}"
                    tvDate.text = item.date
                    tvReason.text = currencyFormat.format(item.amount)
                    tvReason.visibility = View.VISIBLE
                    tvReason.setTextColor(ContextCompat.getColor(this@AdminFinanceActivity, R.color.green_700))
                }
                2 -> { // Tax
                    val item = taxDeclarations[position]
                    tvIcon.text = "🏛️"
                    tvTitle.text = "Staff ID: ${item.employeeId} - Tax Decl."
                    tvDate.text = "FY ${item.financialYear}"
                    tvReason.text = "${item.status}: ${currencyFormat.format(item.totalInvestmentAmount)}"
                    tvReason.visibility = View.VISIBLE
                    holder.itemView.setOnClickListener { handleTaxAction(item) }
                }
            }
        }

        override fun getItemCount() = when (currentTab) {
            0 -> advances.size
            1 -> bonuses.size
            2 -> taxDeclarations.size
            else -> 0
        }

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v)
    }
}
