package com.biometric.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.databinding.ItemFeatureToggleBinding
import com.biometric.app.domain.FeatureManager

class FeatureToggleAdapter(
    private val features: List<FeatureManager.Feature>,
    private val initialEnabled: Set<FeatureManager.Feature>,
    private val onToggle: (FeatureManager.Feature, Boolean) -> Unit
) : RecyclerView.Adapter<FeatureToggleAdapter.ViewHolder>() {

    private val enabledSet = initialEnabled.toMutableSet()

    class ViewHolder(val binding: ItemFeatureToggleBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFeatureToggleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val feature = features[position]
        holder.binding.tvFeatureEmoji.text = feature.emoji
        holder.binding.tvFeatureName.text = feature.displayName
        
        // Reset listener to avoid triggering it while setting state
        holder.binding.switchFeature.setOnCheckedChangeListener(null)
        holder.binding.switchFeature.isChecked = enabledSet.contains(feature)
        
        holder.binding.switchFeature.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) enabledSet.add(feature) else enabledSet.remove(feature)
            onToggle(feature, isChecked)
        }
    }

    override fun getItemCount(): Int = features.size
    
    fun getEnabledFeatures(): Set<FeatureManager.Feature> = enabledSet
}
