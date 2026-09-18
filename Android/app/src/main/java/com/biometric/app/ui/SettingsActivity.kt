package com.biometric.app.ui

import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.ArrayAdapter
import android.widget.AdapterView
import com.google.android.material.materialswitch.MaterialSwitch
import com.biometric.app.data.entity.FeatureSettings
import com.biometric.app.data.entity.CompanySettings
import android.widget.EditText
import android.widget.Toast
import com.biometric.app.sync.FirebaseSyncManager
import com.biometric.app.data.repository.TrackingConfigurationRepository
import com.biometric.app.domain.location.TrackingWindowResolver
import com.google.gson.Gson
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.core.content.edit
import com.biometric.app.R
import com.biometric.app.databinding.ActivitySettingsBinding
import com.biometric.app.ui.viewmodel.MainViewModel
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.util.HapticUtil
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.lang.reflect.Modifier

@AndroidEntryPoint
class SettingsActivity : MotionBaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: MainViewModel by viewModels()
    @Inject lateinit var sharedViewModel: SharedViewModel
    @Inject lateinit var firebaseSync: FirebaseSyncManager
    @Inject lateinit var trackingConfiguration: TrackingConfigurationRepository

    private var featureSettings = FeatureSettings()
    private val featureSwitches = linkedMapOf<String, MaterialSwitch>()
    
    private var companySettings = CompanySettings()
    private val companyInputs = linkedMapOf<String, View>()
    
    private var currentShopId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        applyWindowInsets(binding.clSettingsRoot, findViewById(R.id.appBar))

        currentShopId = intent.getStringExtra("SHOP_ID")
        setupToolbar()
        loadSettings()
        setupFeatureSettings()
        setupTrackingConfiguration()
        setupListeners()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        val shopName = intent.getStringExtra("SHOP_NAME")
        binding.toolbar.title = if (shopName != null) "$shopName - Settings ⚙️" else "App Settings ⚙️"
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        binding.switchAdminControls.isChecked = prefs.getBoolean("enable_admin_controls", false)
    }

    private fun setupFeatureSettings() {
        val owner = firebaseSync.getOwnerRef() ?: return
        owner.child("feature_settings").child("1").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                featureSettings = runCatching { Gson().fromJson(Gson().toJson(snapshot.value), FeatureSettings::class.java) }.getOrNull() ?: FeatureSettings()
                renderFeatureSwitches()
            }
            override fun onCancelled(error: DatabaseError) { renderFeatureSwitches() }
        })
        
        owner.child("company_settings").child("1").addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                companySettings = runCatching { Gson().fromJson(Gson().toJson(snapshot.value), CompanySettings::class.java) }.getOrNull() ?: CompanySettings()
                renderCompanySettings()
            }
            override fun onCancelled(error: DatabaseError) { renderCompanySettings() }
        })
    }

    private fun renderFeatureSwitches() {
        binding.featureToggleContainer.removeAllViews()
        featureSwitches.clear()
        FeatureSettings::class.java.declaredFields
            .filter { Modifier.isPublic(it.modifiers) || !Modifier.isStatic(it.modifiers) }
            .filter { it.type == Boolean::class.javaPrimitiveType || it.type == java.lang.Boolean::class.java }
            .forEach { field ->
                field.isAccessible = true
                val sw = MaterialSwitch(this)
                sw.text = "${prettyFieldName(field.name)}  ⚙️"
                sw.isChecked = (field.get(featureSettings) as? Boolean) ?: false
                featureSwitches[field.name] = sw
                binding.featureToggleContainer.addView(sw, LinearLayout.LayoutParams(-1, -2))
            }
        featureSwitches["enableDualAttendance"]?.setOnCheckedChangeListener { _, checked ->
            if (checked) featureSwitches["enableGeoFencing"]?.isChecked = true
        }
        featureSwitches["enableAutomaticGeofencePunching"]?.setOnCheckedChangeListener { _, checked ->
            if (checked) featureSwitches["enableGeoFencing"]?.isChecked = true
        }
        featureSwitches["enableGeoFencing"]?.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                featureSwitches["enableDualAttendance"]?.isChecked = false
                featureSwitches["enableAutomaticGeofencePunching"]?.isChecked = false
            }
        }
    }

    private fun renderCompanySettings() {
        binding.companySettingsContainer.removeAllViews()
        companyInputs.clear()
        
        val fields = CompanySettings::class.java.declaredFields
            .filter { Modifier.isPublic(it.modifiers) || !Modifier.isStatic(it.modifiers) }
            .filter { !it.name.contains("officeLatitude") && !it.name.contains("officeLongitude") && !it.name.contains("geoRadiusMeters") }
            
        fields.forEach { field ->
            field.isAccessible = true
            val name = field.name
            val label = prettyFieldName(name)
            
            val params = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8; bottomMargin = 8 }
            
            when (field.type) {
                Boolean::class.javaPrimitiveType, java.lang.Boolean::class.java -> {
                    val sw = MaterialSwitch(this)
                    sw.text = "$label ⚙️"
                    sw.isChecked = (field.get(companySettings) as? Boolean) ?: false
                    companyInputs[name] = sw
                    binding.companySettingsContainer.addView(sw, params)
                }
                Int::class.javaPrimitiveType, Integer::class.java,
                Double::class.javaPrimitiveType, java.lang.Double::class.java,
                String::class.java -> {
                    val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle)
                    til.hint = label
                    val et = TextInputEditText(til.context)
                    et.setText(field.get(companySettings)?.toString().orEmpty())
                    if (field.type != String::class.java) {
                        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    }
                    til.addView(et)
                    companyInputs[name] = et
                    binding.companySettingsContainer.addView(til, params)
                }
            }
        }
    }

    private fun prettyFieldName(name: String): String = name.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }

    private fun saveFeatureSettings() {
        val profile = sharedViewModel.userProfile.value
        val settings = viewModel.featureSettings.value
        val allowed = profile?.isSuperAdmin() == true || (profile?.isAdmin() == true && (settings?.enablePayroll == true)) // Using enablePayroll as proxy for admin perms check logic
        if (!allowed) {
            Toast.makeText(this, "You do not have permission to edit settings 🔒", Toast.LENGTH_LONG).show()
            return
        }
        val owner = firebaseSync.getOwnerRef() ?: return
        featureSwitches["enableGeoFencing"]?.let { geo ->
            if (!geo.isChecked) {
                featureSwitches["enableDualAttendance"]?.isChecked = false
                featureSwitches["enableAutomaticGeofencePunching"]?.isChecked = false
            }
        }
        featureSwitches.forEach { (name, sw) ->
            runCatching {
                val field = FeatureSettings::class.java.getDeclaredField(name)
                field.isAccessible = true
                field.set(featureSettings, sw.isChecked)
            }
        }
        lifecycleScope.launch {
            runCatching {
                owner.child("feature_settings").child("1").setValue(featureSettings).await()
                firebaseSync.notifyRealtimeAfterWrite("FeatureSettings", "MODIFIED")
                Toast.makeText(this@SettingsActivity, "Feature settings saved ✅", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@SettingsActivity, "Save failed: ${it.message} ⚠️", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveCompanySettings() {
        val profile = sharedViewModel.userProfile.value
        val allowed = profile?.isSuperAdmin() == true || profile?.isAdmin() == true
        if (!allowed) {
            Toast.makeText(this, "You do not have permission to edit company settings 🔒", Toast.LENGTH_LONG).show()
            return
        }
        val owner = firebaseSync.getOwnerRef() ?: return
        
        companyInputs.forEach { (name, view) ->
            runCatching {
                val field = CompanySettings::class.java.getDeclaredField(name)
                field.isAccessible = true
                val value: Any? = when (view) {
                    is MaterialSwitch -> view.isChecked
                    is EditText -> {
                        val text = view.text.toString()
                        when (field.type) {
                            Int::class.javaPrimitiveType, Integer::class.java -> text.toIntOrNull() ?: 0
                            Double::class.javaPrimitiveType, java.lang.Double::class.java -> text.toDoubleOrNull() ?: 0.0
                            else -> text
                        }
                    }
                    else -> null
                }
                field.set(companySettings, value)
            }
        }
        
        lifecycleScope.launch {
            runCatching {
                owner.child("company_settings").child("1").setValue(companySettings).await()
                firebaseSync.notifyRealtimeAfterWrite("CompanySettings", "MODIFIED")
                Toast.makeText(this@SettingsActivity, "Company settings saved ✅", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@SettingsActivity, "Save failed: ${it.message} ⚠️", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupTrackingConfiguration() {
        val modes = listOf(TrackingWindowResolver.MODE_24_7, TrackingWindowResolver.MODE_SHIFT, TrackingWindowResolver.MODE_CUSTOM)
        binding.spinnerTrackingMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)
        lifecycleScope.launch {
            val config = trackingConfiguration.load()
            binding.switchTrackingEnabled.isChecked = config.enabled
            binding.editTrackingCustomStart.setText(config.customStart)
            binding.editTrackingCustomEnd.setText(config.customEnd)
            binding.editTrackingInterval.setText(config.intervalSeconds.toString())
            binding.spinnerTrackingMode.setSelection(modes.indexOf(config.mode).coerceAtLeast(0))
            updateTrackingModeFields(config.mode)
        }
        binding.spinnerTrackingMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateTrackingModeFields(modes.getOrElse(position) { TrackingWindowResolver.MODE_24_7 })
            }
        }
    }

    private fun updateTrackingModeFields(mode: String) {
        val custom = mode == TrackingWindowResolver.MODE_CUSTOM
        binding.editTrackingCustomStart.isEnabled = custom
        binding.editTrackingCustomEnd.isEnabled = custom
    }

    private fun saveTrackingConfiguration() {
        val profile = sharedViewModel.userProfile.value
        val allowed = profile?.isSuperAdmin() == true || profile?.isAdmin() == true
        if (!allowed) {
            Toast.makeText(this, "You do not have permission to edit tracking configuration 🔒", Toast.LENGTH_LONG).show()
            return
        }
        val mode = binding.spinnerTrackingMode.selectedItem?.toString() ?: TrackingWindowResolver.MODE_24_7
        val interval = binding.editTrackingInterval.text?.toString()?.trim()?.toIntOrNull() ?: 30
        if (interval !in 15..3600) {
            binding.editTrackingInterval.error = "Enter 15 to 3600 seconds"
            return
        }
        val start = binding.editTrackingCustomStart.text?.toString()?.trim().orEmpty()
        val end = binding.editTrackingCustomEnd.text?.toString()?.trim().orEmpty()
        if (mode == TrackingWindowResolver.MODE_CUSTOM && (start.isBlank() || end.isBlank())) {
            Toast.makeText(this, "Custom start and end are required", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            runCatching {
                trackingConfiguration.save(TrackingConfigurationRepository.Config(mode, start, end, interval, binding.switchTrackingEnabled.isChecked))
            }.onSuccess {
                Toast.makeText(this@SettingsActivity, "Tracking configuration saved and published to Firebase ✅", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@SettingsActivity, "Tracking save failed: ${it.message} ⚠️", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupListeners() {
        binding.btnSaveTrackingConfiguration.setOnClickListener {
            HapticUtil.vibrateClick(it)
            saveTrackingConfiguration()
        }
        binding.btnSaveFeatureSettings.setOnClickListener {
            HapticUtil.vibrateClick(it)
            saveFeatureSettings()
        }
        binding.btnSaveCompanySettings.setOnClickListener {
            HapticUtil.vibrateClick(it)
            saveCompanySettings()
        }
        binding.switchAdminControls.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("app_prefs", MODE_PRIVATE).edit {
                putBoolean("enable_admin_controls", isChecked)
            }
            HapticUtil.vibrateClick(binding.switchAdminControls)
        }

        binding.btnShareApp.setOnClickListener {
            HapticUtil.vibrateClick(it)
            GlobalSwitcherDelegate.shareApp(this)
        }
        
        setupMotionFeedback(binding.btnShareApp)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        GlobalSwitcherDelegate.inflateMenu(menuInflater, menu, showShopSwitcher = false)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (GlobalSwitcherDelegate.handleOptionsItemSelected(this, item, sharedViewModel)) {
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
