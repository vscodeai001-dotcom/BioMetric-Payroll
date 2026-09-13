package com.biometric.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.data.entity.Payslip
import com.biometric.app.databinding.ItemPayrollCardBinding
import java.util.*

class PayrollAdapter(
    private val items: List<Payslip>,
    private val onItemClick: (Payslip) -> Unit
) : RecyclerView.Adapter<PayrollAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPayrollCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemPayrollCardBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: Payslip) {
            binding.tvStaffName.text = item.employeeName
            binding.tvSalaryDetails.text = "Worked: ${item.workedDays} days | Net: ₹${String.format(Locale.getDefault(), "%.2f", item.breakdown.netPayable)}"
            
            binding.root.setOnClickListener { onItemClick(item) }
        }
    }
}
