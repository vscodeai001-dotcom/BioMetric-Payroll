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
import com.biometric.app.databinding.FragmentMoneyListBinding
import com.biometric.app.data.repository.FirebaseEmployeeSelfServiceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class SalaryAdvancesFragment : Fragment() {

    private var _binding: FragmentMoneyListBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var selfService: FirebaseEmployeeSelfServiceRepository

    private lateinit var adapter: MoneyAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMoneyListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvSummaryLabel.text = "TOTAL ADVANCES 💳 🏦"
        binding.tvSubLabel.text = "Cumulative salary advances 🛡️"
        binding.fabAction.visibility = View.VISIBLE
        binding.fabAction.text = "Request Advance ➕ 💸"
        
        setupRecyclerView()
        loadAdvances()
        setupRealTimeSync()
        
        binding.fabAction.setOnClickListener {
            requireActivity().supportFragmentManager.beginTransaction()
                .replace(R.id.container, ApplyAdvanceFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    private fun setupRecyclerView() {
        adapter = MoneyAdapter(isBonus = false)
        _binding?.let { b ->
            b.rvList.layoutManager = LinearLayoutManager(requireContext())
            b.rvList.adapter = adapter
        }
    }

    private fun loadAdvances() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val advances = selfService.advances()
                _binding?.let { b ->
                    adapter.submitList(advances)
                    val total = advances.sumOf { it.amount }
                    b.tvTotalAmount.text = String.format(Locale.US, "₹ %,.2f", total)
                    if (advances.isEmpty()) {
                        b.tvEmpty.text = "No advances recorded yet 💳 🛡️"
                        b.tvEmpty.visibility = View.VISIBLE
                    } else b.tvEmpty.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e("Advances", "Firebase load failed: ${e.message}", e)
                _binding?.let { b ->
                    b.tvEmpty.text = "Unable to load salary advances. Please try again. ⚠️"
                    b.tvEmpty.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setupRealTimeSync() {
        viewLifecycleOwner.lifecycleScope.launch {
            selfService.changesFlow().collect {
                if (isAdded && _binding != null) loadAdvances()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
