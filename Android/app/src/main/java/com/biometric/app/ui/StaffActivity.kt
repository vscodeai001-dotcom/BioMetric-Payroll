package com.biometric.app.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.biometric.app.R
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.EmployeeHistory
import com.biometric.app.databinding.ActivityStaffBinding
import com.biometric.app.databinding.DialogHikeUpdateBinding
import com.biometric.app.databinding.DialogShiftUpdateBinding
import com.biometric.app.ui.adapter.StaffAdapter
import com.biometric.app.ui.viewmodel.MainViewModel
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.ui.viewmodel.StaffViewModel
import com.biometric.app.util.PickerHelper
import com.biometric.app.util.PremiumLoader
import com.biometric.app.util.HapticUtil
import com.biometric.app.util.ImageUtils
import com.biometric.app.utils.PremiumUI
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class StaffActivity : MotionBaseActivity() {

    private var _binding: ActivityStaffBinding? = null
    private val binding get() = _binding!!
    
    private val mainViewModel: MainViewModel by viewModels()
    private val viewModel: StaffViewModel by viewModels()
    @Inject lateinit var sharedViewModel: SharedViewModel

    private lateinit var adapter: StaffAdapter
    private lateinit var speechRecognizer: SpeechRecognizer
    private var employees: List<Employee> = emptyList()

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
        if (isGranted) {
            startVoiceRecognition()
        } else {
            Toast.makeText(this, "Permission denied to record audio", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        
        _binding = ActivityStaffBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        applyWindowInsets(binding.clStaffRoot, binding.appBar)

        val intentShopId = intent.getStringExtra("SHOP_ID")
        val intentShopName = intent.getStringExtra("SHOP_NAME")
        intentShopId?.let {
            viewModel.setShop(it)
            intentShopName?.let { name -> updateToolbarTitle(name) }
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        binding.toolbar.setOnClickListener {
            showShopSelectionDialog()
        }

        binding.btnShowQrAttendance.setOnClickListener {
            val intent = Intent(this, QrAttendanceActivity::class.java).apply {
                putExtra("SHOP_ID", viewModel.shopId.value)
                putExtra("SHOP_NAME", binding.toolbar.title.toString().split("\n").firstOrNull())
            }
            startActivity(intent)
        }

        setupUI()
        setupSearch()
        observeViewModel()
        setupSpeechRecognizer()

        setupMotionFeedback(
            binding.btnPrevMonth, binding.btnNextMonth, binding.btnPrevDay, binding.btnNextDay,
            binding.fabAddStaff, binding.fabVoiceInput, binding.btnShowQrAttendance,
        )
    }

    private fun updateToolbarTitle(shopName: String) {
        setupDualHeader(binding.toolbar, "$shopName 🏪", "Staff Management 👥")
    }

    private fun setupSearch() {
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                viewModel.setSearchQuery(query ?: "")
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.setSearchQuery(newText ?: "")
                return true
            }
        })
    }

    private fun setupUI() {
        adapter = StaffAdapter(
            onAttendanceClick = { employee ->
                ManualAttendanceDialogFragment.newInstance(employee)
                    .show(supportFragmentManager, ManualAttendanceDialogFragment.TAG)
            },
            onAdvanceClick = { employee ->
                GiveAdvanceDialogFragment.newInstance(employee.employeeId)
                    .show(supportFragmentManager, GiveAdvanceDialogFragment.TAG)
            },
            onAdvanceLogsClick = { employee ->
                val intent = Intent(this, StaffDetailActivity::class.java).apply {
                    putExtra("EMPLOYEE_ID", employee.employeeId)
                    putExtra("SHOP_ID", viewModel.shopId.value)
                }
                startActivity(intent)
            },
            onSalaryClick = { employee ->
                PaySalaryDialogFragment.newInstance(employee.employeeId, employee.name)
                    .show(supportFragmentManager, PaySalaryDialogFragment.TAG)
            },
            onViewLogsClick = { employee ->
                val intent = Intent(this, StaffDetailActivity::class.java).apply {
                    putExtra("EMPLOYEE_ID", employee.employeeId)
                    putExtra("SHOP_ID", viewModel.shopId.value)
                }
                startActivity(intent)
            },
            onSalaryHikeClick = { employee ->
                showSalaryHikeDialog(employee)
            },
            onShiftUpdateClick = { employee ->
                showShiftUpdateDialog(employee)
            },
            onAllowanceHikeClick = { employee ->
                showAllowanceHikeDialog(employee)
            },
            onEditClick = { employee ->
                AddStaffDialogFragment.newInstance(employee.employeeId).show(supportFragmentManager, AddStaffDialogFragment.TAG)
            },
            onDeleteClick = { employee ->
                AlertDialog.Builder(this)
                    .setTitle("🗑️ Delete Staff?")
                    .setMessage("Are you sure you want to delete ${employee.name}? This action is permanent.")
                    .setPositiveButton("Delete 🚩") { _, _ ->
                        viewModel.deleteEmployee(employee)
                        HapticUtil.vibrateDeletion(binding.root)
                    }
                    .setNegativeButton("Cancel ✕", null)
                    .show()
            },
            onEditHistoryClick = { history ->
                showEditHistoryDialog(history)
            },
            onDeleteHistoryClick = { history ->
                AlertDialog.Builder(this)
                    .setTitle("🗑️ Delete Change Record?")
                    .setMessage("This will remove this record from history. This might affect salary calculations for the specified period.")
                    .setPositiveButton("Delete 🚩") { _, _ ->
                        viewModel.deleteEmployeeHistory(history)
                    }
                    .setNegativeButton("Cancel ✕", null)
                    .show()
            },
            onOverrideToggle = { employee, monthKey, type, isIncluded ->
                viewModel.updateMonthOverride(employee, monthKey, type, isIncluded)
            },
        ) { view, employee, viewsToHide, _ ->
            val shopName = sharedViewModel.selectedShop.value?.name ?: "Biometric Payroll"
            val monthStr = SimpleDateFormat("MMM_yyyy", Locale.getDefault()).format(viewModel.selectedCalendar.value.time)
            val fileName = "Staff_${employee.name.replace(" ", "_")}_$monthStr"
            ImageUtils.shareViewAsImage(view, fileName, viewsToHide, shopName)
        }
        binding.rvStaff.layoutManager = LinearLayoutManager(this)
        binding.rvStaff.adapter = adapter

        binding.fabAddStaff.setOnClickListener {
            AddStaffDialogFragment.newInstance().show(supportFragmentManager, AddStaffDialogFragment.TAG)
        }

        binding.fabVoiceInput.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startVoiceRecognition()
            } else {
                requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        binding.btnPrevMonth.setOnClickListener {
            val cal = viewModel.selectedCalendar.value.clone() as Calendar
            cal.add(Calendar.MONTH, -1)
            viewModel.setSelectedMonth(cal)
        }

        binding.btnNextMonth.setOnClickListener {
            val cal = viewModel.selectedCalendar.value.clone() as Calendar
            cal.add(Calendar.MONTH, 1)
            viewModel.setSelectedMonth(cal)
        }

        binding.btnPrevDay.setOnClickListener {
            val cal = viewModel.selectedDay.value.clone() as Calendar
            cal.add(Calendar.DAY_OF_YEAR, -1)
            
            val monthCal = viewModel.selectedCalendar.value
            if ((cal[Calendar.MONTH] != monthCal[Calendar.MONTH]) || (cal[Calendar.YEAR] != monthCal[Calendar.YEAR])) {
                return@setOnClickListener
            }
            viewModel.setSelectedDay(cal)
        }

        binding.btnNextDay.setOnClickListener {
            val cal = viewModel.selectedDay.value.clone() as Calendar
            cal.add(Calendar.DAY_OF_YEAR, 1)
            
            val monthCal = viewModel.selectedCalendar.value
            if ((cal[Calendar.MONTH] != monthCal[Calendar.MONTH]) || (cal[Calendar.YEAR] != monthCal[Calendar.YEAR])) {
                return@setOnClickListener
            }
            viewModel.setSelectedDay(cal)
        }

        binding.tvSelectedDay.setOnClickListener {
            showDatePicker()
        }
    }

    private fun showDatePicker() {
        val currentDay = viewModel.selectedDay.value
        val monthCal = viewModel.selectedCalendar.value
        
        PickerHelper.showSmartPicker(this, "Day", currentDay) { selectedCal ->
            if ((selectedCal[Calendar.MONTH] != monthCal[Calendar.MONTH]) || 
                (selectedCal[Calendar.YEAR] != monthCal[Calendar.YEAR])) {
                viewModel.setSelectedMonth(selectedCal.clone() as Calendar)
            }
            
            viewModel.setSelectedDay(selectedCal)
        }
    }

    private fun setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {}
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        processVoiceCommand(matches[0])
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            },
        )
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...")
        speechRecognizer.startListening(intent)
    }

    private fun processVoiceCommand(command: String) {
        val lowerCaseCommand = command.lowercase(Locale.getDefault())
        val employee = employees.find { lowerCaseCommand.contains(it.name.lowercase(Locale.getDefault())) }
        if (employee != null) {
            viewModel.markAttendance(employee, Date())
            PremiumUI.showSuccess(this, "Attendance Marked", "Logged for ${employee.name}")
        } else {
            HapticUtil.vibrateError(binding.root)
            Toast.makeText(this, "Could not find an employee with that name", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateTotalPayableSummary() {
        val base = viewModel.totalMonthlyBaseSalary.value
        val ot = viewModel.totalMonthlyEarnedOT.value
        val allowance = viewModel.totalMonthlyEarnedAllowance.value
        val upToDateTotal = viewModel.totalMonthlyStaffCost.value
        val projectedTotal = base + ot + allowance
        val daily = viewModel.totalDailyStaffCost.value

        binding.tvTotalUpToDate.text = String.format(Locale.getDefault(), "Earned: ₹%.0f", upToDateTotal)
        binding.tvSalaryBreakdown.text = String.format(
            Locale.getDefault(), 
            "Sal=%.0f | OT=%.0f | All=%.0f | Tot=%.0f", 
            base, ot, allowance, projectedTotal
        )
        binding.tvTotalDailySalaryValue.text = String.format(Locale.getDefault(), "₹%.0f", daily)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    sharedViewModel.selectedShop.collectLatest { shop ->
                        shop?.let {
                            viewModel.setShop(it.shopId)
                            updateToolbarTitle(it.name)
                        }
                    }
                }
                launch {
                    viewModel.isStaffLoading.collect { isLoading ->
                        // REQUIREMENT: Instant data display. 
                        // Only show technical loader if we have literally nothing to show.
                        val isDataZero = employees.isEmpty()
                        if (isLoading && isDataZero) {
                            PremiumLoader.show(binding.brewingLoader, PremiumLoader.ScreenType.STAFF, lifecycleScope, immediate = true)
                            binding.contentLayout.visibility = View.GONE
                        } else {
                            PremiumLoader.hide(binding.brewingLoader)
                            binding.contentLayout.visibility = View.VISIBLE
                            binding.contentLayout.alpha = 1f
                        }
                    }
                }
                launch {
                    viewModel.employees.collect {
                        employees = it
                        adapter.submitList(it)
                    }
                }
                launch {
                    viewModel.employeeHistory.collect { historyMap ->
                        adapter.updateHistory(historyMap)
                    }
                }
                launch {
                    viewModel.employeeStats.collect { stats ->
                        val cal = viewModel.selectedCalendar.value
                        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                        val monthKey = "${cal[Calendar.YEAR]}_${cal[Calendar.MONTH] + 1}"
                        adapter.updateStats(stats, days, monthKey)
                    }
                }
                launch {
                    viewModel.totalMonthlyStaffCost.collect {
                        updateTotalPayableSummary()
                    }
                }
                launch {
                    viewModel.totalMonthlyBaseSalary.collect {
                        updateTotalPayableSummary()
                    }
                }
                launch {
                    viewModel.totalMonthlyEarnedOT.collect {
                        updateTotalPayableSummary()
                    }
                }
                launch {
                    viewModel.totalMonthlyEarnedAllowance.collect {
                        updateTotalPayableSummary()
                    }
                }
                launch {
                    viewModel.totalFullMonthStaffCost.collect {
                        updateTotalPayableSummary()
                    }
                }
                launch {
                    viewModel.totalDailyStaffCost.collect {
                        _binding?.let { b ->
                            updateTotalPayableSummary()
                        }
                    }
                }
                launch {
                    viewModel.selectedCalendar.collect { cal ->
                        _binding?.let { b ->
                            val sdf = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                            b.tvSelectedMonth.text = sdf.format(cal.time)
                            
                            val dayCal = viewModel.selectedDay.value.clone() as Calendar
                            if ((dayCal[Calendar.MONTH] != cal[Calendar.MONTH]) || (dayCal[Calendar.YEAR] != cal[Calendar.YEAR])) {
                                val newDay = cal.clone() as Calendar
                                newDay[Calendar.DAY_OF_MONTH] = 1
                                viewModel.setSelectedDay(newDay)
                            }
                        }
                    }
                }
                launch {
                    viewModel.selectedDay.collect { cal ->
                        _binding?.let { b ->
                            val sdf = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())
                            val today = Calendar.getInstance()
                            val label = if ((cal[Calendar.DAY_OF_YEAR] == today[Calendar.DAY_OF_YEAR]) && 
                                           (cal[Calendar.YEAR] == today[Calendar.YEAR])) {
                                "Today, ${sdf.format(cal.time)}"
                            } else {
                                sdf.format(cal.time)
                            }
                            b.tvSelectedDay.text = label
                            updateTotalPayableSummary()
                        }
                    }
                }
            }
        }
    }

    private fun showSalaryHikeDialog(employee: Employee) {
        val dialogBinding = DialogHikeUpdateBinding.inflate(layoutInflater)
        dialogBinding.tvDialogTitle.text = getString(R.string.title_update_salary)
        dialogBinding.tvCurrentValue.text = getString(R.string.current_value_format, employee.salaryRate)
        dialogBinding.etNewValue.hint = getString(R.string.hint_new_salary_rate)
        
        val defaultCal = viewModel.selectedCalendar.value.clone() as Calendar
        defaultCal[Calendar.DAY_OF_MONTH] = 1
        defaultCal[Calendar.HOUR_OF_DAY] = 0; defaultCal[Calendar.MINUTE] = 0; defaultCal[Calendar.SECOND] = 0; defaultCal[Calendar.MILLISECOND] = 0
        
        var effectiveDateMillis = defaultCal.timeInMillis
        var endDateMillis: Long? = null
        val sdf = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())
        dialogBinding.etEffectiveDate.setText(sdf.format(Date(effectiveDateMillis)))
        dialogBinding.etEndDate.setText(getString(R.string.label_permanent))
        
        dialogBinding.etEffectiveDate.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = effectiveDateMillis }
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    cal.set(y, m, d)
                    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                    effectiveDateMillis = cal.timeInMillis
                    dialogBinding.etEffectiveDate.setText(sdf.format(cal.time))
                },
                cal[Calendar.YEAR],
                cal[Calendar.MONTH],
                cal[Calendar.DAY_OF_MONTH],
            ).show()
        }

        dialogBinding.etEndDate.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = endDateMillis ?: System.currentTimeMillis() }
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    cal.set(y, m, d)
                    cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
                    endDateMillis = cal.timeInMillis
                    dialogBinding.etEndDate.setText(sdf.format(cal.time))
                },
                cal[Calendar.YEAR],
                cal[Calendar.MONTH],
                cal[Calendar.DAY_OF_MONTH],
            ).apply {
                setButton(DatePickerDialog.BUTTON_NEUTRAL, getString(R.string.btn_permanent)) { _, _ ->
                    endDateMillis = null
                    dialogBinding.etEndDate.setText(getString(R.string.label_permanent))
                }
            }.show()
        }

        MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.btn_update_salary)) { _, _ ->
                val newValue = dialogBinding.etNewValue.text.toString().toDoubleOrNull()
                val reason = dialogBinding.etReason.text.toString()
                if (newValue != null && newValue > 0) {
                    viewModel.updateSalaryHike(employee, newValue, effectiveDateMillis, endDateMillis, reason)
                    HapticUtil.vibrateSuccess(binding.root)
                } else {
                    Toast.makeText(this, "Invalid salary amount", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showShiftUpdateDialog(employee: Employee) {
        val dialogBinding = DialogShiftUpdateBinding.inflate(layoutInflater)
        
        val currentStr = if (employee.weekendShiftStart != null) {
            "Current: %s-%s (Wkday) / %s-%s (Wknd)".format(employee.shiftStart, employee.shiftEnd, employee.weekendShiftStart, employee.weekendShiftEnd)
        } else {
            "Current: %s - %s (%.1fh Break)".format(employee.shiftStart, employee.shiftEnd, employee.breakHours)
        }
        dialogBinding.tvCurrentShift.text = currentStr
        
        dialogBinding.etShiftStart.setText(employee.shiftStart)
        dialogBinding.etShiftEnd.setText(employee.shiftEnd)
        dialogBinding.etBreakHours.setText(employee.breakHours.toString())

        dialogBinding.cbSplitShift.setOnCheckedChangeListener { _, isChecked ->
            dialogBinding.llSplitShiftContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        if (employee.shift2Start != null) {
            dialogBinding.cbSplitShift.isChecked = true
            dialogBinding.llSplitShiftContainer.visibility = View.VISIBLE
            dialogBinding.etShift2Start.setText(employee.shift2Start)
            dialogBinding.etShift2End.setText(employee.shift2End)
        }

        dialogBinding.cbCustomWeekendShift.setOnCheckedChangeListener { _, isChecked ->
            dialogBinding.llWeekendShiftContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        if (employee.weekendShiftStart != null) {
            dialogBinding.cbCustomWeekendShift.isChecked = true
            dialogBinding.llWeekendShiftContainer.visibility = View.VISIBLE
            dialogBinding.etWeekendShiftStart.setText(employee.weekendShiftStart)
            dialogBinding.etWeekendShiftEnd.setText(employee.weekendShiftEnd)
            dialogBinding.etWeekendBreakHours.setText(employee.weekendBreakHours?.toString() ?: "0.0")

            if (employee.weekendShift2Start != null) {
                dialogBinding.cbWeekendSplitShift.isChecked = true
                dialogBinding.llWeekendSplitShiftContainer.visibility = View.VISIBLE
                dialogBinding.etWeekendShift2Start.setText(employee.weekendShift2Start)
                dialogBinding.etWeekendShift2End.setText(employee.weekendShift2End)
            }
        }

        dialogBinding.cbWeekendSplitShift.setOnCheckedChangeListener { _, isChecked ->
            dialogBinding.llWeekendSplitShiftContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        
        dialogBinding.etShiftStart.setOnClickListener { showTimePicker(dialogBinding.etShiftStart) }
        dialogBinding.etShiftEnd.setOnClickListener { showTimePicker(dialogBinding.etShiftEnd) }
        dialogBinding.etShift2Start.setOnClickListener { showTimePicker(dialogBinding.etShift2Start) }
        dialogBinding.etShift2End.setOnClickListener { showTimePicker(dialogBinding.etShift2End) }

        dialogBinding.etWeekendShiftStart.setOnClickListener { showTimePicker(dialogBinding.etWeekendShiftStart) }
        dialogBinding.etWeekendShiftEnd.setOnClickListener { showTimePicker(dialogBinding.etWeekendShiftEnd) }
        dialogBinding.etWeekendShift2Start.setOnClickListener { showTimePicker(dialogBinding.etWeekendShift2Start) }
        dialogBinding.etWeekendShift2End.setOnClickListener { showTimePicker(dialogBinding.etWeekendShift2End) }

        val defaultCal = viewModel.selectedCalendar.value.clone() as Calendar
        defaultCal[Calendar.DAY_OF_MONTH] = 1
        defaultCal[Calendar.HOUR_OF_DAY] = 0; defaultCal[Calendar.MINUTE] = 0; defaultCal[Calendar.SECOND] = 0; defaultCal[Calendar.MILLISECOND] = 0
        
        var effectiveDateMillis = defaultCal.timeInMillis
        var endDateMillis: Long? = null
        val sdf = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())
        dialogBinding.etEffectiveDate.setText(sdf.format(Date(effectiveDateMillis)))
        dialogBinding.etEndDate.setText(getString(R.string.label_permanent))
        
        dialogBinding.etEffectiveDate.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = effectiveDateMillis }
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    cal.set(y, m, d)
                    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                    effectiveDateMillis = cal.timeInMillis
                    dialogBinding.etEffectiveDate.setText(sdf.format(cal.time))
                },
                cal[Calendar.YEAR],
                cal[Calendar.MONTH],
                cal[Calendar.DAY_OF_MONTH],
            ).show()
        }

        dialogBinding.etEndDate.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = endDateMillis ?: System.currentTimeMillis() }
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    cal.set(y, m, d)
                    cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
                    endDateMillis = cal.timeInMillis
                    dialogBinding.etEndDate.setText(sdf.format(cal.time))
                },
                cal[Calendar.YEAR],
                cal[Calendar.MONTH],
                cal[Calendar.DAY_OF_MONTH],
            ).apply {
                setButton(DatePickerDialog.BUTTON_NEUTRAL, getString(R.string.btn_permanent)) { _, _ ->
                    endDateMillis = null
                    dialogBinding.etEndDate.setText(getString(R.string.label_permanent))
                }
            }.show()
        }

        MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.btn_update_shift)) { _, _ ->
                val start = dialogBinding.etShiftStart.text.toString().trim()
                val end = dialogBinding.etShiftEnd.text.toString().trim()
                val breakHrs = dialogBinding.etBreakHours.text.toString().toDoubleOrNull() ?: 0.0
                val reason = dialogBinding.etReason.text.toString()

                val hasSplit = dialogBinding.cbSplitShift.isChecked
                val s2Start = if (hasSplit) dialogBinding.etShift2Start.text.toString().trim().ifEmpty { null } else null
                val s2End = if (hasSplit) dialogBinding.etShift2End.text.toString().trim().ifEmpty { null } else null

                val hasWknd = dialogBinding.cbCustomWeekendShift.isChecked
                val wStart = if (hasWknd) dialogBinding.etWeekendShiftStart.text.toString().trim().ifEmpty { null } else null
                val wEnd = if (hasWknd) dialogBinding.etWeekendShiftEnd.text.toString().trim().ifEmpty { null } else null
                val wBreak = if (hasWknd) dialogBinding.etWeekendBreakHours.text.toString().toDoubleOrNull() ?: 0.0 else null

                val hasWkndSplit = dialogBinding.cbWeekendSplitShift.isChecked
                val ws2Start = if (hasWkndSplit) dialogBinding.etWeekendShift2Start.text.toString().trim().ifEmpty { null } else null
                val ws2End = if (hasWkndSplit) dialogBinding.etWeekendShift2End.text.toString().trim().ifEmpty { null } else null
                
                if (start.isNotEmpty() && end.isNotEmpty()) {
                    viewModel.updateShiftTiming(employee, start, end, breakHrs, wStart, wEnd, wBreak, effectiveDateMillis, endDateMillis, reason, s2Start, s2End, ws2Start, ws2End)
                    HapticUtil.vibrateSuccess(binding.root)
                } else {
                    Toast.makeText(this, getString(R.string.error_shift_required), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showEditHistoryDialog(history: EmployeeHistory) {
        val dialogBinding = DialogHikeUpdateBinding.inflate(layoutInflater)
        dialogBinding.tvDialogTitle.text = "Edit Change Record"
        dialogBinding.tvCurrentValue.text = "Prev Value: ₹${history.oldValue}"
        dialogBinding.etNewValue.setText(history.newValue.toString())
        dialogBinding.etReason.setText(history.changeReason)
        
        val sdf = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())
        var effectiveDateMillis = history.effectiveDate
        var endDateMillis = history.endDate
        
        dialogBinding.etEffectiveDate.setText(sdf.format(Date(effectiveDateMillis)))
        dialogBinding.etEndDate.setText(endDateMillis?.let { sdf.format(Date(it)) } ?: getString(R.string.label_permanent))

        // Logic for Salary/Allowance/Shift specific UI (Reusing Hike UI for simple types)
        if (history.type == "SHIFT") {
            dialogBinding.etNewValue.visibility = View.GONE
            dialogBinding.tvCurrentValue.text = "Shift Record: ${history.shiftStart} - ${history.shiftEnd}"
        }

        dialogBinding.etEffectiveDate.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = effectiveDateMillis }
            DatePickerDialog(this, { _, y, m, d ->
                cal.set(y, m, d); cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                effectiveDateMillis = cal.timeInMillis
                dialogBinding.etEffectiveDate.setText(sdf.format(cal.time))
            }, cal[Calendar.YEAR], cal[Calendar.MONTH], cal[Calendar.DAY_OF_MONTH]).show()
        }

        dialogBinding.etEndDate.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = endDateMillis ?: System.currentTimeMillis() }
            DatePickerDialog(this, { _, y, m, d ->
                cal.set(y, m, d); cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
                endDateMillis = cal.timeInMillis
                dialogBinding.etEndDate.setText(sdf.format(cal.time))
            }, cal[Calendar.YEAR], cal[Calendar.MONTH], cal[Calendar.DAY_OF_MONTH]).apply {
                setButton(DatePickerDialog.BUTTON_NEUTRAL, getString(R.string.btn_permanent)) { _, _ ->
                    endDateMillis = null
                    dialogBinding.etEndDate.setText(getString(R.string.label_permanent))
                }
            }.show()
        }

        MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Save Changes") { _, _ ->
                val newValue = dialogBinding.etNewValue.text.toString().toDoubleOrNull() ?: 0.0
                val reason = dialogBinding.etReason.text.toString()
                
                val updated = history.copy(
                    newValue = if (history.type == "SHIFT") history.newValue else newValue,
                    effectiveDate = effectiveDateMillis,
                    endDate = endDateMillis,
                    changeReason = reason
                )
                viewModel.updateEmployeeHistory(updated)
                HapticUtil.vibrateSuccess(binding.root)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showTimePicker(editText: EditText) {
        val current = editText.text.toString()
        val parts = current.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 10
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0

        TimePickerDialog(this, { _, hourOfDay, minute ->
            editText.setText(String.format(Locale.getDefault(), "%02d:%02d", hourOfDay, minute))
        }, h, m, true).show()
    }

    private fun showAllowanceHikeDialog(employee: Employee) {
        val dialogBinding = DialogHikeUpdateBinding.inflate(layoutInflater)
        dialogBinding.tvDialogTitle.text = getString(R.string.title_update_allowance)
        dialogBinding.tvCurrentValue.text = getString(R.string.current_value_format, employee.dailyAllowance)
        dialogBinding.etNewValue.hint = getString(R.string.hint_new_daily_allowance)
        
        val defaultCal = viewModel.selectedCalendar.value.clone() as Calendar
        defaultCal[Calendar.DAY_OF_MONTH] = 1
        defaultCal[Calendar.HOUR_OF_DAY] = 0; defaultCal[Calendar.MINUTE] = 0; defaultCal[Calendar.SECOND] = 0; defaultCal[Calendar.MILLISECOND] = 0
        
        var effectiveDateMillis = defaultCal.timeInMillis
        var endDateMillis: Long? = null
        val sdf = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())
        dialogBinding.etEffectiveDate.setText(sdf.format(Date(effectiveDateMillis)))
        dialogBinding.etEndDate.setText(getString(R.string.label_permanent))
        
        dialogBinding.etEffectiveDate.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = effectiveDateMillis }
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    cal.set(y, m, d)
                    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                    effectiveDateMillis = cal.timeInMillis
                    dialogBinding.etEffectiveDate.setText(sdf.format(cal.time))
                },
                cal[Calendar.YEAR],
                cal[Calendar.MONTH],
                cal[Calendar.DAY_OF_MONTH],
            ).show()
        }

        dialogBinding.etEndDate.setOnClickListener {
            val cal = Calendar.getInstance().apply { timeInMillis = endDateMillis ?: System.currentTimeMillis() }
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    cal.set(y, m, d)
                    cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
                    endDateMillis = cal.timeInMillis
                    dialogBinding.etEndDate.setText(sdf.format(cal.time))
                },
                cal[Calendar.YEAR],
                cal[Calendar.MONTH],
                cal[Calendar.DAY_OF_MONTH],
            ).apply {
                setButton(DatePickerDialog.BUTTON_NEUTRAL, getString(R.string.btn_permanent)) { _, _ ->
                    endDateMillis = null
                    dialogBinding.etEndDate.setText(getString(R.string.label_permanent))
                }
            }.show()
        }

        MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.btn_update_allowance)) { _, _ ->
                val newValue = dialogBinding.etNewValue.text.toString().toDoubleOrNull()
                val reason = dialogBinding.etReason.text.toString()
                if (newValue != null) {
                    viewModel.updateAllowanceHike(employee, newValue, effectiveDateMillis, endDateMillis, reason)
                    HapticUtil.vibrateSuccess(binding.root)
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showShopSelectionDialog() {
        val shops = mainViewModel.allShops.value
        if (shops.isEmpty()) return

        val shopNames = shops.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Switch Shop")
            .setItems(shopNames) { _, which ->
                val selectedShop = shops[which]
                sharedViewModel.setSelectedShop(selectedShop)
            }
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        val extraActions = listOf(
            GlobalSwitcherDelegate.ActionItem("👤", "Add Staff") {
                AddStaffDialogFragment.newInstance().show(supportFragmentManager, AddStaffDialogFragment.TAG)
            }
        )
        GlobalSwitcherDelegate.inflateMenu(menuInflater, menu, extraActions = extraActions, activity = this)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val extraActions = listOf(
            GlobalSwitcherDelegate.ActionItem("👤", "Add Staff") {
                AddStaffDialogFragment.newInstance().show(supportFragmentManager, AddStaffDialogFragment.TAG)
            }
        )
        if (GlobalSwitcherDelegate.handleOptionsItemSelected(this, item, sharedViewModel, extraActions)) {
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
        _binding = null
    }
}
