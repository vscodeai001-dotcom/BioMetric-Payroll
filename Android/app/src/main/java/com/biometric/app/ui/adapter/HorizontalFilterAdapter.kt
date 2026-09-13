package com.biometric.app.ui.adapter

import android.graphics.Color
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.databinding.ItemFilterIconBinding

class HorizontalFilterAdapter(
    private val showCustom: Boolean = false,
    private val onFilterSelected: (String) -> Unit
) : RecyclerView.Adapter<HorizontalFilterAdapter.FilterViewHolder>() {

    private val filters = mutableListOf(
        FilterItem("Up To Date", "📈"),
        FilterItem("Daily", "📅"),
        FilterItem("Weekly", "🗓️"),
        FilterItem("Monthly", "📆"),
        FilterItem("Quarterly", "📊"),
        FilterItem("Half Yearly", "🧱"),
        FilterItem("Annually", "🏅")
    ).apply {
        if (showCustom) add(FilterItem("Custom", "⚙️"))
    }

    private var selectedFilter = "Up To Date"
    private var currentDateMillis = System.currentTimeMillis()

    fun setSelected(filter: String, dateMillis: Long = System.currentTimeMillis()) {
        val oldPos = filters.indexOfFirst { it.name == selectedFilter }
        val newPos = filters.indexOfFirst { it.name == filter }
        selectedFilter = filter
        currentDateMillis = dateMillis
        
        // Update dynamic icons
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = currentDateMillis }
        val dayIcon = cal.get(java.util.Calendar.DAY_OF_MONTH).toString()
        val monthIcon = java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault()).format(cal.time).uppercase()
        
        filters.forEachIndexed { index, item ->
            if (item.name == "Daily") filters[index] = item.copy(icon = dayIcon)
            if (item.name == "Monthly") filters[index] = item.copy(icon = monthIcon)
        }
        
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FilterViewHolder {
        val binding = ItemFilterIconBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FilterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FilterViewHolder, position: Int) {
        val item = filters[position]
        val context = holder.itemView.context
        val isSelected = item.name == selectedFilter
        
        holder.binding.tvIcon.text = item.icon
        
        // Match Icon Type: Uniform weight and professional centering
        val isDynamic = (item.name == "Daily") || (item.name == "Monthly")
        holder.binding.tvIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isDynamic) 12f else 20f)
        holder.binding.tvIcon.setTypeface(null, android.graphics.Typeface.BOLD)

        if (isSelected) {
            holder.binding.root.setCardBackgroundColor(ContextCompat.getColor(context, R.color.colorPrimary))
            holder.binding.tvIcon.setTextColor(ContextCompat.getColor(context, R.color.white))
            holder.binding.root.strokeWidth = 0
        } else {
            // UNIFORM BORDERS: Every icon now has a professional border for eye-catching consistency
            val strokeColor: Int
            val bgColor: Int
            val iconColor: Int

            when (item.name) {
                "Daily" -> {
                    strokeColor = ContextCompat.getColor(context, R.color.blue)
                    bgColor = Color.parseColor("#153B82F6")
                    iconColor = strokeColor
                }
                "Monthly" -> {
                    strokeColor = ContextCompat.getColor(context, R.color.green)
                    bgColor = Color.parseColor("#1510B981")
                    iconColor = strokeColor
                }
                "Up To Date" -> {
                    strokeColor = ContextCompat.getColor(context, R.color.colorPrimary)
                    bgColor = Color.parseColor("#104F46E5")
                    iconColor = strokeColor
                }
                "Weekly" -> {
                    strokeColor = ContextCompat.getColor(context, R.color.amber)
                    bgColor = Color.parseColor("#10F59E0B")
                    iconColor = strokeColor
                }
                "Annually" -> {
                    strokeColor = Color.parseColor("#EC4899") // Pink 500
                    bgColor = Color.parseColor("#10EC4899")
                    iconColor = strokeColor
                }
                else -> {
                    strokeColor = ContextCompat.getColor(context, R.color.outline)
                    bgColor = ContextCompat.getColor(context, R.color.colorSurfaceVariant)
                    iconColor = ContextCompat.getColor(context, R.color.text_primary)
                }
            }

            holder.binding.root.setCardBackgroundColor(bgColor)
            holder.binding.tvIcon.setTextColor(iconColor)
            holder.binding.root.strokeColor = strokeColor
            holder.binding.root.strokeWidth = (1.2 * context.resources.displayMetrics.density).toInt()
        }
        
        holder.binding.root.setOnClickListener {
            if (item.name != selectedFilter) {
                onFilterSelected(item.name)
            }
        }
    }

    override fun getItemCount() = filters.size

    data class FilterItem(val name: String, val icon: String)

    class FilterViewHolder(val binding: ItemFilterIconBinding) : RecyclerView.ViewHolder(binding.root)
}
