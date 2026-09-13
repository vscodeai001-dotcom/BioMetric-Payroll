package com.biometric.app.ui.selfservice

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.biometric.app.R
import com.biometric.app.databinding.ActivityGenericContainerBinding
import com.biometric.app.ui.MotionBaseActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MyReportsActivity : MotionBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityGenericContainerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val type = intent.getStringExtra("FRAGMENT_TYPE") ?: "reports"
        
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        
        applyWindowInsets(binding.main, binding.appBar)

        if (savedInstanceState == null) {
            val fragment: Fragment = when(type) {
                "profile" -> {
                    binding.toolbar.title = "Employee Profile"
                    ProfileFragment()
                }
                else -> {
                    binding.toolbar.title = "My Stats & Reports"
                    MyReportsFragment()
                }
            }
            
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, fragment)
                .commit()
        }
    }
}
