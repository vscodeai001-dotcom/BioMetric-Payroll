package com.biometric.app.api

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class FnFSettlement(
    @SerializedName("settlementId") val settlementId: Int = 0,
    @SerializedName("employeeId") val employeeId: Int = 0,
    @SerializedName("resignationRequestId") val resignationRequestId: Int = 0,
    @SerializedName("settlementDate") val settlementDate: String? = null,
    
    // --- EARNINGS ---
    @SerializedName("unpaidSalary") var unpaidSalary: Double = 0.0,
    @SerializedName("leaveEncashment") var leaveEncashment: Double = 0.0,
    @SerializedName("gratuity") var gratuity: Double = 0.0,
    @SerializedName("bonusPayable") var bonusPayable: Double = 0.0,
    
    // --- DEDUCTIONS ---
    @SerializedName("noticePeriodRecovery") var noticePeriodRecovery: Double = 0.0,
    @SerializedName("assetRecoveryCost") var assetRecoveryCost: Double = 0.0,
    @SerializedName("outstandingAdvances") var outstandingAdvances: Double = 0.0,
    
    // --- NET ---
    @SerializedName("netPayable") var netPayable: Double = 0.0,
    @SerializedName("isFinalized") var isFinalized: Boolean = false,
    @SerializedName("paymentReference") var paymentReference: String? = null
)
