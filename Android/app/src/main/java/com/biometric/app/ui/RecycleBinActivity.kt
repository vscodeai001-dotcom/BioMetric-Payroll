package com.biometric.app.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.biometric.app.R
import com.biometric.app.data.entity.UserRole
import com.biometric.app.databinding.ActivityRecycleBinBinding
import com.biometric.app.ui.adapter.RecycleBinAdapter
import com.biometric.app.ui.viewmodel.RecycleBinViewModel
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.util.MotionManager
import com.biometric.app.util.PremiumLoader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import javax.inject.Inject

@ExperimentalCoroutinesApi
@AndroidEntryPoint
class RecycleBinActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityRecycleBinBinding
    private val viewModel: RecycleBinViewModel by viewModels()
    @Inject lateinit var sharedViewModel: SharedViewModel
    
    private lateinit var adapter: RecycleBinAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        
        binding = ActivityRecycleBinBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        applyWindowInsets(binding.clRecycleBinRoot, findViewById(R.id.appBar))

        val role = getSharedPreferences("auth_prefs", MODE_PRIVATE).getString("user_role", UserRole.Employee.name)
        if (role != UserRole.SuperAdmin.name) {
            Toast.makeText(this, "Recycle Bin is restricted to SuperAdmin 🛡️", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupUI()
        observeViewModel()
        
        setupDualHeader(binding.toolbar, "System Governance 🛡️", "Recycle Bin")
    }

    private fun setupUI() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupMotionFeedback(binding.btnLoadMore, binding.chipAll, binding.chipStaff, binding.chipAttendance, binding.chipShop)

        adapter = RecycleBinAdapter(
            onRestore = { item ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Restore Item?")
                    .setMessage("Are you sure you want to restore '${item.itemName}' to its previous module?")
                    .setPositiveButton("Restore") { _, _ ->
                        viewModel.restoreItem(item)
                        MotionManager.playUpdateMorph(binding.animFeedback)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
            onDeletePermanent = { item ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete Permanently?")
                    .setMessage("This action cannot be undone. '${item.itemName}' will be lost forever.")
                    .setPositiveButton("Delete Forever") { _, _ ->
                        viewModel.permanentlyDeleteItem(item)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            },
        )

        binding.rvRecycleBin.layoutManager = LinearLayoutManager(this)
        binding.rvRecycleBin.adapter = adapter

        binding.chipGroupFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when (checkedIds.firstOrNull()) {
                binding.chipStaff.id -> "STAFF"
                binding.chipAttendance.id -> "ATTENDANCE"
                binding.chipShop.id -> "SHOP"
                else -> null
            }
            viewModel.setFilter(filter)
        }

        binding.btnLoadMore.setOnClickListener {
            viewModel.loadMore()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isLoading.collect { isLoading ->
                        val isDataZero = viewModel.binItems.value.isEmpty()
                        if (isLoading) {
                            PremiumLoader.show(binding.brewingLoader, PremiumLoader.ScreenType.RECYCLE_BIN, lifecycleScope, immediate = true)
                            if (isDataZero) binding.contentLayout.visibility = View.GONE
                        } else {
                            PremiumLoader.hide(binding.brewingLoader)
                            binding.contentLayout.visibility = View.VISIBLE
                        }
                    }
                }
                launch {
                    viewModel.binItems.collect { items ->
                        adapter.submitList(items)
                        binding.emptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.canLoadMore.collect { canLoad ->
                        binding.btnLoadMore.visibility = if (canLoad) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.operationMessage.collect { message ->
                        Toast.makeText(this@RecycleBinActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
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
}
