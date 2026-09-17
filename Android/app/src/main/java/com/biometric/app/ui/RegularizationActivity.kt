package com.biometric.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.data.MainRepository
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.api.*
import com.biometric.app.data.entity.RegularizationRequest
import com.biometric.app.databinding.ActivityRegularizationBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class RegularizationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegularizationBinding
    
    @Inject lateinit var repository: MainRepository
    @Inject lateinit var mobileApi: MobileApiService
    @Inject lateinit var sessionStore: MobileSessionStore

    private val requests = mutableListOf<RegularizationRequest>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegularizationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        observeRequests()
    }

    private fun setupRecyclerView() {
        binding.rvRequests.layoutManager = LinearLayoutManager(this)
        binding.rvRequests.adapter = RegularizationAdapter()
    }

    private fun observeRequests() {
        lifecycleScope.launch {
            repository.getPendingRegularizations().collectLatest {
                requests.clear()
                requests.addAll(it)
                binding.rvRequests.adapter?.notifyDataSetChanged()
            }
        }
    }

    private fun handleApproval(request: RegularizationRequest, approve: Boolean) {
        val status = if (approve) "Approved" else "Rejected"
        val notesInput = EditText(this).apply {
            hint = "Admin remarks (optional)"
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("$status Request")
            .setMessage("Action for ${request.staffName} on ${request.date}?")
            .setView(notesInput)
            .setPositiveButton("Confirm") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val response = mobileApi.setAdminRegularizationStatus("Bearer ${sessionStore.token().orEmpty()}", request.id, AdminRegularizationStatusRequest(status, notesInput.text.toString()))
                        if (!response.isSuccessful) throw IllegalStateException("Server rejected action (${response.code()})")
                        Toast.makeText(this@RegularizationActivity, "Request $status ✅", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this@RegularizationActivity, "Unable to update request: ${e.message} ⚠️", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class RegularizationAdapter : RecyclerView.Adapter<RegularizationAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val request = requests[position]
            val tvIcon = holder.itemView.findViewById<TextView>(R.id.tvHistoryIcon)
            val tvTitle = holder.itemView.findViewById<TextView>(R.id.tvHistoryTitle)
            val tvDate = holder.itemView.findViewById<TextView>(R.id.tvHistoryDate)
            val tvReason = holder.itemView.findViewById<TextView>(R.id.tvHistoryReason)
            val btnDelete = holder.itemView.findViewById<View>(R.id.btnDeleteHistory)

            btnDelete.visibility = View.GONE
            tvIcon.text = "🛠️"
            tvTitle.text = "👤 ${request.staffName} - Correction"
            
            val timeSdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
            val requestedTimeStr = timeSdf.format(Date(request.requestedTime))
            
            tvDate.text = "🗓️ ${request.date} | ⏱️ New: $requestedTimeStr"
            tvReason.text = "📝 ${request.reason}"
            tvReason.visibility = View.VISIBLE

            holder.itemView.setOnClickListener {
                MaterialAlertDialogBuilder(this@RegularizationActivity)
                    .setTitle("Review Correction")
                    .setMessage("Approve ${request.punchType} correction for ${request.staffName}?")
                    .setPositiveButton("Approve") { _, _ -> handleApproval(request, true) }
                    .setNegativeButton("Reject") { _, _ -> handleApproval(request, false) }
                    .setNeutralButton("Cancel", null)
                    .show()
            }
        }

        override fun getItemCount() = requests.size
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
    }
}
