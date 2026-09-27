package com.biometric.app.ui.selfservice

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.api.AttendanceDayDto
import com.biometric.app.databinding.ItemAttendanceCardBinding
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.*

class AttendanceAdapter(private val onDayClick: (AttendanceDayDto) -> Unit) :
    ListAdapter<AttendanceDayDto, AttendanceAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAttendanceCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding, onDayClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(getItem(position))

    class ViewHolder(
        private val b: ItemAttendanceCardBinding,
        private val onDayClick: (AttendanceDayDto) -> Unit
    ) : RecyclerView.ViewHolder(b.root) {

        private val isoFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        private val displayFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)

        fun bind(item: AttendanceDayDto) {
            b.root.setOnClickListener { onDayClick(item) }

            // Parse date for display
            val localDate = runCatching { LocalDate.parse(item.date, isoFormatter) }.getOrNull()
            val dayNum = localDate?.dayOfMonth?.toString() ?: "--"
            val dayName = localDate?.dayOfWeek?.getDisplayName(TextStyle.FULL, Locale.US) ?: ""
            val displayDate = localDate?.format(displayFormatter) ?: item.date

            b.tvDayNum.text = dayNum
            b.tvDate.text = displayDate
            b.tvDayName.text = dayName

            // Status pill with color
            b.tvStatus.text = item.status
            val ctx = b.root.context
            val (pillColor, textColor) = when {
                item.status.contains("Present", true) -> 0xFF2E7D32.toInt() to 0xFFFFFFFF.toInt()
                item.status.contains("Missing", true) -> 0xFFE65100.toInt() to 0xFFFFFFFF.toInt()
                item.status.contains("Weekly Off", true) -> 0xFF1565C0.toInt() to 0xFFFFFFFF.toInt()
                item.status.contains("Holiday", true) -> 0xFF6A1B9A.toInt() to 0xFFFFFFFF.toInt()
                item.status.contains("Leave", true) -> 0xFF00838F.toInt() to 0xFFFFFFFF.toInt()
                item.status.contains("Absent", true) -> 0xFFC62828.toInt() to 0xFFFFFFFF.toInt()
                else -> 0xFF757575.toInt() to 0xFFFFFFFF.toInt()
            }
            (b.tvStatus.background as? GradientDrawable)?.setColor(pillColor)
                ?: b.tvStatus.setBackgroundColor(pillColor)
            b.tvStatus.setTextColor(textColor)

            // Day circle: highlight today
            val isToday = localDate == LocalDate.now()
            if (isToday) {
                b.tvDayNum.setBackgroundResource(R.drawable.bg_status_pill)
                (b.tvDayNum.background as? GradientDrawable)?.setColor(ContextCompat.getColor(ctx, R.color.colorPrimary))
                b.tvDayNum.setTextColor(0xFFFFFFFF.toInt())
            } else {
                b.tvDayNum.setBackgroundResource(R.drawable.bg_day_circle)
                b.tvDayNum.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            }

            // Stats
            b.tvScheduled.text = formatDuration(item.scheduledHours)
            b.tvWorked.text = formatDuration(item.workedHours)
            b.tvPenalty.text = formatTimeString(item.penalty)
            b.tvOt.text = formatTimeString(item.overtime)

            // Worked color: red if much less than scheduled
            val workedFraction = if (item.scheduledHours > 0) item.workedHours / item.scheduledHours else 1.0
            b.tvWorked.setTextColor(when {
                item.status.contains("Absent", true) -> ContextCompat.getColor(ctx, R.color.red_700)
                workedFraction < 0.5 && item.workedHours > 0 -> ContextCompat.getColor(ctx, R.color.red_700)
                else -> ContextCompat.getColor(ctx, R.color.text_primary)
            })

            // Punches row
            val punchText = item.punches.joinToString("   ") { p ->
                "${p.type}: ${p.time}"
            }
            if (punchText.isNotBlank()) {
                b.tvPunches.text = punchText
                b.layoutPunches.visibility = View.VISIBLE
            } else {
                b.layoutPunches.visibility = View.GONE
            }
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
        override fun areItemsTheSame(old: AttendanceDayDto, new: AttendanceDayDto) = old.date == new.date
        override fun areContentsTheSame(old: AttendanceDayDto, new: AttendanceDayDto) = old == new
    }
}
