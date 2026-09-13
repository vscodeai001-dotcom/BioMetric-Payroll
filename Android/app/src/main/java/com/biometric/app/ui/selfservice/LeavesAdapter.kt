package com.biometric.app.ui.selfservice

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.api.LeaveDto
import com.biometric.app.databinding.ItemLeaveRequestBinding

class LeavesAdapter : ListAdapter<LeaveDto, LeavesAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLeaveRequestBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemLeaveRequestBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: LeaveDto) {
            binding.tvDate.text = "🗓️ ${item.date}"
            binding.tvType.text = "🌴 ${item.leaveType}"
            binding.tvHalfDay.text = if (item.halfDay) "🌗 Half Day" else "🌑 Full Day"
            binding.tvNotes.text = item.notes?.let { "Reason: $it" } ?: "No notes provided"
            
            val context = binding.root.context
            when {
                item.approved -> {
                    binding.tvStatus.text = "Approved ✅"
                    binding.tvStatus.setBackgroundColor(Color.parseColor("#10B981"))
                }
                item.notes?.contains("LOP", true) == true -> {
                    binding.tvStatus.text = "Loss of Pay 🔴"
                    binding.tvStatus.setBackgroundColor(Color.parseColor("#EF4444"))
                }
                else -> {
                    binding.tvStatus.text = "Pending ⏳"
                    binding.tvStatus.setBackgroundColor(Color.parseColor("#F59E0B"))
                }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<LeaveDto>() {
        override fun areItemsTheSame(oldItem: LeaveDto, newItem: LeaveDto): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: LeaveDto, newItem: LeaveDto): Boolean = oldItem == newItem
    }
}
