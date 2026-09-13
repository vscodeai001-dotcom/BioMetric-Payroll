package com.biometric.app.ui.selfservice

import android.os.Bundle
import com.biometric.app.R
import com.biometric.app.databinding.ActivityGenericContainerBinding
import com.biometric.app.ui.MotionBaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MyRegularizationsActivity : MotionBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityGenericContainerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.title = "Punch Corrections"

        applyWindowInsets(binding.main, binding.appBar)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, RegularizationFragment())
                .commit()
        }
    }
}
