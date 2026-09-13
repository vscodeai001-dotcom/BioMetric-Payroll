package com.biometric.app.ui.selfservice

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.api.MoneyEntryDto
import com.biometric.app.databinding.ItemMoneyEntryBinding
import java.util.Locale

class MoneyAdapter(private val isBonus: Boolean = false) : ListAdapter<MoneyEntryDto, MoneyAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMoneyEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, isBonus)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemMoneyEntryBinding, private val isBonus: Boolean) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: MoneyEntryDto) {
            binding.tvDate.text = "🗓️ ${item.date}"
            binding.tvType.text = if (isBonus) "🌟 ${item.type}" else "💳 ${item.type}"
            binding.tvAmount.text = "₹${String.format(Locale.US, "%,.0f", item.amount)} 💎"
            
            // Premium coloring based on type
            binding.tvAmount.setTextColor(if (isBonus) "#10B981".toColorInt() else "#3B82F6".toColorInt())

            if (item.paid) {
                binding.tvStatus.text = "Settled ✅ 🛡️"
                binding.tvStatus.setBackgroundColor("#10B981".toColorInt())
            } else {
                binding.tvStatus.text = "Pending ⏳ 📊"
                binding.tvStatus.setBackgroundColor("#F59E0B".toColorInt())
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<MoneyEntryDto>() {
        override fun areItemsTheSame(oldItem: MoneyEntryDto, newItem: MoneyEntryDto): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: MoneyEntryDto, newItem: MoneyEntryDto): Boolean = oldItem == newItem
    }
}
