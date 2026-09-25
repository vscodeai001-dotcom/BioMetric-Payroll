package com.biometric.app.ui

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.biometric.app.R
import com.biometric.app.data.entity.Employee
import com.biometric.app.databinding.ActivityStaffBinding
import com.biometric.app.ui.adapter.StaffAdapter
import com.biometric.app.ui.viewmodel.MainViewModel
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.ui.viewmodel.StaffViewModel
import com.biometric.app.util.HapticUtil
import com.biometric.app.util.PremiumLoader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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

        setupUI()
        setupSearch()
        observeViewModel()
        setupSpeechRecognizer()

        setupMotionFeedback(
            binding.btnAddNewEmployee, binding.btnEmptyAddStaff,
            binding.fabAddStaff, binding.fabVoiceInput
        )
    }

    private fun updateToolbarTitle(shopName: String) {
        setupDualHeader(binding.toolbar, "Employee Directory 👥", "$shopName 🏪")
        binding.tvCurrentShopIndicator.text = "🏪 $shopName"
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
            onViewClick = { employee ->
                val intent = Intent(this, StaffDetailActivity::class.java).apply {
                    putExtra("EMPLOYEE_ID", employee.employeeId)
                    putExtra("SHOP_ID", viewModel.shopId.value)
                }
                startActivity(intent)
            },
            onEditClick = { employee ->
                AddStaffDialogFragment.newInstance(employee.employeeId)
                    .show(supportFragmentManager, AddStaffDialogFragment.TAG)
            },
            onDeleteClick = { employee ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("🗑️ Delete Employee: ${employee.name}")
                    .setMessage("Move ${employee.name} to Recycle Bin? Record will be hidden from directory but history is preserved for reporting.")
                    .setPositiveButton("Move to Recycle Bin") { _, _ ->
                        viewModel.deleteEmployee(employee)
                        HapticUtil.vibrateDeletion(binding.root)
                        Toast.makeText(this, "${employee.name} moved to Recycle Bin", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )

        binding.rvStaff.layoutManager = LinearLayoutManager(this)
        binding.rvStaff.adapter = adapter

        // Add employee triggers
        binding.btnAddNewEmployee.setOnClickListener {
            AddStaffDialogFragment.newInstance().show(supportFragmentManager, AddStaffDialogFragment.TAG)
        }
        binding.btnEmptyAddStaff.setOnClickListener {
            AddStaffDialogFragment.newInstance().show(supportFragmentManager, AddStaffDialogFragment.TAG)
        }
        binding.fabAddStaff.setOnClickListener {
            AddStaffDialogFragment.newInstance().show(supportFragmentManager, AddStaffDialogFragment.TAG)
        }
        binding.tvCurrentShopIndicator.setOnClickListener {
            showShopSelectionDialog()
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
            }
        )
    }

    private fun processVoiceCommand(command: String) {
        val lowerCaseCommand = command.lowercase(Locale.getDefault())
        val employee = employees.find { lowerCaseCommand.contains(it.name.lowercase(Locale.getDefault())) }
        if (employee != null) {
            viewModel.setSearchQuery(employee.name)
            binding.searchView.setQuery(employee.name, true)
        } else {
            HapticUtil.vibrateError(binding.root)
            Toast.makeText(this, "Could not find an employee with that name", Toast.LENGTH_SHORT).show()
        }
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
                    viewModel.employees.collect { list ->
                        employees = list
                        adapter.submitList(list)

                        // 1:1 Mirror Web team members count badge
                        binding.tvTeamMembersBadge.text = "• ${list.size} Team Members"

                        // Empty State toggle
                        if (list.isEmpty()) {
                            binding.llEmptyState.visibility = View.VISIBLE
                            binding.rvStaff.visibility = View.GONE
                        } else {
                            binding.llEmptyState.visibility = View.GONE
                            binding.rvStaff.visibility = View.VISIBLE
                        }
                    }
                }
            }
        }
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

    override fun onResume() {
        super.onResume()
        sharedViewModel.warmUpDashboard()
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
