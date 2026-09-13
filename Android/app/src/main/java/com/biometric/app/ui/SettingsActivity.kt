package com.biometric.app.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.core.content.edit
import com.biometric.app.databinding.ActivitySettingsBinding
import com.biometric.app.ui.viewmodel.MainViewModel
import com.biometric.app.ui.viewmodel.SharedViewModel
import com.biometric.app.util.HapticUtil
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : MotionBaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: MainViewModel by viewModels()
    @Inject lateinit var sharedViewModel: SharedViewModel
    
    private var currentShopId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentShopId = intent.getStringExtra("SHOP_ID")
        setupToolbar()
        loadSettings()
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

    private fun setupListeners() {
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
