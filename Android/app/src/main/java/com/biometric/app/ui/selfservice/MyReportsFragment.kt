package com.biometric.app.ui.selfservice

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.biometric.app.R
import com.biometric.app.databinding.FragmentMyReportsBinding
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MyReportsFragment : Fragment() {

    private var _binding: FragmentMyReportsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMyReportsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTabs()
        showFragment("attendance")
    }

    private fun setupTabs() {
        _binding?.tabLayout?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showFragment("attendance")
                    1 -> showFragment("payslips")
                    2 -> showFragment("leaves")
                    3 -> showFragment("advances")
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun showFragment(type: String) {
        val fragment = when (type) {
            "attendance" -> AttendanceLogsFragment()
            "payslips" -> PayslipListFragment()
            "leaves" -> MyLeavesFragment()
            "advances" -> SalaryAdvancesFragment()
            else -> AttendanceLogsFragment()
        }

        childFragmentManager.beginTransaction()
            .replace(R.id.reportContainer, fragment)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
