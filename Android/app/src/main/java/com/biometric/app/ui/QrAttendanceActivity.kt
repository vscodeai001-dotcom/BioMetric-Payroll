package com.biometric.app.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.biometric.app.databinding.ActivityQrAttendanceBinding
import com.biometric.app.util.QrUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class QrAttendanceActivity : MotionBaseActivity() {

    private lateinit var binding: ActivityQrAttendanceBinding
    private var shopId: String = ""
    private var shopName: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private val refreshInterval = 30000L // 30 seconds

    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateQrCode()
            handler.postDelayed(this, refreshInterval)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrAttendanceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        shopId = intent.getStringExtra("SHOP_ID") ?: ""
        shopName = intent.getStringExtra("SHOP_NAME") ?: "Tea Shop"

        binding.tvShopName.text = shopName
        binding.btnClose.setOnClickListener { finish() }

        updateQrCode()
    }

    override fun onStart() {
        super.onStart()
        handler.postDelayed(refreshRunnable, refreshInterval)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun updateQrCode() {
        val timestamp = System.currentTimeMillis()
        val content = "ATTENDANCE|$shopId|$timestamp"
        val bitmap = QrUtil.generateQrCode(content)
        if (bitmap != null) {
            binding.ivQrCode.setImageBitmap(bitmap)
        }

        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val validUntil = Date(timestamp + refreshInterval)
        binding.tvTimestamp.text = "Valid until: ${sdf.format(validUntil)}"
    }
}
