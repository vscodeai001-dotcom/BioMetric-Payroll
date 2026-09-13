package com.biometric.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.ShopClosedDay
import com.biometric.app.databinding.ItemClosedDayBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ClosedDaysAdapter(
    private val allEmployees: List<Employee>,
    private val onDelete: (ShopClosedDay) -> Unit
) : ListAdapter<ShopClosedDay, ClosedDaysAdapter.ViewHolder>(ClosedDayDiffCallback()) {

    class ViewHolder(val binding: ItemClosedDayBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemClosedDayBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.binding.root.context
        val df = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        holder.binding.tvDate.text = df.format(Date(item.date))
        
        val staffText = if (item.affectedEmployeeIds.isEmpty()) {
            context.getString(R.string.all_staff)
        } else {
            val names = allEmployees.filter { it.employeeId in item.affectedEmployeeIds }.map { it.name }
            if (names.size == allEmployees.size) context.getString(R.string.all_staff) else names.joinToString(", ")
        }
        
        holder.binding.tvReason.text = context.getString(R.string.reason_with_staff, item.reason, staffText)
        holder.binding.tvSalaryStatus.text = if (item.paySalary) {
            context.getString(R.string.salary_yes)
        } else {
            context.getString(R.string.salary_no)
        }
        
        holder.binding.btnDelete.setOnClickListener { onDelete(item) }
    }

    class ClosedDayDiffCallback : DiffUtil.ItemCallback<ShopClosedDay>() {
        override fun areItemsTheSame(oldItem: ShopClosedDay, newItem: ShopClosedDay): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ShopClosedDay, newItem: ShopClosedDay): Boolean {
            return oldItem == newItem
        }
    }
}
