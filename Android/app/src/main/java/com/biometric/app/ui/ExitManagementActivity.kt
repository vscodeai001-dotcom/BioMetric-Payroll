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
import com.biometric.app.data.entity.ResignationRequest
import com.biometric.app.databinding.ActivityExitManagementBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class ExitManagementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExitManagementBinding
    
    @Inject lateinit var repository: MainRepository
    private val requests = mutableListOf<ResignationRequest>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExitManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        listenToRequests()
    }

    private fun setupRecyclerView() {
        binding.rvExitRequests.layoutManager = LinearLayoutManager(this)
        binding.rvExitRequests.adapter = ExitAdapter()
    }

    private fun listenToRequests() {
        lifecycleScope.launch {
            repository.allResignationRequestsFlow.collectLatest {
                requests.clear()
                requests.addAll(it)
                binding.rvExitRequests.adapter?.notifyDataSetChanged()
            }
        }
    }

    private fun handleAction(request: ResignationRequest) {
        if (request.status == "Pending") {
            MaterialAlertDialogBuilder(this)
                .setTitle("Review Resignation")
                .setMessage("Action for staff ID: ${request.employeeId}?")
                .setPositiveButton("Approve") { _, _ -> updateStatus(request, "Approved") }
                .setNegativeButton("Reject") { _, _ -> updateStatus(request, "Rejected") }
                .show()
        } else if (request.status == "Approved" && !request.isSettled) {
            Toast.makeText(this, "Opening FnF Calculator...", Toast.LENGTH_SHORT).show()
            // Here you would navigate to an FnF activity or show a dialog
        }
    }

    private fun updateStatus(request: ResignationRequest, status: String) {
        val remarks = EditText(this).apply { hint = "Remarks" }
        MaterialAlertDialogBuilder(this)
            .setTitle("Confirm $status")
            .setView(remarks)
            .setPositiveButton("Save") { _, _ ->
                lifecycleScope.launch {
                    repository.updateResignationStatus(request.requestId, status, remarks.text.toString())
                    Toast.makeText(this@ExitManagementActivity, "Status updated to $status", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    inner class ExitAdapter : RecyclerView.Adapter<ExitAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val req = requests[position]
            val tvIcon = holder.itemView.findViewById<TextView>(R.id.tvHistoryIcon)
            val tvTitle = holder.itemView.findViewById<TextView>(R.id.tvHistoryTitle)
            val tvDate = holder.itemView.findViewById<TextView>(R.id.tvHistoryDate)
            val tvReason = holder.itemView.findViewById<TextView>(R.id.tvHistoryReason)
            
            tvIcon.text = "🚪"
            tvTitle.text = "Staff ID: ${req.employeeId}"
            
            val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            tvDate.text = "Last Day: ${sdf.format(Date(req.desiredLastWorkingDay))}"
            tvReason.text = "${req.status}: ${req.reason ?: "No reason provided"}"
            tvReason.visibility = View.VISIBLE

            holder.itemView.setOnClickListener { handleAction(req) }
        }

        override fun getItemCount() = requests.size
        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v)
    }
}
