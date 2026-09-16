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
import com.biometric.app.databinding.FragmentMoneyListBinding
import com.biometric.app.data.repository.FirebaseEmployeeSelfServiceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class BonusesFragment : Fragment() {

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
        binding.tvSummaryLabel.text = "TOTAL BONUSES 🌟 🏆"
        binding.tvSubLabel.text = "Cumulative performance bonuses 💎"
        
        setupRecyclerView()
        loadBonuses()
        setupRealTimeSync()
    }

    private fun setupRealTimeSync() {
        viewLifecycleOwner.lifecycleScope.launch {
            selfService.changesFlow().collect {
                if (isAdded && _binding != null) loadBonuses()
            }
        }
    }

    private fun setupRecyclerView() {
        adapter = MoneyAdapter(isBonus = true)
        _binding?.let { b ->
            b.rvList.layoutManager = LinearLayoutManager(requireContext())
            b.rvList.adapter = adapter
        }
    }

    private fun loadBonuses() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bonuses = selfService.bonuses()
                _binding?.let { b ->
                    adapter.submitList(bonuses)
                    val total = bonuses.sumOf { it.amount }
                    b.tvTotalAmount.text = String.format(Locale.US, "₹ %,.2f", total)
                    if (bonuses.isEmpty()) {
                        b.tvEmpty.text = "No bonuses recorded yet 🎁 💎"
                        b.tvEmpty.visibility = View.VISIBLE
                    } else {
                        b.tvEmpty.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                Log.e("Bonuses", "Firebase load failed: ${e.message}", e)
                _binding?.let { b ->
                    b.tvEmpty.text = "Unable to load bonuses. Please try again. ⚠️"
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
