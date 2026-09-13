package com.biometric.app.data.entity

import java.util.UUID

data class SalaryRules(
    var newJoineeCutoffDay: Int = 5,
    var maxAbsencesForPaidLeave: Int = 3,
    var maxAbsencesForBonus: Int = 0,
    var maxShortfallMinutesForBonus: Int = 6,
    var paidLeaveDaysPool: Int = 1,
    var defaultShiftStart: String = "10:00",
    var defaultShiftEnd: String = "22:00",
    var defaultShift2Start: String? = null,
    var defaultShift2End: String? = null,
    var defaultBreakHours: Double = 0.0,
    var defaultOtMultiplier: Double = 1.0,
    var isBonusEligibleDefault: Boolean = true,
    var isPaidLeaveEligibleDefault: Boolean = true,
    var paidLeaveOnWeekdaysDefault: Boolean = true,
    var paidLeaveOnWeekendsDefault: Boolean = false
)

data class Shop(
    var shopId: String = UUID.randomUUID().toString(),
    var name: String = "",
    var location: String = "",
    var openingDate: Long = System.currentTimeMillis(),
    var isActive: Boolean = true,
    var brandingName: String? = null,
    var brandingLogoUrl: String? = null,
    var salaryRules: SalaryRules = SalaryRules(),
    var createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var syncState: Int = 0,
    var lastModified: Long = System.currentTimeMillis(),
    var latitude: Double = 0.0,
    var longitude: Double = 0.0
) {
    constructor() : this(UUID.randomUUID().toString(), "", "", System.currentTimeMillis(), true, null, null, SalaryRules(), System.currentTimeMillis(), System.currentTimeMillis(), 0, System.currentTimeMillis(), 0.0, 0.0)
}
