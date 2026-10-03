package com.biometric.app.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room cache entity for Employee.
 *
 * SSOT: Firebase RTDB → FirebaseRoomHydrator → this table → MainRepository.toDomain() → UI.
 *
 * ALL fields from the domain Employee class must be persisted here so that
 * StaffActivity / AddStaffDialogFragment (edit mode) display the correct
 * SSOT data instead of empty / default values.
 *
 * Schema version bump: 25 (see DatabaseModule / AppDatabase)
 */
@Entity(tableName = "local_employees")
data class LocalEmployee(
    @PrimaryKey val employeeId: String,
    val shopId: String,
    val name: String,
    val role: String,
    val isActive: Boolean,

    // Payroll
    val salaryType: String = "MONTHLY_FIXED",
    val salaryRate: Double = 0.0,
    val basicSalaryComponent: Double = 0.0,
    val hraComponent: Double = 0.0,
    val daComponent: Double = 0.0,
    val standardHours: Int = 8,
    val salaryCalculationMethod: String = "Days in Month",
    val dailyAllowance: Double = 0.0,
    val nightShiftAllowance: Double = 0.0,
    val allowanceEffectiveDate: Long = 0L,

    // Shift
    val shiftStart: String = "10:00",
    val shiftEnd: String = "22:00",
    val shiftMode: String = "SINGLE_DAY",
    val trackingMode: String = "24/7",
    val breakHours: Double = 0.0,
    val shift2Start: String? = null,
    val shift2End: String? = null,
    val weekendShiftStart: String? = null,
    val weekendShiftEnd: String? = null,
    val weekendBreakHours: Double? = null,
    val weekendShift2Start: String? = null,
    val weekendShift2End: String? = null,
    val compOffDayOfWeek: Int? = null,

    // OT / Rules
    val otRule: String = "No Overtime",
    val otFlatRate: Double = 0.0,
    val otRateMultiplier: Double = 1.0,

    // Dates
    val hireDate: Long = 0L,
    val dob: Long? = null,
    val terminateDate: Long? = null,
    val createdAt: Long = 0L,

    // Contact / Identity
    val phone: String = "",
    val email: String? = null,
    val biometricId: String = "",

    // Leave / Bonus eligibility
    val isBonusEligibleRule: Boolean = true,
    val isPaidLeaveEligibleRule: Boolean = true,
    val paidLeaveOnWeekdays: Boolean = true,
    val paidLeaveOnWeekends: Boolean = false,
    val paidLeaveBalance: Double = 0.0,
    val sickLeaveBalance: Double = 0.0,

    // Shift Rotation
    val enableShiftRotation: Boolean = false,
    val rotationGroup: String? = null,
    val shiftRotationPattern: String? = null,

    // Statutory / Bank
    val enablePf: Boolean = false,
    val enableEsi: Boolean = false,
    val tdsRatePercent: Double = 0.0,
    val bankAccountNumber: String? = null,
    val bankIfscCode: String? = null,
    val bankName: String? = null,
    val uanNumber: String? = null,
    val esiNumber: String? = null,

    // Sync
    val syncState: Int = 0,
    val lastModified: Long = 0L
)
