package com.biometric.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.biometric.app.R
import com.biometric.app.api.FbpComponentDto
import com.biometric.app.api.MobileApiService
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.entity.LocalFbpComponent
import com.biometric.app.databinding.ActivityFbpComponentsBinding
import com.biometric.app.databinding.DialogFbpComponentEditBinding
import com.biometric.app.sync.FirebaseSyncManager
import com.biometric.app.util.HapticUtil
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class FbpComponentsActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityFbpComponentsBinding

    @Inject lateinit var sync: FirebaseSyncManager
    @Inject lateinit var api: MobileApiService
    @Inject lateinit var session: MobileSessionStore

    private var allComponents = listOf<LocalFbpComponent>()
    private val currency = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).apply {
        maximumFractionDigits = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFbpComponentsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets(binding.clFbpComponentsRoot, binding.appBar)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            HapticUtil.vibrateClick(it)
            finish()
        }

        setupListeners()
        observeRealtimeComponents()
    }

    private fun setupListeners() {
        binding.fabAddComponent.setOnClickListener {
            HapticUtil.vibrateClick(it)
            showEditDialog(null)
        }
        binding.btnAddComponentTop.setOnClickListener {
            HapticUtil.vibrateClick(it)
            showEditDialog(null)
        }
        binding.btnReviewDeclarations.setOnClickListener {
            HapticUtil.vibrateClick(it)
            startActivity(android.content.Intent(this, FbpDeclarationApprovalActivity::class.java))
        }
    }

    private fun observeRealtimeComponents() {
        lifecycleScope.launch {
            sync.getDataFlow<LocalFbpComponent>("fbp_components").collectLatest { rows ->
                allComponents = rows.sortedBy { it.name }
                updateKpiDashboard()
                renderComponents()
            }
        }
    }

    private fun updateKpiDashboard() {
        val total = allComponents.size
        val active = allComponents.count { it.isActive }
        val taxExempt = allComponents.count { it.isTaxExempt }

        binding.tvKpiTotal.text = total.toString()
        binding.tvKpiActive.text = active.toString()
        binding.tvKpiTaxExempt.text = taxExempt.toString()

        binding.tvStatus.text = "SSOT Realtime • $total FBP benefit components"
    }

    private fun renderComponents() {
        val container = binding.llComponentsContainer
        container.removeAllViews()

        val hasData = allComponents.isNotEmpty()
        binding.llEmptyState.isVisible = !hasData

        if (!hasData) return

        val inflater = LayoutInflater.from(this)

        allComponents.forEach { comp ->
            val card = inflater.inflate(R.layout.item_fbp_component_row, container, false)

            val tvComponentName = card.findViewById<TextView>(R.id.tvComponentName)
            val tvComponentMeta = card.findViewById<TextView>(R.id.tvComponentMeta)
            val tvActiveBadge = card.findViewById<TextView>(R.id.tvActiveBadge)
            val tvTaxExemptBadge = card.findViewById<TextView>(R.id.tvTaxExemptBadge)
            val tvMaxLimit = card.findViewById<TextView>(R.id.tvMaxLimit)
            val btnEdit = card.findViewById<MaterialButton>(R.id.btnEditComponent)

            tvComponentName.text = comp.name
            tvComponentMeta.text = "Component ID #${comp.componentId}"
            tvMaxLimit.text = "${currency.format(comp.maxAnnualLimit)} / year"

            // Active Badge
            if (comp.isActive) {
                tvActiveBadge.text = "ACTIVE 🟢"
                tvActiveBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.green_light))
                tvActiveBadge.setTextColor(getColor(R.color.green_700))
            } else {
                tvActiveBadge.text = "INACTIVE ⚪"
                tvActiveBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.colorSurfaceVariant))
                tvActiveBadge.setTextColor(getColor(R.color.text_secondary))
            }

            // Tax Exempt Badge
            if (comp.isTaxExempt) {
                tvTaxExemptBadge.text = "TAX EXEMPT 🛡️"
                tvTaxExemptBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.blue_light))
                tvTaxExemptBadge.setTextColor(getColor(R.color.colorPrimary))
            } else {
                tvTaxExemptBadge.text = "TAXABLE 🪙"
                tvTaxExemptBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.amber_light))
                tvTaxExemptBadge.setTextColor(getColor(R.color.amber_900))
            }

            btnEdit.setOnClickListener {
                HapticUtil.vibrateClick(it)
                showEditDialog(comp)
            }

            container.addView(card)
        }
    }

    private fun showEditDialog(comp: LocalFbpComponent?) {
        val dialogBinding = DialogFbpComponentEditBinding.inflate(layoutInflater)

        if (comp != null) {
            dialogBinding.etComponentName.setText(comp.name)
            dialogBinding.etMaxLimit.setText(comp.maxAnnualLimit.toInt().toString())
            dialogBinding.switchTaxExempt.isChecked = comp.isTaxExempt
            dialogBinding.switchActive.isChecked = comp.isActive
        }

        val title = if (comp == null) "Add FBP Component ➕" else "Edit ${comp.name} ✏️"

        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(dialogBinding.root)
            .setPositiveButton(if (comp == null) "Add Component" else "Update") { _, _ ->
                val name = dialogBinding.etComponentName.text.toString().trim()
                val limitStr = dialogBinding.etMaxLimit.text.toString().trim()
                val limit = limitStr.toDoubleOrNull() ?: 0.0
                val isTaxExempt = dialogBinding.switchTaxExempt.isChecked
                val isActive = dialogBinding.switchActive.isChecked

                if (name.isBlank() || limit <= 0.0) {
                    Toast.makeText(this, "Component Name and positive Max Limit are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                saveComponent(comp?.componentId ?: 0, name, limit, isActive, isTaxExempt)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveComponent(id: Int, name: String, limit: Double, active: Boolean, exempt: Boolean) {
        lifecycleScope.launch {
            try {
                val targetId = if (id > 0) id else ((allComponents.maxOfOrNull { it.componentId } ?: 0) + 1)
                val owner = sync.getOwnerRef()
                if (owner != null) {
                    owner.child("fbp_components").child(targetId.toString()).setValue(
                        mapOf(
                            "componentId" to targetId,
                            "name" to name,
                            "maxAnnualLimit" to limit,
                            "isActive" to active,
                            "isTaxExempt" to exempt
                        )
                    ).await()
                    sync.notifyRealtimeChanged("FBPComponent", if (id > 0) "UPDATED" else "ADDED", targetId.toString())
                }

                runCatching {
                    api.saveAdminFbpComponent(
                        "Bearer ${session.token().orEmpty()}",
                        FbpComponentDto(targetId, name, limit, active, exempt)
                    )
                }

                Toast.makeText(this@FbpComponentsActivity, "FBP Component '$name' saved successfully 🏦 ✅", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@FbpComponentsActivity, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
