package com.biometric.app.ui.selfservice

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.biometric.app.api.MobileApiService
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.databinding.FragmentShiftScheduleBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class ShiftScheduleFragment : Fragment() {

    private var _binding: FragmentShiftScheduleBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var mobileApi: MobileApiService
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
        val token = sessionStore.token() ?: return
        val currentMonth = SimpleDateFormat("yyyy-MM", Locale.US).format(Date())
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = mobileApi.shifts("Bearer $token", currentMonth)
                _binding?.let { b ->
                    if (response.isSuccessful) {
                        val shifts = response.body() ?: emptyList()
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
                Log.e("Shifts", "Load failed: ${e.message}")
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
