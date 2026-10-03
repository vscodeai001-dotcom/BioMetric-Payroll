package com.biometric.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.biometric.app.R
import com.biometric.app.domain.attendance.EvaluatedPunchRecord
import com.biometric.app.domain.attendance.PunchSourceTier
import com.google.android.material.card.MaterialCardView

/**
 * ListAdapter for Raw Punch Inspector cards.
 * Each card shows all telemetry at a glance: direction, tier badge, result, employee, reason.
 */
class RawPunchAdapter(
    private val onInspect: (EvaluatedPunchRecord) -> Unit
) : ListAdapter<EvaluatedPunchRecord, RawPunchAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<EvaluatedPunchRecord>() {
            override fun areItemsTheSame(a: EvaluatedPunchRecord, b: EvaluatedPunchRecord) =
                a.punchId == b.punchId
            override fun areContentsTheSame(a: EvaluatedPunchRecord, b: EvaluatedPunchRecord) = a == b
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardRoot: MaterialCardView = view.findViewById(R.id.cardPunchItem)
        val tvTimestamp: TextView = view.findViewById(R.id.tvPunchTimestamp)
        val tvDate: TextView = view.findViewById(R.id.tvPunchDate)
        val tvAvatar: TextView = view.findViewById(R.id.tvPunchAvatar)
        val tvEmpName: TextView = view.findViewById(R.id.tvPunchEmpName)
        val tvEmpMeta: TextView = view.findViewById(R.id.tvPunchEmpMeta)
        val tvDirection: TextView = view.findViewById(R.id.tvPunchDirection)
        val tvTier: TextView = view.findViewById(R.id.tvPunchTier)
        val tvResult: TextView = view.findViewById(R.id.tvPunchResult)
        val tvReason: TextView = view.findViewById(R.id.tvPunchReason)
        val tvDeviceId: TextView = view.findViewById(R.id.tvPunchDeviceId)
        val btnInspect: View = view.findViewById(R.id.btnInspectPunch)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_raw_punch_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val punch = getItem(position)
        val ctx = holder.itemView.context

        holder.tvTimestamp.text = punch.timeStr
        holder.tvDate.text = punch.dateStr
        holder.tvAvatar.text = punch.employeeName.firstOrNull()?.uppercase() ?: "?"
        holder.tvEmpName.text = punch.employeeName
        holder.tvEmpMeta.text = "ID: #${punch.staffId}  •  ${punch.role ?: "Staff"}"

        // Direction badge
        val (dirEmoji, dirLabel, dirColor) = when {
            punch.isIn -> Triple("📥", "IN", R.color.punch_in_color)
            punch.isOut -> Triple("📤", "OUT", R.color.punch_out_color)
            else -> Triple("🔄", punch.rawDirection, R.color.grey_500)
        }
        holder.tvDirection.text = "$dirEmoji $dirLabel"
        holder.tvDirection.setTextColor(ContextCompat.getColor(ctx, dirColor))

        // Source tier badge
        val (tierEmoji, tierLabel) = when (punch.sourceTier) {
            PunchSourceTier.PhysicalMachine -> "📟" to "Physical Machine"
            PunchSourceTier.ManualAdmin -> "✍️" to "Manual Correction"
            PunchSourceTier.GeofenceAuto -> "📍" to "Android Geofence"
        }
        holder.tvTier.text = "$tierEmoji $tierLabel"

        // Result badge
        if (punch.isAccepted) {
            holder.tvResult.text = "✅ VALID"
            holder.tvResult.setTextColor(ContextCompat.getColor(ctx, R.color.punch_accepted_color))
            holder.cardRoot.strokeColor = ContextCompat.getColor(ctx, R.color.punch_accepted_color)
            holder.cardRoot.strokeWidth = 3
        } else {
            holder.tvResult.text = "🚫 SUPPRESSED"
            holder.tvResult.setTextColor(ContextCompat.getColor(ctx, R.color.punch_suppressed_color))
            holder.cardRoot.strokeColor = ContextCompat.getColor(ctx, R.color.punch_suppressed_color)
            holder.cardRoot.strokeWidth = 3
        }

        holder.tvReason.text = punch.reason
        holder.tvDeviceId.text = "🖥 ${punch.deviceId.ifBlank { "—" }}  |  🆔 ${punch.biometricId.take(16).ifBlank { "—" }}"

        holder.btnInspect.setOnClickListener { onInspect(punch) }
        holder.cardRoot.setOnClickListener { onInspect(punch) }
    }
}
