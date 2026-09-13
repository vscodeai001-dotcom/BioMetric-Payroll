package com.biometric.app.ui.selfservice

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.api.AttendanceDayDto
import com.biometric.app.databinding.ItemAttendanceDayBinding
import java.util.*

class AttendanceAdapter(private val onDayClick: (AttendanceDayDto) -> Unit) : ListAdapter<AttendanceDayDto, AttendanceAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAttendanceDayBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, onDayClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemAttendanceDayBinding, private val onDayClick: (AttendanceDayDto) -> Unit) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AttendanceDayDto) {
            binding.root.setOnClickListener { onDayClick(item) }
            
            binding.tvDate.text = "🗓️ ${item.date}"
            
            val statusEmoji = when {
                item.status.contains("Present", true) -> "🟢"
                item.status.contains("Absent", true) -> "🔴"
                item.status.contains("Off", true) -> "🔵"
                else -> "⚪"
            }
            binding.tvStatus.text = "$statusEmoji ${item.status} 📊"
            
            binding.tvScheduled.text = "${formatDuration(item.scheduledHours)} 📋"
            binding.tvWorked.text = "${formatDuration(item.workedHours)} ⏱️"
            binding.tvPenalty.text = "${formatTimeString(item.penalty)} 📉"
            binding.tvOt.text = "${formatTimeString(item.overtime)} ⚡"
            
            val punchTimes = item.punches.joinToString(" / ") { it.time }
            binding.tvPunches.text = if (punchTimes.isNotBlank()) "📜 $punchTimes" else "📜 --"

            val context = binding.root.context
            binding.tvStatus.setTextColor(when {
                item.status.contains("Present", true) -> ContextCompat.getColor(context, R.color.green_700)
                item.status.contains("Absent", true) -> ContextCompat.getColor(context, R.color.red_700)
                item.status.contains("Weekly Off", true) -> ContextCompat.getColor(context, R.color.blue)
                else -> ContextCompat.getColor(context, R.color.text_secondary)
            })
        }

        private fun formatDuration(hours: Double): String {
            val totalSeconds = (hours * 3600).toInt()
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            return String.format(Locale.US, "%02d:%02d", h, m)
        }

        private fun formatTimeString(time: String?): String {
            if (time.isNullOrBlank()) return "00:00"
            return try {
                val parts = time.split(":")
                if (parts.size >= 2) "${parts[0]}:${parts[1]}" else time
            } catch (_: Exception) { "00:00" }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<AttendanceDayDto>() {
        override fun areItemsTheSame(oldItem: AttendanceDayDto, newItem: AttendanceDayDto): Boolean = oldItem.date == newItem.date
        override fun areContentsTheSame(oldItem: AttendanceDayDto, newItem: AttendanceDayDto): Boolean = oldItem == newItem
    }
}
