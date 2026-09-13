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
import com.biometric.app.R
import com.biometric.app.api.MobileApiService
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.databinding.FragmentMyLeavesBinding
import com.biometric.app.sync.SignalRManager
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class MyLeavesFragment : Fragment() {

    private var _binding: FragmentMyLeavesBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var mobileApi: MobileApiService
    @Inject lateinit var sessionStore: MobileSessionStore
    @Inject lateinit var signalR: SignalRManager

    private lateinit var adapter: LeavesAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMyLeavesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupTabs()
        loadLeaves()
        loadBalances()
        setupRealTimeSync()

        binding.fabApplyLeave.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.container, ApplyLeaveFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                if (tab?.position == 0) {
                    binding.llHistory.visibility = View.VISIBLE
                    binding.svBalances.visibility = View.GONE
                } else {
                    binding.llHistory.visibility = View.GONE
                    binding.svBalances.visibility = View.VISIBLE
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupRecyclerView() {
        adapter = LeavesAdapter()
        binding.rvLeaves.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLeaves.adapter = adapter
    }

    private fun loadLeaves() {
        val token = sessionStore.token() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = mobileApi.leaves("Bearer $token")
                _binding?.let { b ->
                    if (response.isSuccessful) {
                        adapter.submitList(response.body() ?: emptyList())
                    }
                }
            } catch (e: Exception) {
                Log.e("MyLeaves", "Load failed: ${e.message}")
                if (isAdded) {
                    Toast.makeText(requireContext(), "Unable to load leave requests. Please try again. ⚠️", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadBalances() {
        val token = sessionStore.token() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = mobileApi.dashboard("Bearer $token")
                _binding?.let { b ->
                    if (response.isSuccessful) {
                        response.body()?.let { dashboard ->
                            b.tvPaidLeaveVal.text = String.format(Locale.US, "%.2f / 12.00 💎", dashboard.paidLeaveBalance)
                            b.tvPaidSummary.text = String.format(Locale.US, "%.1f", dashboard.paidLeaveBalance)
                            b.progressPaid.progress = (dashboard.paidLeaveBalance / 12.0 * 100).toInt().coerceIn(0, 100)
                            
                            b.tvSickLeaveVal.text = String.format(Locale.US, "%.2f / 12.00 🛡️", dashboard.sickLeaveBalance)
                            b.tvSickSummary.text = String.format(Locale.US, "%.1f", dashboard.sickLeaveBalance)
                            b.progressSick.progress = (dashboard.sickLeaveBalance / 12.0 * 100).toInt().coerceIn(0, 100)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    @OptIn(FlowPreview::class)
    private fun setupRealTimeSync() {
        viewLifecycleOwner.lifecycleScope.launch {
            signalR.dataChangeEvents
                .debounce(500L)
                .collect { event ->
                    Log.d("MyLeavesFragment", "Real-time refresh: $event 🛰️")
                    if (isAdded && _binding != null) {
                        loadLeaves()
                        loadBalances()
                    }
                }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
