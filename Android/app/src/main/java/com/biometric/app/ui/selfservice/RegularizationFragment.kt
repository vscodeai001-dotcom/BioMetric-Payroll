package com.biometric.app.ui.selfservice

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.biometric.app.R
import com.biometric.app.api.MobileApiService
import com.biometric.app.api.RegularizationDto
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.databinding.FragmentMyRegularizationBinding
import com.biometric.app.sync.SignalRManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class RegularizationFragment : Fragment() {

    private var _binding: FragmentMyRegularizationBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var mobileApi: MobileApiService
    @Inject lateinit var sessionStore: MobileSessionStore
    @Inject lateinit var signalR: SignalRManager

    private lateinit var adapter: RegularizationAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMyRegularizationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        loadRegularizations()
        setupRealTimeSync()

        binding.fabNewRequest.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.container, ApplyRegularizationFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    private fun setupRecyclerView() {
        adapter = RegularizationAdapter()
        binding.rvRequests.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRequests.adapter = adapter
    }

    private fun loadRegularizations() {
        val token = sessionStore.token() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val requestsResponse = mobileApi.regularizations("Bearer $token")
                _binding?.let { b ->
                    if (requestsResponse.isSuccessful) {
                        val requests = requestsResponse.body() ?: emptyList()
                        adapter.submitList(requests)

                        val now = Date()
                        val monthStart = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
                            .let { sdf ->
                                val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
                                sdf.format(cal.time)
                            }
                        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
                        
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                val attendanceResponse = mobileApi.attendance("Bearer $token", monthStart, today)
                                _binding?.let {
                                    if (attendanceResponse.isSuccessful) {
                                        renderMissingPunches(attendanceResponse.body() ?: emptyList(), requests)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("RegularizationFragment", "Attendance load failed ⚠️", e)
                            }
                        }
                    } else {
                         Toast.makeText(requireContext(), "Server error: ${requestsResponse.code()} ❌", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("RegularizationFragment", "Failed to load corrections ⚠️", e)
                if (isAdded && _binding != null) {
                    Toast.makeText(requireContext(), "Failed to load corrections ⚠️", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @Suppress("SetTextI18n")
    private fun renderMissingPunches(days: List<com.biometric.app.api.AttendanceDayDto>, requests: List<RegularizationDto>) {
        val container = _binding?.missingPunchContainer ?: return
        container.removeAllViews()

        val missingDays = days.filter { it.status.equals("Missing Punch", true) }
            .filter { it.punches.isNotEmpty() }
            .sortedByDescending { it.date }

        if (missingDays.isEmpty()) {
            val empty = TextView(requireContext()).apply {
                text = "No historical missing punches detected this month 💎.\nDays with zero punches are treated as Absent."
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                textSize = 12f
                setPadding(16, 8, 16, 12)
            }
            container.addView(empty)
            return
        }

        missingDays.forEach { day ->
            val last = day.punches.lastOrNull()
            val missingIn = last?.type?.equals("OUT", true) == true
            val missingType = if (missingIn) "IN" else "OUT"
            val existing = requests.filter { it.date == day.date && it.inPunch == (missingType == "IN") }
                .maxByOrNull { it.id }

            val card = MaterialCardView(requireContext()).apply {
                radius = 12f
                cardElevation = 1f
                setContentPadding(14, 10, 10, 10)
                useCompatPadding = true
            }
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val info = TextView(requireContext()).apply {
                text = "🗓️ ${day.date}\nPunches: ${day.punches.joinToString { it.time.take(5) }}\nMissing: $missingType ⚠️"
                textSize = 12f
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(info)

            when {
                existing?.status.equals("Pending", true) -> row.addView(statusButton("Pending ⏳"))
                existing?.status.equals("Approved", true) -> row.addView(statusButton("Approved ✅"))
                existing?.status.equals("Rejected", true) && existing?.canResubmit == true -> {
                    val b = MaterialButton(requireContext()).apply { text = "Request Again 🛠️" }
                    b.setOnClickListener { openCorrection(day.date, missingType == "IN") }
                    row.addView(b)
                }
                existing?.status.equals("Rejected", true) -> row.addView(statusButton("Rejected ❌"))
                else -> {
                    val b = MaterialButton(requireContext()).apply { text = "Request 🛠️" }
                    b.setOnClickListener { openCorrection(day.date, missingType == "IN") }
                    row.addView(b)
                }
            }

            card.addView(row)
            container.addView(card)
        }
    }

    private fun statusButton(text: String): MaterialButton =
        MaterialButton(requireContext()).apply {
            this.text = text
            isEnabled = false
        }

    private fun openCorrection(date: String, isInPunch: Boolean) {
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(R.id.container, ApplyRegularizationFragment.newInstance(date, isInPunch))
            .addToBackStack(null)
            .commit()
    }

    @OptIn(FlowPreview::class)
    private fun setupRealTimeSync() {
        viewLifecycleOwner.lifecycleScope.launch {
            signalR.dataChangeEvents
                .debounce(500L)
                .collect { event ->
                    Log.d("RegularizationFragment", "Real-time refresh: $event 🛰️")
                    if (isAdded && _binding != null) {
                        loadRegularizations()
                    }
                }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
