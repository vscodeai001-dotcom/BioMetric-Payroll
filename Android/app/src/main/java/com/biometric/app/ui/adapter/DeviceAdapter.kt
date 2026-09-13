package com.biometric.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.data.entity.UserProfile
import com.biometric.app.databinding.ItemDeviceCardBinding

class DeviceAdapter(
    private val items: List<UserProfile>,
    private val onRevokeClick: (UserProfile) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDeviceCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemDeviceCardBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: UserProfile) {
            binding.tvStaffName.text = item.name
            binding.tvDeviceInfo.text = "${item.deviceModel ?: "Unknown"} | SDK ${item.androidVersion ?: "?"}"
            
            binding.btnRevoke.setOnClickListener { onRevokeClick(item) }
        }
    }
}
