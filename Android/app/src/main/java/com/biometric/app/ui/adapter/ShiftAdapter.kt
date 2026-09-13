package com.biometric.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.data.entity.WorkShift
import com.biometric.app.databinding.ItemShiftCardBinding

class ShiftAdapter(
    private val items: List<WorkShift>,
    private val onItemClick: (WorkShift) -> Unit,
    private val onDeleteClick: (WorkShift) -> Unit
) : RecyclerView.Adapter<ShiftAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemShiftCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemShiftCardBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: WorkShift) {
            binding.tvName.text = item.name
            binding.tvDetails.text = "${item.startTime} - ${item.endTime} | Grace: ${item.graceMinutes}m"
            
            binding.root.setOnClickListener { onItemClick(item) }
            binding.btnDelete.setOnClickListener { onDeleteClick(item) }
        }
    }
}
