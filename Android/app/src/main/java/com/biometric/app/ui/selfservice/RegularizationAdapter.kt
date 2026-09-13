package com.biometric.app.ui.selfservice

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.api.RegularizationDto
import com.biometric.app.databinding.ItemRegularizationBinding

class RegularizationAdapter : ListAdapter<RegularizationDto, RegularizationAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRegularizationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemRegularizationBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: RegularizationDto) {
            binding.tvDate.text = "🗓️ ${item.date}"
            binding.tvType.text = if (item.inPunch) "🟢 IN" else "🔴 OUT"
            binding.tvTime.text = "⏱️ ${item.punchTime}"
            binding.tvStatus.text = item.status
            binding.tvRemarks.text = item.remarks?.takeIf { it.isNotBlank() }?.let { "Admin: $it" } ?: ""

            val context = binding.root.context
            when (item.status) {
                "Approved" -> {
                    binding.tvStatus.text = "${item.status} ✅"
                    binding.tvStatus.backgroundTintList = ContextCompat.getColorStateList(context, R.color.green_700)
                }
                "Rejected" -> {
                    binding.tvStatus.text = "${item.status} ❌"
                    binding.tvStatus.backgroundTintList = ContextCompat.getColorStateList(context, R.color.red)
                }
                else -> {
                    binding.tvStatus.text = "${item.status} ⏳"
                    binding.tvStatus.backgroundTintList = ContextCompat.getColorStateList(context, R.color.amber_900)
                }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<RegularizationDto>() {
        override fun areItemsTheSame(oldItem: RegularizationDto, newItem: RegularizationDto): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: RegularizationDto, newItem: RegularizationDto): Boolean = oldItem == newItem
    }
}
