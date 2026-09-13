package com.biometric.app.ui.selfservice

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.biometric.app.api.MobileApiService
import com.biometric.app.data.MobileSessionStore
import com.biometric.app.sync.SignalRManager
import com.biometric.app.databinding.FragmentProfileBinding
import com.biometric.app.databinding.ItemProfileDetailBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    @Inject
    lateinit var mobileApi: MobileApiService

    @Inject
    lateinit var sessionStore: MobileSessionStore

    @Inject
    lateinit var signalR: SignalRManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(
            inflater,
            container,
            false
        )
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(
            view,
            savedInstanceState
        )

        loadProfile()
        setupRealTimeSync()
    }

    private fun loadProfile() {
        val token = sessionStore.token() ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = mobileApi.dashboard(
                    "Bearer $token"
                )

                if (!isAdded || _binding == null) {
                    return@launch
                }

                if (response.isSuccessful) {

                    response.body()?.let { data ->

                        binding.tvProfileName.text =
                            "${data.name} 👤"

                        binding.tvProfileRole.text =
                            "${data.role ?: "Staff Member"} 👔"

                        setDetail(
                            binding.detailEmpId,
                            "🆔 Employee ID",
                            data.employeeId.toString()
                        )

                        setDetail(
                            binding.detailEmail,
                            "✉️ Official Email",
                            data.email
                        )

                        setDetail(
                            binding.detailHireDate,
                            "🗓️ Joining Date",
                            data.hireDate ?: "--"
                        )

                        val shift =
                            if (data.shiftStartTime != null) {
                                "${data.shiftStartTime} - " +
                                        "${data.shiftEndTime}"
                            } else {
                                "General Shift"
                            }

                        setDetail(
                            binding.detailShift,
                            "🕒 Assigned Shift",
                            shift
                        )

                        val salary =
                            NumberFormat
                                .getCurrencyInstance(
                                    Locale.forLanguageTag("en-IN")
                                )
                                .format(data.monthlySalary)

                        setDetail(
                            binding.detailSalary,
                            "💰 Gross Salary",
                            salary
                        )

                        setDetail(
                            binding.detailBank,
                            "🏦 Bank Name",
                            data.bankName ?: "--"
                        )

                        setDetail(
                            binding.detailIfsc,
                            "🔢 IFSC Code",
                            data.bankIfscCode ?: "--"
                        )

                        setDetail(
                            binding.detailUan,
                            "🛡️ PF UAN",
                            data.uan ?: "Not Enrolled"
                        )

                        setDetail(
                            binding.detailEsi,
                            "🏥 ESI Number",
                            data.esiNumber ?: "Not Enrolled"
                        )
                    }

                } else {

                    Toast.makeText(
                        requireContext(),
                        "Unable to load profile",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            } catch (e: Exception) {

                if (isAdded && _binding != null) {
                    Toast.makeText(
                        requireContext(),
                        "Error loading profile",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun setDetail(
        includeBinding: ItemProfileDetailBinding,
        label: String,
        value: String
    ) {
        includeBinding.tvLabel.text = label
        includeBinding.tvValue.text = value
    }

    @OptIn(FlowPreview::class)
    private fun setupRealTimeSync() {
        viewLifecycleOwner.lifecycleScope.launch {
            signalR.dataChangeEvents
                .debounce(500L)
                .collect {
                    if (isAdded && _binding != null) {
                        loadProfile()
                    }
                }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}