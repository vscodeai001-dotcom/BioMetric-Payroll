package com.biometric.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.data.entity.Employee
import com.biometric.app.data.entity.ShopClosedDay
import com.biometric.app.databinding.ItemClosedDayBinding
import com.biometric.app.databinding.ItemMonthGroupBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MonthData(
    val monthName: String,
    val monthIndex: Int,
    val holidays: List<ShopClosedDay>
)

class MonthGroupAdapter(
    private var allEmployees: List<Employee>,
    private val onDelete: (ShopClosedDay) -> Unit
) : ListAdapter<MonthData, MonthGroupAdapter.ViewHolder>(MonthDataDiffCallback()) {

    fun updateEmployees(employees: List<Employee>) {
        this.allEmployees = employees
        notifyDataSetChanged()
    }

    class ViewHolder(val binding: ItemMonthGroupBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMonthGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.tvMonthName.text = item.monthName
        
        holder.binding.llHolidaysContainer.removeAllViews()
        
        if (item.holidays.isEmpty()) {
            holder.binding.tvNoHolidays.visibility = View.VISIBLE
        } else {
            holder.binding.tvNoHolidays.visibility = View.GONE
            val inflater = LayoutInflater.from(holder.binding.root.context)
            item.holidays.forEach { holiday ->
                val itemBinding = ItemClosedDayBinding.inflate(inflater, holder.binding.llHolidaysContainer, false)
                
                val context = itemBinding.root.context
                val df = SimpleDateFormat("dd MMM", Locale.getDefault())
                itemBinding.tvDate.text = df.format(Date(holiday.date))
                
                val staffText = if (holiday.affectedEmployeeIds.isEmpty()) {
                    context.getString(R.string.all_staff)
                } else {
                    val names = allEmployees.filter { it.employeeId in holiday.affectedEmployeeIds }.map { it.name }
                    if (names.size == allEmployees.size) context.getString(R.string.all_staff) else names.joinToString(", ")
                }
                
                itemBinding.tvReason.text = context.getString(R.string.reason_with_staff, holiday.reason, staffText)
                itemBinding.tvSalaryStatus.text = if (holiday.paySalary) {
                    context.getString(R.string.salary_yes)
                } else {
                    context.getString(R.string.salary_no)
                }
                
                itemBinding.btnDelete.setOnClickListener { onDelete(holiday) }
                
                holder.binding.llHolidaysContainer.addView(itemBinding.root)
            }
        }
    }

    class MonthDataDiffCallback : DiffUtil.ItemCallback<MonthData>() {
        override fun areItemsTheSame(oldItem: MonthData, newItem: MonthData): Boolean {
            return oldItem.monthName == newItem.monthName
        }

        override fun areContentsTheSame(oldItem: MonthData, newItem: MonthData): Boolean {
            return oldItem == newItem
        }
    }
}
