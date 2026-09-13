package com.biometric.app.ui.selfservice

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.api.PayslipDto
import com.biometric.app.databinding.ItemPayslipBinding
import java.text.DateFormatSymbols
import java.util.Locale

class PayslipAdapter(private val onViewClick: (PayslipDto) -> Unit) :
    ListAdapter<PayslipDto, PayslipAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPayslipBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemPayslipBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PayslipDto) {
            val monthName = if (item.month in 1..12) DateFormatSymbols().months[item.month - 1] else "Payroll"
            binding.tvMonthYear.text = "$monthName ${item.year} 🗓️"
            binding.tvAmount.text = "₹ ${String.format(Locale.US, "%,.2f", item.netSalary)} 💎"
            binding.tvHourlyRate.text = "₹ ${String.format(Locale.US, "%,.2f", item.hourlyRate)} 🕒"
            binding.tvTotalHours.text = String.format(Locale.US, "%.2f hrs ⚡", item.totalHoursWorked)
            binding.tvOtPay.text = "₹ ${String.format(Locale.US, "%,.2f", item.overtimePay)} 🔥"
            binding.btnDownload.text = "View 💎"
            binding.btnDownload.setOnClickListener { onViewClick(item) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<PayslipDto>() {
        override fun areItemsTheSame(oldItem: PayslipDto, newItem: PayslipDto): Boolean =
            oldItem.payrollId == newItem.payrollId
        override fun areContentsTheSame(oldItem: PayslipDto, newItem: PayslipDto): Boolean =
            oldItem == newItem
    }
}
