package com.biometric.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.Shop
import com.biometric.app.databinding.ActivityTenantSelectionBinding
import com.biometric.app.databinding.ItemTenantCompanyCardBinding
import com.biometric.app.sync.FirebaseSyncManager
import com.biometric.app.ui.viewmodel.MainViewModel
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.util.HapticUtil
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TenantSelectionActivity : MotionBaseActivity() {

    private var _binding: ActivityTenantSelectionBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var sharedViewModel: SharedViewModel
    @Inject lateinit var firebaseSync: FirebaseSyncManager
    private val mainViewModel: MainViewModel by viewModels()

    private lateinit var adapter: TenantCompanyAdapter
    private var allCompaniesList: List<Shop> = emptyList()
    private var staffCountMap: Map<String, Int> = emptyMap()
    private var searchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityTenantSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.clTenantSelectionRoot, binding.appBar, binding.nestedScrollView)

        setupToolbar()
        setupRecyclerView()
        setupSearch()
        observeData()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        binding.toolbar.inflateMenu(R.menu.menu_tenant_selection)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_logout -> {
                    handleLogout()
                    true
                }
                R.id.action_refresh -> {
                    sharedViewModel.triggerDashboardRefresh()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = TenantCompanyAdapter()
        binding.rvTenantCompanies.layoutManager = LinearLayoutManager(this)
        binding.rvTenantCompanies.adapter = adapter
    }

    private fun setupSearch() {
        binding.etSearchCompany.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim().orEmpty()
                filterAndSubmitList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(sharedViewModel.allShops, sharedViewModel.allEmployees) { shops, employees ->
                    Pair(shops, employees)
                }.collectLatest { (shops, employees) ->
                    binding.progressBar.isVisible = false

                    // If no shops exist, generate default primary enterprise workspace
                    val effectiveShops = if (shops.isEmpty()) {
                        listOf(
                            Shop(
                                shopId = "default_company_01",
                                name = "Main Enterprise Workspace",
                                location = "Headquarters • Bangalore",
                                isActive = true
                            )
                        )
                    } else {
                        shops
                    }

                    allCompaniesList = effectiveShops
                    staffCountMap = employees.groupBy { it.shopId }.mapValues { it.value.size }

                    updateKpis(effectiveShops, employees.size)
                    filterAndSubmitList()
                }
            }
        }
    }

    private fun updateKpis(shops: List<Shop>, totalStaff: Int) {
        binding.tvKpiTotalCompanies.text = shops.size.toString()
        binding.tvKpiSparkMode.text = shops.size.toString()
        binding.tvKpiActiveCompanies.text = shops.count { it.isActive }.toString()
        binding.tvKpiTotalStaff.text = totalStaff.toString()
    }

    private fun filterAndSubmitList() {
        val filtered = if (searchQuery.isBlank()) {
            allCompaniesList
        } else {
            allCompaniesList.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.location.contains(searchQuery, ignoreCase = true) ||
                it.shopId.contains(searchQuery, ignoreCase = true)
            }
        }

        adapter.submitList(filtered)
        binding.llEmptyState.isVisible = filtered.isEmpty()
    }

    private fun enterWorkspace(shop: Shop) {
        // Set the active tenant company in context and session
        sharedViewModel.setSelectedShop(shop)

        getSharedPreferences("auth_prefs", MODE_PRIVATE).edit()
            .putString("selected_shop_id", shop.shopId)
            .putString("selected_shop_name", shop.name)
            .apply()

        // Launch into MainActivity dashboard and menus
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("SHOP_ID", shop.shopId)
            putExtra("SHOP_NAME", shop.name)
        }
        startActivity(intent)
        finish()
    }

    private fun handleLogout() {
        FirebaseAuth.getInstance().signOut()
        MobileSessionStore(this).clearLogin()
        SecurityBaseActivity.clearProcessAuthorization(applicationContext)
        getSharedPreferences("auth_prefs", MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("user_prefs", MODE_PRIVATE).edit().clear().apply()

        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

    inner class TenantCompanyAdapter : RecyclerView.Adapter<TenantCompanyAdapter.Holder>() {
        private var items: List<Shop> = emptyList()

        fun submitList(list: List<Shop>) {
            items = list
            notifyDataSetChanged()
        }

        inner class Holder(val b: ItemTenantCompanyCardBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemTenantCompanyCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val shop = items[position]
            val b = holder.b

            b.tvCompanyName.text = shop.name.ifBlank { "Client Company #${position + 1}" }
            b.tvCompanyCode.text = if (shop.shopId.length > 8) shop.shopId.take(8).uppercase() else shop.shopId.uppercase()
            b.tvCompanyLocation.text = if (shop.location.isNotBlank()) "📍 ${shop.location}" else "📍 Enterprise Division"

            val staffCount = staffCountMap[shop.shopId] ?: 0
            b.tvStaffCount.text = "👥 $staffCount Active Staff Members"

            if (shop.isActive) {
                b.tvActiveStatus.text = "Active 🟢"
                b.tvActiveStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.green_light))
                b.tvActiveStatus.setTextColor(getColor(R.color.green_900))
            } else {
                b.tvActiveStatus.text = "Inactive 🔴"
                b.tvActiveStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.red_light))
                b.tvActiveStatus.setTextColor(getColor(R.color.red_900))
            }

            b.btnEnterWorkspace.setOnClickListener {
                HapticUtil.vibrateClick(it)
                enterWorkspace(shop)
            }

            holder.itemView.setOnClickListener {
                HapticUtil.vibrateClick(it)
                enterWorkspace(shop)
            }
        }

        override fun getItemCount() = items.size
    }
}
