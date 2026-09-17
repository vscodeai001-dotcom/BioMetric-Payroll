package com.biometric.app.ui.selfservice

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.biometric.app.databinding.FragmentShiftScheduleBinding
import com.biometric.app.api.ShiftDto
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.data.dao.LocalShiftScheduleDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class ShiftScheduleFragment : Fragment() {

    private var _binding: FragmentShiftScheduleBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var shiftScheduleDao: LocalShiftScheduleDao
    @Inject lateinit var sessionStore: MobileSessionStore

    private lateinit var adapter: ShiftAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentShiftScheduleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        loadShifts()
    }

    private fun setupRecyclerView() {
        adapter = ShiftAdapter()
        _binding?.let { b ->
            b.rvShifts.layoutManager = LinearLayoutManager(requireContext())
            b.rvShifts.adapter = adapter
        }
    }

    private fun loadShifts() {
        val employeeId = sessionStore.employeeId()
        if (employeeId <= 0) {
            binding.tvEmpty.text = "Employee session is unavailable. Please sign in again. ⚠️"
            binding.tvEmpty.visibility = View.VISIBLE
            return
        }

        val monthStart = java.time.LocalDate.now().withDayOfMonth(1)
        val monthEnd = monthStart.plusMonths(1).minusDays(1)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Firebase -> Room hydrator is the realtime/offline transport for
                // this read-only employee screen. Every Firebase child change is
                // projected into Room, so this Flow updates without reload.
                shiftScheduleDao.observeForEmployeeBetween(
                    employeeId,
                    monthStart.toString(),
                    monthEnd.toString()
                ).collectLatest { localRows ->
                    val shifts = localRows.mapNotNull { row ->
                        val date = runCatching { java.time.LocalDate.parse(row.shiftDate) }.getOrNull()
                            ?: return@mapNotNull null
                        ShiftDto(
                            date = row.shiftDate,
                            day = date.dayOfWeek.name.lowercase(Locale.US).replaceFirstChar { it.uppercase() },
                            startTime = row.startTime.take(5),
                            endTime = row.endTime.take(5),
                            status = if (row.isRecurringPattern) "Recurring" else "Scheduled"
                        )
                    }.sortedBy { it.date }

                    _binding?.let { b ->
                        adapter.submitList(shifts)
                        b.tvShiftSummary.text = "You have ${shifts.size} shifts scheduled for this month 🕒 📅."
                        if (shifts.isEmpty()) {
                            b.tvEmpty.text = "No shifts scheduled for this month 🕒 💎"
                            b.tvEmpty.visibility = View.VISIBLE
                        } else {
                            b.tvEmpty.visibility = View.GONE
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Shifts", "Shift schedule Flow failed: ${e.message}", e)
                _binding?.let { b ->
                    b.tvEmpty.text = "Unable to load shifts. Please try again. ⚠️"
                    b.tvEmpty.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
