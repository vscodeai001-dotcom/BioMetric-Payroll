package com.biometric.app.ui.adapter

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.data.entity.RecycleBinItem
import com.biometric.app.databinding.ItemRecycleBinRowBinding
import java.text.SimpleDateFormat
import java.util.*

class RecycleBinAdapter(
    private val onRestore: (RecycleBinItem) -> Unit,
    private val onDeletePermanent: (RecycleBinItem) -> Unit
) : ListAdapter<RecycleBinItem, RecycleBinAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecycleBinRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemRecycleBinRowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: RecycleBinItem) {
            binding.tvItemName.text = item.itemName
            
            val sdf = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())
            val timeStr = sdf.format(Date(item.timestamp))
            binding.tvActionInfo.text = String.format("%s by %s • %s", item.actionType, item.userName, timeStr)
            
            binding.tvModuleTag.text = item.module
            val color = when (item.module) {
                "STAFF" -> R.color.colorPrimary
                "ATTENDANCE" -> R.color.green
                "SHOP" -> R.color.amber
                else -> R.color.text_secondary
            }
            binding.tvModuleTag.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(itemView.context, color))
            
            binding.tvModuleIcon.text = when (item.module) {
                "STAFF" -> "👥"
                "ATTENDANCE" -> "📅"
                "SHOP" -> "🏪"
                else -> "📦"
            }

            binding.btnRestore.setOnClickListener { onRestore(item) }
            binding.btnDeletePermanent.setOnClickListener { onDeletePermanent(item) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<RecycleBinItem>() {
        override fun areItemsTheSame(oldItem: RecycleBinItem, newItem: RecycleBinItem) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: RecycleBinItem, newItem: RecycleBinItem) = oldItem == newItem
    }
}
