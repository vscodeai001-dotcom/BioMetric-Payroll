package com.biometric.app.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.data.dao.LocalSettingsDao
import com.biometric.app.databinding.ActivityTenantSelectionBinding
import com.biometric.app.databinding.ItemTenantCompanyCardBinding
import com.biometric.app.sync.FirebaseRoomHydrator
import com.biometric.app.sync.FirebaseSyncManager
import com.biometric.app.ui.viewmodel.MainViewModel
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.util.HapticUtil
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class TenantCompany(
    val tenantId: String = "",
    val companyName: String = "",
    val companyCode: String = "",
    val adminEmail: String = "",
    val adminName: String = "",
    val adminPhone: String? = null,
    val iconEmoji: String = "🏢",
    val planMode: String = "Spark",
    val isOfflineMode: Boolean = false,
    val deploymentMode: String = "CloudOnly",
    val isActive: Boolean = true,
    val createdAtUtc: String = ""
)

@AndroidEntryPoint
class TenantSelectionActivity : MotionBaseActivity() {

    private var _binding: ActivityTenantSelectionBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var sharedViewModel: SharedViewModel
    @Inject lateinit var firebaseSync: FirebaseSyncManager
    @Inject lateinit var firebaseRoomHydrator: FirebaseRoomHydrator
    @Inject lateinit var localSettingsDao: LocalSettingsDao
    @Inject lateinit var appDatabase: com.biometric.app.data.AppDatabase
    private val mainViewModel: MainViewModel by viewModels()

    private lateinit var adapter: TenantCompanyAdapter
    private var allCompaniesList: List<TenantCompany> = emptyList()
    private var searchQuery: String = ""
    private var tenantsRef: DatabaseReference? = null
    private var tenantsListener: ValueEventListener? = null

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
                    binding.progressBar.isVisible = true
                    tenantsRef?.addListenerForSingleValueEvent(tenantsListener ?: return@setOnMenuItemClickListener true)
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
        binding.progressBar.isVisible = true

        val ref = FirebaseDatabase.getInstance().getReference("tenants")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = mutableListOf<TenantCompany>()
                for (child in snapshot.children) {
                    val tid = child.child("tenantId").getValue(String::class.java) ?: child.key.orEmpty()
                    val name = child.child("companyName").getValue(String::class.java).orEmpty()
                    val code = child.child("companyCode").getValue(String::class.java).orEmpty()
                    val email = child.child("adminEmail").getValue(String::class.java).orEmpty()
                    val admin = child.child("adminName").getValue(String::class.java).orEmpty()
                    val phone = child.child("adminPhone").getValue(String::class.java)
                    val emoji = child.child("iconEmoji").getValue(String::class.java) ?: "🏢"
                    val plan = child.child("planMode").getValue(String::class.java) ?: "Spark"
                    val active = child.child("isActive").getValue(Boolean::class.java) ?: true
                    val offline = child.child("isOfflineMode").getValue(Boolean::class.java) ?: false
                    val deploy = child.child("deploymentMode").getValue(String::class.java) ?: "CloudOnly"
                    val created = child.child("createdAtUtc").getValue(String::class.java).orEmpty()

                    if (name.isNotBlank() || code.isNotBlank()) {
                        list.add(
                            TenantCompany(
                                tenantId = tid,
                                companyName = name,
                                companyCode = code,
                                adminEmail = email,
                                adminName = admin,
                                adminPhone = phone,
                                iconEmoji = emoji,
                                planMode = plan,
                                isOfflineMode = offline,
                                deploymentMode = deploy,
                                isActive = active,
                                createdAtUtc = created
                            )
                        )
                    }
                }

                lifecycleScope.launch {
                    val effectiveList = list
                    allCompaniesList = effectiveList
                    binding.progressBar.isVisible = false
                    updateKpis(effectiveList)
                    filterAndSubmitList()

                    val currentTenantId = sessionStore.activeTenantId()
                    if (effectiveList.isEmpty() || (!currentTenantId.isNullOrBlank() && effectiveList.none { it.tenantId == currentTenantId })) {
                        withContext(Dispatchers.IO) {
                            appDatabase.clearAllTables()
                        }
                        sessionStore.clearActiveTenant()
                        getSharedPreferences("auth_prefs", MODE_PRIVATE).edit()
                            .remove("selected_tenant_id")
                            .remove("selected_tenant_name")
                            .remove("selected_tenant_code")
                            .remove("firebase_owner_uid")
                            .apply()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                binding.progressBar.isVisible = false
                lifecycleScope.launch {
                    allCompaniesList = emptyList()
                    updateKpis(emptyList())
                    filterAndSubmitList()
                }
            }
        }

