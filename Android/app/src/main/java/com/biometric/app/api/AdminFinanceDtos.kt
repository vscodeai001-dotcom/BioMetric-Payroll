package com.biometric.app.api

data class AdminRemarkRequest(val remarks: String? = null)
data class ExitStatusRequest(val status: String, val approvedLastWorkingDay: String? = null, val remarks: String? = null)

data class AdminAdvanceRequest(val employeeID: Int, val amount: Double, val advanceType: String? = null, val advanceDate: String? = null)
data class AdminBonusRequest(val employeeID: Int, val amount: Double, val description: String? = null, val bonusDate: String? = null)
data class FbpComponentDto(val componentId: Int = 0, val name: String = "", val maxAnnualLimit: Double = 0.0, val isActive: Boolean = true, val isTaxExempt: Boolean = true)
