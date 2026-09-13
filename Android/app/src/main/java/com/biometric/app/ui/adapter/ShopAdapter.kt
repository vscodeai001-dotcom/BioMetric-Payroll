package com.biometric.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.data.entity.Shop
import com.biometric.app.databinding.ItemShopCardBinding
import com.biometric.app.ui.viewmodel.ShopWorkforceState

class ShopAdapter(
    private val onShopClick: (Shop) -> Unit,
    private val onDeleteClick: (Shop) -> Unit
) : ListAdapter<ShopWorkforceState, ShopAdapter.ShopViewHolder>(ShopDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ShopViewHolder {
        val binding = ItemShopCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ShopViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ShopViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ShopViewHolder(private val binding: ItemShopCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(state: ShopWorkforceState) {
            val shop = state.shop
            binding.tvShopName.text = shop.name
            binding.tvLocation.text = if (shop.location.isBlank()) "No Location" else "📍 ${shop.location}"
            
            binding.tvPresentToday.text = "${state.presentCount} / ${state.staffCount}"

            binding.root.setOnClickListener { onShopClick(shop) }
            binding.root.setOnLongClickListener {
                onDeleteClick(shop)
                true
            }
        }
    }

    class ShopDiffCallback : DiffUtil.ItemCallback<ShopWorkforceState>() {
        override fun areItemsTheSame(oldItem: ShopWorkforceState, newItem: ShopWorkforceState) = oldItem.shop.shopId == newItem.shop.shopId
        override fun areContentsTheSame(oldItem: ShopWorkforceState, newItem: ShopWorkforceState) = oldItem == newItem
    }
}
