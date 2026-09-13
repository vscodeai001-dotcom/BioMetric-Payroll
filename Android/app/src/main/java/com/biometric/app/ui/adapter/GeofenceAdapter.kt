package com.biometric.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.data.entity.GeofenceLocation
import com.biometric.app.databinding.ItemGeofenceCardBinding

class GeofenceAdapter(
    private val items: List<GeofenceLocation>,
    private val onItemClick: (GeofenceLocation) -> Unit,
    private val onDeleteClick: (GeofenceLocation) -> Unit
) : RecyclerView.Adapter<GeofenceAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGeofenceCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemGeofenceCardBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: GeofenceLocation) {
            binding.tvName.text = item.name
            binding.tvDetails.text = "Radius: ${item.radius.toInt()}m | Type: ${item.type}"
            
            binding.root.setOnClickListener { onItemClick(item) }
            binding.btnDelete.setOnClickListener { onDeleteClick(item) }
        }
    }
}
