package com.biometric.app.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.biometric.app.R
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.ShopClosedDay
import com.biometric.app.databinding.ActivityShopClosedDaysBinding
import com.biometric.app.ui.adapter.MonthData
import com.biometric.app.ui.adapter.MonthGroupAdapter
import com.biometric.app.ui.viewmodel.MainViewModel
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.ui.viewmodel.StaffViewModel
import com.biometric.app.util.PremiumLoader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class ShopClosedDaysActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityShopClosedDaysBinding
    private val mainViewModel: MainViewModel by viewModels()
    private val viewModel: StaffViewModel by viewModels()
    @Inject lateinit var sharedViewModel: SharedViewModel
    
    private var shopId: String? = null
    private var employeesList: List<Employee> = emptyList()
    private var selectedYear: Int = Calendar.getInstance().get(Calendar.YEAR)
    private lateinit var monthGroupAdapter: MonthGroupAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShopClosedDaysBinding.inflate(layoutInflater)
        setContentView(binding.root)

        shopId = intent.getStringExtra("SHOP_ID")
        shopId?.let { viewModel.setShop(it) }
        val shopName = intent.getStringExtra("SHOP_NAME") ?: "Shop"
        
        setupToolbar(shopName)
        
        setupUI()
        observeViewModel()
    }

    private fun setupToolbar(shopName: String) {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        binding.toolbar.setOnClickListener {
            showShopSelectionDialog()
        }
        setupDualHeader(binding.toolbar, shopName, "Holiday Management")
    }

    private fun setupUI() {
        monthGroupAdapter = MonthGroupAdapter(emptyList()) { day ->
            showDeleteConfirmDialog(day)
        }
        binding.rvClosedDays.layoutManager = LinearLayoutManager(this)
        binding.rvClosedDays.adapter = monthGroupAdapter

        binding.fabAddClosedDay.setOnClickListener { showAddClosedDayDialog() }

        binding.btnPrevYear.setOnClickListener {
            selectedYear--
            refreshData()
        }

        binding.btnNextYear.setOnClickListener {
            selectedYear++
            refreshData()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(viewModel.isStaffLoading, viewModel.isHolidaysLoading) { staff, holidays ->
                        staff || holidays
                    }.collect { isLoading ->
                        if (isLoading) {
                            PremiumLoader.show(binding.brewingLoader, PremiumLoader.ScreenType.ATTENDANCE, lifecycleScope)
                            binding.contentLayout.visibility = View.GONE
                        } else {
                            PremiumLoader.hide(binding.brewingLoader)
                            binding.contentLayout.visibility = View.VISIBLE
                        }
                    }
                }
                launch {
                    sharedViewModel.selectedShop.collectLatest { shop ->
                        shop?.let {
                            shopId = it.shopId
                            setupDualHeader(binding.toolbar, it.name, "Holiday Management")
                            viewModel.setShop(it.shopId)
                            refreshData()
                        }
                    }
                }
                launch {
                    viewModel.employees.collectLatest { 
                        employeesList = it
                        monthGroupAdapter.updateEmployees(it)
                    }
                }
            }
        }
    }

    private fun refreshData() {
        val currentShopId = shopId ?: return
        binding.tvSelectedYear.text = selectedYear.toString()

        val startCal = Calendar.getInstance()
        startCal.set(Calendar.YEAR, selectedYear)
        startCal.set(Calendar.MONTH, Calendar.JANUARY)
        startCal.set(Calendar.DAY_OF_MONTH, 1)
        startCal.set(Calendar.HOUR_OF_DAY, 0); startCal.set(Calendar.MINUTE, 0); startCal.set(Calendar.SECOND, 0); startCal.set(Calendar.MILLISECOND, 0)
        val start = startCal.timeInMillis

        val endCal = Calendar.getInstance()
        endCal.set(Calendar.YEAR, selectedYear)
        endCal.set(Calendar.MONTH, Calendar.DECEMBER)
        endCal.set(Calendar.DAY_OF_MONTH, 31)
        endCal.set(Calendar.HOUR_OF_DAY, 23); endCal.set(Calendar.MINUTE, 59); endCal.set(Calendar.SECOND, 59); endCal.set(Calendar.MILLISECOND, 999)
        val end = endCal.timeInMillis

        lifecycleScope.launch {
            viewModel.getClosedDays(currentShopId, start, end).collectLatest { allDays ->
                // Loading flag fix: stop loading once results arrive
                // Note: in observeViewModel I need to make sure we don't hide loader too early if this is still pending

                val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                val currentMonth = Calendar.getInstance().get(Calendar.MONTH)

                val monthNames = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
                
                val monthLimit = if (selectedYear < currentYear) 11 else if (selectedYear == currentYear) currentMonth else -1
                
                val monthGroups = mutableListOf<MonthData>()
                for (i in 0..monthLimit) {
                    val cal = Calendar.getInstance()
                    cal.set(Calendar.YEAR, selectedYear)
                    cal.set(Calendar.MONTH, i)
                    
                    val monthHolidays = allDays.filter { day ->
                        val dayCal = Calendar.getInstance()
                        dayCal.timeInMillis = day.date
                        dayCal.get(Calendar.YEAR) == selectedYear && dayCal.get(Calendar.MONTH) == i
                    }.sortedBy { it.date }
                    
                    monthGroups.add(MonthData(monthNames[i], i, monthHolidays))
                }
                
                monthGroupAdapter.submitList(monthGroups)
            }
        }
    }

    private fun showShopSelectionDialog() {
        val shops = mainViewModel.allShops.value
        if (shops.isEmpty()) return

        val shopNames = shops.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.switch_shop)
            .setItems(shopNames) { _, which ->
                val selectedShop = shops[which]
                sharedViewModel.setSelectedShop(selectedShop)
            }
            .show()
    }

    private fun showDeleteConfirmDialog(day: ShopClosedDay) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_closed_day)
            .setMessage(R.string.delete_closed_day_msg)
            .setPositiveButton(R.string.delete) { _, _ ->
                 viewModel.deleteClosedDay(day)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        GlobalSwitcherDelegate.inflateMenu(menuInflater, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (GlobalSwitcherDelegate.handleOptionsItemSelected(this, item, sharedViewModel)) {
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showAddClosedDayDialog() {
        val currentShopId = shopId ?: return
        val view = layoutInflater.inflate(R.layout.dialog_add_closed_day, null)
        val etReason = view.findViewById<EditText>(R.id.etReason)
        val cbPaySalary = view.findViewById<CheckBox>(R.id.cbPaySalary)
        val etDate = view.findViewById<EditText>(R.id.etDate)
        val llStaffContainer = view.findViewById<LinearLayout>(R.id.llStaffContainer)
        
        val staffCheckBoxes = mutableListOf<Pair<String, CheckBox>>()
        employeesList.forEach { emp ->
            val cb = CheckBox(this).apply {
                text = emp.name
                isChecked = true
            }
            llStaffContainer.addView(cb)
            staffCheckBoxes.add(emp.employeeId to cb)
        }

        val selectedDate = Calendar.getInstance()
        val df = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        etDate.setText(df.format(selectedDate.time))

        etDate.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                selectedDate.set(y, m, d, 0, 0, 0)
                selectedDate.set(Calendar.MILLISECOND, 0)
                etDate.setText(df.format(selectedDate.time))
            }, selectedDate.get(Calendar.YEAR), selectedDate.get(Calendar.MONTH), selectedDate.get(Calendar.DAY_OF_MONTH)).show()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_closed_day)
            .setView(view)
            .setPositiveButton(R.string.add) { _, _ ->
                val reasonText = etReason.text.toString()
                val reason = if (reasonText.isBlank()) getString(R.string.unspecified_reason) else reasonText
                val affectedIds = staffCheckBoxes.filter { it.second.isChecked }.map { it.first }
                
                val day = ShopClosedDay(
                    id = UUID.randomUUID().toString(),
                    shopId = currentShopId,
                    date = selectedDate.timeInMillis,
                    reason = reason,
                    paySalary = cbPaySalary.isChecked,
                    affectedEmployeeIds = affectedIds
                )
                viewModel.addClosedDay(day)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
