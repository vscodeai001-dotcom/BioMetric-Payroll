package com.biometric.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.CombinedLoadStates
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.biometric.app.data.entity.AuditLog
import com.biometric.app.databinding.ActivityAuditTrailBinding
import com.biometric.app.databinding.ItemAuditLogBinding
import com.biometric.app.ui.viewmodel.AuditTrailViewModel
import com.biometric.app.ui.viewmodel.MainViewModel
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.util.DateRangeUtil
import com.biometric.app.util.PremiumLoader
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview

import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import com.biometric.app.ui.adapter.HorizontalFilterAdapter
import com.biometric.app.ui.viewmodel.AuditTrailState
import com.biometric.app.util.PickerHelper

@AndroidEntryPoint
@OptIn(ExperimentalCoroutinesApi::class)
class AuditTrailActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityAuditTrailBinding
    private val viewModel: AuditTrailViewModel by viewModels()
    private val mainViewModel: MainViewModel by viewModels()
    @Inject lateinit var sharedViewModel: SharedViewModel

    private var selectedDate = Calendar.getInstance()
    private var currentFilter = "Daily"
    private var shopId: String? = null
    private var shopName: String = ""
    private var auditSearch: String = ""
    private lateinit var auditAdapter: AuditLogAdapter
    private lateinit var filterAdapter: HorizontalFilterAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return

        binding = ActivityAuditTrailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.clAuditTrailRoot, binding.appBar)

        val initialShopName = intent.getStringExtra("SHOP_NAME") ?: "Financial Audit"
        shopId = intent.getStringExtra("SHOP_ID")

        setupToolbar(initialShopName)
        setupUI()
        observeViewModel()

        setupMotionFeedback(binding.layoutFilterIcons.btnPrevDate, binding.layoutFilterIcons.btnNextDate)
    }

    private fun setupToolbar(name: String) {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.toolbar.setOnClickListener {
            showShopSelectionDialog()
        }
        updateToolbarTitle(name)
    }

    private fun updateToolbarTitle(name: String) {
        this.shopName = name
        setupDualHeader(binding.toolbar, name, "Financial Audit")
    }

    private fun showShopSelectionDialog() {
        val shops = mainViewModel.allShops.value
        if (shops.isEmpty()) return

        val shopNames = shops.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Switch Shop")
            .setItems(shopNames) { _, which ->
                val selectedShop = shops[which]
                shopId = selectedShop.shopId
                sharedViewModel.setSelectedShop(selectedShop)
                updateToolbarTitle(selectedShop.name)
                refreshData()
            }
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

    private fun setupUI() {
        binding.btnOpenRecycleBin.setOnClickListener {
            startActivity(Intent(this, RecycleBinActivity::class.java))
        }

        auditAdapter = AuditLogAdapter { log ->
            showAuditDetailsDialog(log)
        }
        binding.rvAuditLogs.layoutManager = LinearLayoutManager(this)
        binding.rvAuditLogs.adapter = auditAdapter

        filterAdapter = com.biometric.app.ui.adapter.HorizontalFilterAdapter(showCustom = false) { selected: String ->
            currentFilter = selected
            refreshData()
        }
        binding.layoutFilterIcons.rvFilterIcons.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 7)
        binding.layoutFilterIcons.rvFilterIcons.adapter = filterAdapter

        binding.layoutFilterIcons.btnPrevDate.setOnClickListener { adjustDate(-1) }
        binding.layoutFilterIcons.btnNextDate.setOnClickListener { adjustDate(1) }

        binding.layoutFilterIcons.tvDateLabel.setOnClickListener {
            PickerHelper.showSmartPicker(
                this, currentFilter, selectedDate,
            ) { newDate ->
                selectedDate.timeInMillis = newDate.timeInMillis
                refreshData()
            }
        }

        binding.etSearch.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    auditSearch = s?.toString().orEmpty().trim()
                    refreshData()
                }
                override fun afterTextChanged(s: Editable?) {}
            }
        )
    }

    private fun showAuditDetailsDialog(log: AuditLog) {
        val detailsBinding = com.biometric.app.databinding.DialogAuditDetailsBinding.inflate(layoutInflater)

        detailsBinding.tvUser.text = log.userDisplayName
        detailsBinding.tvModule.text = log.module.uppercase()
        detailsBinding.tvAction.text = log.action
        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm:a", Locale.getDefault())
        detailsBinding.tvTimestamp.text = sdf.format(Date(log.timestamp))

        if (log.oldValue != null) {
            detailsBinding.llOldValue.visibility = View.VISIBLE
            detailsBinding.tvOldValueFull.text = formatJson(log.oldValue!!)
        } else {
            detailsBinding.llOldValue.visibility = View.GONE
        }

        if (log.newValue != null) {
            detailsBinding.llNewValue.visibility = View.VISIBLE
            detailsBinding.tvNewValueFull.text = formatJson(log.newValue!!)
        } else {
            detailsBinding.llNewValue.visibility = View.GONE
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Audit Log Details")
            .setView(detailsBinding.root)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun formatJson(json: String): String {
        return try {
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            val element = com.google.gson.JsonParser.parseString(json)
            gson.toJson(element)
        } catch (_: Exception) {
            json
        }
    }

    private fun adjustDate(amount: Int) {
        DateRangeUtil.adjustDate(currentFilter, selectedDate, amount)
        refreshData()
    }

    private fun refreshData() {
        updateFilterText()
        viewModel.loadLogs(shopId, currentFilter, selectedDate.timeInMillis, auditSearch)
    }

    private fun updateFilterText() {
        binding.layoutFilterIcons.tvDateLabel.text = DateRangeUtil.getFormattedRangeLabel(currentFilter, selectedDate.timeInMillis)
        filterAdapter.setSelected(currentFilter, selectedDate.timeInMillis)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    sharedViewModel.selectedShop.collectLatest { shop ->
                        shop?.let {
                            shopId = it.shopId
                            updateToolbarTitle(it.name)
                            refreshData()
                        }
                    }
                }
                launch {
                    combine(
                        viewModel.state,
                        auditAdapter.loadStateFlow
                    ) { state: AuditTrailState, loadStates: CombinedLoadStates ->
                        val refreshLoading = loadStates.refresh is androidx.paging.LoadState.Loading

                        // ATOMIC LOADING: Hide only when EVERYTHING is ready
                        val isSummaryLoading = state.isLoading
                        val isLogsLoading = refreshLoading && auditAdapter.itemCount == 0

                        isSummaryLoading || isLogsLoading
                    }
                    .distinctUntilChanged()
                    .collectLatest { showBrewing ->
                        if (showBrewing) {
                            PremiumLoader.show(binding.brewingLoader, PremiumLoader.ScreenType.AUDIT, lifecycleScope, immediate = true)
                            binding.contentLayout.visibility = View.GONE
                        } else {
                            PremiumLoader.hide(binding.brewingLoader)
                            binding.contentLayout.visibility = View.VISIBLE
                            binding.contentLayout.alpha = 1f
                        }
                    }
                }
                launch {
                    viewModel.state.collectLatest { state ->
                        binding.tvSummaryUpdates.text = state.summary.updates.toString()
                        binding.tvSummaryDeletions.text = state.summary.deletions.toString()
                        binding.tvSummaryRestorations.text = state.summary.restorations.toString()
                        binding.tvSummaryNew.text = state.summary.newEntries.toString()
                    }
                }
                launch {
                    viewModel.pagedLogs.collectLatest { pagingData ->
                        auditAdapter.submitData(pagingData)
                    }
                }
                launch {
                    auditAdapter.loadStateFlow.collectLatest { loadStates ->
                        val refreshState = loadStates.refresh
                        val isError = refreshState is androidx.paging.LoadState.Error

                        if (isError) {
                            val error = refreshState.error
                            android.util.Log.e("AuditTrail", "Paging error: ${error.message}")
                        }

                        // Empty State Logic
                        val isInitialLoading = refreshState is androidx.paging.LoadState.Loading
                        val isEmpty = !isError && !isInitialLoading && auditAdapter.itemCount == 0

                        if (isEmpty) {
                            binding.llEmptyState.visibility = View.VISIBLE
                            binding.rvAuditLogs.visibility = View.GONE
                        } else {
                            binding.llEmptyState.visibility = View.GONE
                            binding.rvAuditLogs.visibility = View.VISIBLE

                            if (refreshState is androidx.paging.LoadState.NotLoading && loadStates.prepend.endOfPaginationReached) {
                                binding.rvAuditLogs.scrollToPosition(0)
                            }
                        }
                    }
                }
            }
        }
    }

    class AuditLogAdapter(private val onItemClick: (AuditLog) -> Unit) :
        PagingDataAdapter<AuditLog, AuditLogAdapter.ViewHolder>(AuditDiffCallback()) {

        class ViewHolder(val binding: ItemAuditLogBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(ItemAuditLogBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val log = getItem(position) ?: return
            val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
            val dayFormat = SimpleDateFormat("dd MMM", Locale.getDefault())

            holder.binding.tvLogUser.text = log.userDisplayName
            holder.binding.tvLogModule.text = log.module.uppercase()
            holder.binding.tvLogTime.text = String.format(Locale.getDefault(), "%s\n%s", dayFormat.format(Date(log.timestamp)), timeFormat.format(Date(log.timestamp)))

            val (icon, actionText) = when(log.action) {
                "ADD" -> "➕" to "Added new ${log.module}"
                "UPDATE" -> "✏️" to "Updated ${log.module}"
                "DELETE" -> "🗑️" to "Deleted ${log.module} entry"
                "RESTORE" -> "♻️" to "Restored ${log.module} record"
                else -> "📝" to "Action on ${log.module}"
            }

            holder.binding.tvActionIcon.text = icon
            holder.binding.tvLogActionText.text = actionText

            if ((log.oldValue != null) || (log.newValue != null)) {
                holder.binding.llValueChange.visibility = View.VISIBLE
                holder.binding.tvOldValue.text = log.oldValue?.take(30) ?: "None"
                holder.binding.tvNewValue.text = log.newValue?.take(30) ?: "None"
            } else {
                holder.binding.llValueChange.visibility = View.GONE
            }

            holder.itemView.setOnClickListener { onItemClick(log) }
        }
    }

    class AuditDiffCallback : DiffUtil.ItemCallback<AuditLog>() {
        override fun areItemsTheSame(oldItem: AuditLog, newItem: AuditLog): Boolean =
            oldItem.logId == newItem.logId

        override fun areContentsTheSame(oldItem: AuditLog, newItem: AuditLog): Boolean =
            oldItem == newItem
    }
}
