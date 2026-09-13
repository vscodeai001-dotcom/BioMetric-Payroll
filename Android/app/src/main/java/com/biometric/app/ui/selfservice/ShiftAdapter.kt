package com.biometric.app.ui.selfservice

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.api.ShiftDto
import com.biometric.app.databinding.ItemShiftRowBinding

class ShiftAdapter : ListAdapter<ShiftDto, ShiftAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemShiftRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemShiftRowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ShiftDto) {
            binding.tvDate.text = "🗓️ ${item.date} (${item.day})"
            binding.tvTime.text = "🕒 ${item.startTime} - ${item.endTime}"
            binding.tvStatus.text = "🚩 ${item.status}"
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ShiftDto>() {
        override fun areItemsTheSame(oldItem: ShiftDto, newItem: ShiftDto): Boolean = oldItem.date == newItem.date
        override fun areContentsTheSame(oldItem: ShiftDto, newItem: ShiftDto): Boolean = oldItem == newItem
    }
}
