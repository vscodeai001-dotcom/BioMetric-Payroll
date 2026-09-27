package com.biometric.app.ui.selfservice

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.biometric.app.R
import com.biometric.app.api.AttendanceDayDto
import com.biometric.app.data.repository.FirebaseEmployeeSelfServiceRepository
import com.biometric.app.databinding.DialogAttendanceDayDetailsBinding
import com.biometric.app.databinding.FragmentMyAttendanceBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class AttendanceLogsFragment : Fragment() {

    private var _binding: FragmentMyAttendanceBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var selfService: FirebaseEmployeeSelfServiceRepository

    private lateinit var adapter: AttendanceAdapter

    /** Currently displayed month. Starts at current month. */
    private var displayedMonth: YearMonth = YearMonth.now()

    private val isoFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMyAttendanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSwipeRefresh()
        setupMonthNavigation()
        loadAttendance()
        setupRealTimeSync()
    }

    private fun setupMonthNavigation() {
        binding.btnPrevMonth.setOnClickListener {
            displayedMonth = displayedMonth.minusMonths(1)
            loadAttendance()
        }
        binding.btnNextMonth.setOnClickListener {
            // Don't allow navigating into the future beyond the current month
            if (displayedMonth < YearMonth.now()) {
                displayedMonth = displayedMonth.plusMonths(1)
                loadAttendance()
            }
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener { loadAttendance() }
        binding.swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(requireContext(), R.color.colorPrimary)
        )
    }

    @OptIn(FlowPreview::class)
    private fun setupRealTimeSync() {
        viewLifecycleOwner.lifecycleScope.launch {
            selfService.changesFlow()
                .debounce(500L)
                .collect {
                    Log.d("AttendanceLogs", "Firebase real-time refresh 🛰️")
                    if (isAdded && _binding != null) loadAttendance()
                }
        }
    }

    private fun setupRecyclerView() {
        adapter = AttendanceAdapter { day -> if (isAdded) showDayDetails(day) }
        _binding?.let { b ->
            b.rvAttendance.layoutManager = LinearLayoutManager(requireContext())
            b.rvAttendance.adapter = adapter
        }
    }

    private fun showDayDetails(day: AttendanceDayDto) {
        val dialogBinding = DialogAttendanceDayDetailsBinding.inflate(layoutInflater)
        dialogBinding.tvDialogDate.text = "Punches for ${day.date} 🗓️"

        if (day.punches.isEmpty()) {
            dialogBinding.tvNoPunches.visibility = View.VISIBLE
            dialogBinding.svPunches.visibility = View.GONE
        } else {
            day.punches.forEach { punch ->
                val tv = TextView(requireContext()).apply {
                    text = "${punch.type}: ${punch.time}  (${punch.source})"
                    setPadding(16, 16, 16, 16)
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                }
                dialogBinding.llPunchesContainer.addView(tv)
                val divider = View(requireContext()).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(ContextCompat.getColor(context, R.color.divider))
                }
                dialogBinding.llPunchesContainer.addView(divider)
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun loadAttendance() {
        val firstDay = displayedMonth.atDay(1)
        val lastDay = minOf(displayedMonth.atEndOfMonth(), LocalDate.now())

        // Don't load if month is entirely in the future
        if (firstDay.isAfter(LocalDate.now())) return

        val fromDate = firstDay.format(isoFormatter)
        val toDate = lastDay.format(isoFormatter)

        _binding?.tvSummaryTitle?.text = displayedMonth.format(monthFormatter)

        // Disable next button if we're at the current month
        _binding?.btnNextMonth?.alpha = if (displayedMonth >= YearMonth.now()) 0.3f else 1.0f

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val logs = selfService.attendance(fromDate, toDate)
                // Sort newest first for easier reading
                val sorted = logs.sortedByDescending { it.date }
                _binding?.let { b ->
                    adapter.submitList(sorted)
                    updateSummary(logs)
                    b.swipeRefresh.isRefreshing = false
                }
            } catch (e: Exception) {
                Log.e("AttendanceLogs", "Firebase load failed: ${e.message}", e)
                _binding?.swipeRefresh?.isRefreshing = false
            }
        }
    }

    private fun updateSummary(logs: List<AttendanceDayDto>) {
        var presentCount = 0
        var absentCount = 0
        var totalWorkedSec = 0L
        var totalPenaltySec = 0L
        var totalOtSec = 0L

        logs.forEach { day ->
            when {
                day.status.contains("Present", true) ||
                day.status.contains("Missing", true) -> presentCount++
                day.status.contains("Absent", true) -> absentCount++
            }
            totalWorkedSec += (day.workedHours * 3600).toLong()
            totalPenaltySec += parseDurationToSeconds(day.penalty)
            totalOtSec += parseDurationToSeconds(day.overtime)
        }

        _binding?.let { b ->
            b.tvCountPresent.text = presentCount.toString()
            b.tvCountAbsent.text = absentCount.toString()
            b.tvTotalWorked.text = formatDurationFromSeconds(totalWorkedSec)
            b.tvTotalPenalty.text = formatDurationFromSeconds(totalPenaltySec)
            b.tvTotalOt.text = formatDurationFromSeconds(totalOtSec)
        }
    }

    private fun parseDurationToSeconds(duration: String?): Long {
        if (duration.isNullOrBlank()) return 0L
        return try {
            val parts = duration.split(":")
            when {
                parts.size == 3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()
                parts.size == 2 -> parts[0].toLong() * 60 + parts[1].toLong()
                else -> 0L
            }
        } catch (_: Exception) { 0L }
    }

    private fun formatDurationFromSeconds(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        return String.format(Locale.US, "%02d:%02d", h, m)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
