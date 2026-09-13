package com.biometric.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import androidx.lifecycle.lifecycleScope
import com.biometric.app.data.MainRepository
import com.biometric.app.data.entity.LeaveRequest
import com.biometric.app.databinding.ActivityLeaveManagementBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class LeaveManagementActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLeaveManagementBinding
    
    @Inject lateinit var repository: MainRepository

    private val allRequests = mutableListOf<LeaveRequest>()
    private val filteredRequests = mutableListOf<LeaveRequest>()
    private var currentTab = "Pending"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLeaveManagementBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        setupRecyclerView()
        setupTabs()
        listenToRequests()

        binding.fabAddLeave.setOnClickListener {
            // Internal logic for admin to add leave for someone
            Toast.makeText(this, "Admin leave entry coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupRecyclerView() {
        binding.rvLeaveRequests.layoutManager = LinearLayoutManager(this)
        binding.rvLeaveRequests.adapter = LeaveAdapter()
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = tab?.text.toString()
                filterRequests()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun listenToRequests() {
        lifecycleScope.launch {
            repository.allLeaveRequestsFlow.collectLatest {
                allRequests.clear()
                allRequests.addAll(it)
                filterRequests()
            }
        }
    }

    private fun filterRequests() {
        filteredRequests.clear()
        filteredRequests.addAll(allRequests.filter { it.status == currentTab })
        binding.rvLeaveRequests.adapter?.notifyDataSetChanged()
    }

    private fun updateRequestStatus(request: LeaveRequest, newStatus: String) {
        val notesInput = EditText(this).apply {
            hint = "Add admin remarks (optional)"
            setPadding(40, 40, 40, 40)
        }
        
        MaterialAlertDialogBuilder(this)
            .setTitle("$newStatus Request")
            .setMessage("Are you sure you want to $newStatus the leave for ${request.staffName}?")
            .setView(notesInput)
            .setPositiveButton("Confirm") { _, _ ->
                val remarks = notesInput.text.toString()
                lifecycleScope.launch {
                    repository.updateLeaveStatus(request.id, newStatus, remarks)
                    Toast.makeText(this@LeaveManagementActivity, "Request $newStatus", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class LeaveAdapter : RecyclerView.Adapter<LeaveAdapter.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val request = filteredRequests[position]
            val tvIcon = holder.itemView.findViewById<TextView>(R.id.tvHistoryIcon)
            val tvTitle = holder.itemView.findViewById<TextView>(R.id.tvHistoryTitle)
            val tvDate = holder.itemView.findViewById<TextView>(R.id.tvHistoryDate)
            val tvReason = holder.itemView.findViewById<TextView>(R.id.tvHistoryReason)
            val btnDelete = holder.itemView.findViewById<View>(R.id.btnDeleteHistory)

            btnDelete.visibility = View.GONE
            tvIcon.text = "🌴"
            tvTitle.text = "👤 ${request.staffName} - ${request.leaveType}"
            
            val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
            tvDate.text = "🗓️ ${sdf.format(Date(request.startDate))} - ${sdf.format(Date(request.endDate))}"
            tvReason.text = "📝 ${request.reason}"
            tvReason.visibility = View.VISIBLE

            holder.itemView.setOnClickListener {
                if (request.status == "Pending") {
                    showApprovalDialog(request)
                }
            }
        }

        override fun getItemCount() = filteredRequests.size

        private fun showApprovalDialog(request: LeaveRequest) {
            MaterialAlertDialogBuilder(this@LeaveManagementActivity)
                .setTitle("Approval Action")
                .setMessage("Manage leave for ${request.staffName}")
                .setPositiveButton("Approve") { _, _ -> updateRequestStatus(request, "Approved") }
                .setNegativeButton("Reject") { _, _ -> updateRequestStatus(request, "Rejected") }
                .setNeutralButton("Close", null)
                .show()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
    }
}