        ref.addValueEventListener(listener)
        tenantsRef = ref
        tenantsListener = listener
    }

    private fun updateKpis(companies: List<TenantCompany>) {
        binding.tvKpiTotalCompanies.text = companies.size.toString()
        binding.tvKpiSparkMode.text = companies.count { it.planMode.equals("Spark", ignoreCase = true) }.toString()
        binding.tvKpiActiveCompanies.text = companies.count { it.isActive }.toString()
        binding.tvKpiTotalStaff.text = companies.size.toString()
    }

    private fun filterAndSubmitList() {
        val filtered = if (searchQuery.isBlank()) {
            allCompaniesList
        } else {
            allCompaniesList.filter {
                it.companyName.contains(searchQuery, ignoreCase = true) ||
                it.companyCode.contains(searchQuery, ignoreCase = true) ||
                it.adminName.contains(searchQuery, ignoreCase = true) ||
                it.adminEmail.contains(searchQuery, ignoreCase = true) ||
                it.tenantId.contains(searchQuery, ignoreCase = true)
            }
        }

        adapter.submitList(filtered)
        binding.llEmptyState.isVisible = filtered.isEmpty()
    }

    private fun enterWorkspace(tenant: TenantCompany) {
        // Save tenant details to MobileSessionStore and SharedPreferences
        sessionStore.saveActiveTenant(tenant.tenantId, tenant.companyName, tenant.companyCode)
        sessionStore.setFirebaseOwnerUid(tenant.tenantId)
        sessionStore.setDeploymentMode(tenant.deploymentMode)
        sessionStore.setOfflineMode(tenant.isOfflineMode)

        getSharedPreferences("auth_prefs", MODE_PRIVATE).edit()
            .putString("selected_tenant_id", tenant.tenantId)
            .putString("selected_tenant_name", tenant.companyName)
            .putString("selected_tenant_code", tenant.companyCode)
            .putString("firebase_owner_uid", tenant.tenantId)
            .apply()

        // Restart Firebase Room Hydrator to listen to this specific tenant's owner root
        runCatching {
            firebaseRoomHydrator.stop()
            firebaseRoomHydrator.start()
        }

        // Launch into MainActivity dashboard and menus
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("TENANT_ID", tenant.tenantId)
            putExtra("TENANT_NAME", tenant.companyName)
            putExtra("COMPANY_NAME", tenant.companyName)
            putExtra("COMPANY_CODE", tenant.companyCode)
        }
        startActivity(intent)
        finish()
    }

    private fun handleLogout() {
        FirebaseAuth.getInstance().signOut()
        sessionStore.clearLogin()
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
        tenantsRef?.let { r -> tenantsListener?.let { l -> r.removeEventListener(l) } }
        _binding = null
    }

    inner class TenantCompanyAdapter : RecyclerView.Adapter<TenantCompanyAdapter.Holder>() {
        private var items: List<TenantCompany> = emptyList()

        fun submitList(list: List<TenantCompany>) {
            items = list
            notifyDataSetChanged()
        }

        inner class Holder(val b: ItemTenantCompanyCardBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ItemTenantCompanyCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val tenant = items[position]
            val b = holder.b

            b.tvCompanyEmoji.text = tenant.iconEmoji.ifBlank { "🏢" }
            b.tvCompanyName.text = tenant.companyName.ifBlank { "Client Company #${position + 1}" }
            b.tvCompanyCode.text = tenant.companyCode.ifBlank { tenant.tenantId.take(8).uppercase() }
            b.tvPlanMode.text = "⚡ ${tenant.planMode}"

            val adminInfo = if (tenant.adminName.isNotBlank()) "👤 ${tenant.adminName}" else "👤 Enterprise Admin"
            val contactInfo = if (!tenant.adminPhone.isNullOrBlank()) " • 📞 ${tenant.adminPhone}" else if (tenant.adminEmail.isNotBlank()) " • ✉️ ${tenant.adminEmail}" else ""
            b.tvCompanyLocation.text = "$adminInfo$contactInfo"
            b.tvStaffCount.text = "🏢 Workspace: ${tenant.tenantId}"

            if (tenant.isActive) {
                b.tvActiveStatus.text = "Active 🟢"
                b.tvActiveStatus.backgroundTintList = ColorStateList.valueOf(getColor(R.color.green_light))
                b.tvActiveStatus.setTextColor(getColor(R.color.green_900))
            } else {
                b.tvActiveStatus.text = "Inactive 🔴"
                b.tvActiveStatus.backgroundTintList = ColorStateList.valueOf(getColor(R.color.red_light))
                b.tvActiveStatus.setTextColor(getColor(R.color.red_900))
            }

            b.btnEnterWorkspace.setOnClickListener {
                HapticUtil.vibrateClick(it)
                enterWorkspace(tenant)
            }

            holder.itemView.setOnClickListener {
                HapticUtil.vibrateClick(it)
                enterWorkspace(tenant)
            }
        }

        override fun getItemCount() = items.size
    }
}
