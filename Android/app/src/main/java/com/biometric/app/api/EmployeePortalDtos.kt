package com.biometric.app.api

import com.google.gson.annotations.SerializedName

data class EmployeeDashboardResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("employeeId") val employeeId: Int = 0,
    @SerializedName("name") val name: String = "",
    @SerializedName("email") val email: String = "",
    @SerializedName("monthlySalary") val monthlySalary: Double = 0.0,
    @SerializedName("paidLeaveBalance") val paidLeaveBalance: Double = 0.0,
    @SerializedName("sickLeaveBalance") val sickLeaveBalance: Double = 0.0,
    @SerializedName("latestPayslip") val latestPayslip: PayslipDto? = null,
    @SerializedName("officeLatitude") val officeLatitude: Double = 0.0,
    @SerializedName("officeLongitude") val officeLongitude: Double = 0.0,
    @SerializedName("geoRadiusMeters") val geoRadiusMeters: Int = 100,
    @SerializedName("enableGeoFencing") val enableGeoFencing: Boolean = true,
    @SerializedName("enableDualAttendance") val enableDualAttendance: Boolean = false,
    @SerializedName("enableAutomaticGeofencePunching") val enableAutomaticGeofencePunching: Boolean = false,
    
    // Extra Profile Fields
    @SerializedName("role") val role: String? = null,
    @SerializedName("dob") val dob: String? = null,
    @SerializedName("hireDate") val hireDate: String? = null,
    @SerializedName("shiftStartTime") val shiftStartTime: String? = null,
    @SerializedName("shiftEndTime") val shiftEndTime: String? = null,
    @SerializedName("uan") val uan: String? = null,
    @SerializedName("esiNumber") val esiNumber: String? = null,
    @SerializedName("bankName") val bankName: String? = null,
    @SerializedName("bankAccountNumber") val bankAccountNumber: String? = null,
    @SerializedName("bankIfscCode") val bankIfscCode: String? = null
)

data class CompanySettingsResponse(
    @SerializedName("companyName") val companyName: String = "",
    @SerializedName("addressLine1") val addressLine1: String = "",
    @SerializedName("cityStatePincode") val cityStatePincode: String = "",
    @SerializedName("salaryCalculationMethod") val salaryCalculationMethod: String = "Days in Month",
    @SerializedName("officeLatitude") val officeLatitude: Double = 0.0,
    @SerializedName("officeLongitude") val officeLongitude: Double = 0.0,
    @SerializedName("geoRadiusMeters") val geoRadiusMeters: Int = 1000,
    @SerializedName("zktecoIP") val zktecoIP: String? = null,
    @SerializedName("zktecoPort") val zktecoPort: Int = 4370,
    @SerializedName("zktecoMachineNumber") val zktecoMachineNumber: Int = 1,
    @SerializedName("workDayCutoffHour") val workDayCutoffHour: Int = 22,
    @SerializedName("endTimeGraceMinutes") val endTimeGraceMinutes: Int = 0,
    @SerializedName("lateGraceMinutes") val lateGraceMinutes: Int = 0,
    @SerializedName("enablePfEsiSystem") val enablePfEsiSystem: Boolean = false,
    @SerializedName("esiWageLimit") val esiWageLimit: Double = 21000.0,
    @SerializedName("basicSalaryPercentage") val basicSalaryPercentage: Double = 40.0,
    @SerializedName("employeePfPercentage") val employeePfPercentage: Double = 12.0,
    @SerializedName("employeeEsiPercentage") val employeeEsiPercentage: Double = 0.75,
    @SerializedName("employerPfPercentage") val employerPfPercentage: Double = 13.0,
    @SerializedName("employerEsiPercentage") val employerEsiPercentage: Double = 3.25,
    @SerializedName("enableProfessionalTax") val enableProfessionalTax: Boolean = false,
    @SerializedName("enableEmailNotifications") val enableEmailNotifications: Boolean = false,
    @SerializedName("smtpHost") val smtpHost: String? = null,
    @SerializedName("smtpPort") val smtpPort: Int = 587,
    @SerializedName("smtpUser") val smtpUser: String? = null,
    @SerializedName("smtpPass") val smtpPass: String? = null,
    @SerializedName("smtpFromEmail") val smtpFromEmail: String? = null,
    @SerializedName("enableSsl") val enableSsl: Boolean = true,
    @SerializedName("enableShiftAllowance") val enableShiftAllowance: Boolean = false,
    @SerializedName("enableLeaveAccrual") val enableLeaveAccrual: Boolean = false,
    @SerializedName("leaveAccrualRate") val leaveAccrualRate: Double = 1.5,
    @SerializedName("enableSandwichRule") val enableSandwichRule: Boolean = false,
    @SerializedName("enableLeaveManagement") val enableLeaveManagement: Boolean = false,
    @SerializedName("enableTdsDeduction") val enableTdsDeduction: Boolean = false
)

data class AttendanceDayDto(
    @SerializedName("date") val date: String = "",
    @SerializedName("status") val status: String = "",
    @SerializedName("scheduledHours") val scheduledHours: Double = 0.0,
    @SerializedName("workedHours") val workedHours: Double = 0.0,
    @SerializedName("overtime") val overtime: String = "00:00:00",
    @SerializedName("penalty") val penalty: String = "00:00:00",
    @SerializedName("punches") val punches: List<PunchDto> = emptyList()
)

data class PunchDto(
    @SerializedName("time") val time: String = "",
    @SerializedName("type") val type: String = "",
    @SerializedName("source") val source: String = "",
    @SerializedName("approved") val approved: Boolean = true
)

data class PayslipDto(
    @SerializedName("payrollId") val payrollId: Int = 0,
    @SerializedName("month") val month: Int = 0,
    @SerializedName("year") val year: Int = 0,
    @SerializedName("baseSalary") val baseSalary: Double = 0.0,
    @SerializedName("overtimePay") val overtimePay: Double = 0.0,
    @SerializedName("bonus") val bonus: Double = 0.0,
    @SerializedName("advanceDeduction") val advanceDeduction: Double = 0.0,
    @SerializedName("pfDeduction") val pfDeduction: Double = 0.0,
    @SerializedName("esiDeduction") val esiDeduction: Double = 0.0,
    @SerializedName("ptDeduction") val ptDeduction: Double = 0.0,
    @SerializedName("tdsDeduction") val tdsDeduction: Double = 0.0,
    @SerializedName("netSalary") val netSalary: Double = 0.0,
    @SerializedName("hourlyRate") val hourlyRate: Double = 0.0,
    @SerializedName("totalHoursWorked") val totalHoursWorked: Double = 0.0,
    @SerializedName("totalDeductions") val totalDeductions: Double = 0.0
)

data class LeaveDto(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("date") val date: String = "",
    @SerializedName("leaveType") val leaveType: String = "",
    @SerializedName("halfDay") val halfDay: Boolean = false,
    @SerializedName("approved") val approved: Boolean = false,
    @SerializedName("notes") val notes: String? = null
)

data class LeaveCreateRequest(
    @SerializedName("leaveDate") val leaveDate: String,
    @SerializedName("leaveType") val leaveType: String,
    @SerializedName("isHalfDay") val isHalfDay: Boolean = false,
    @SerializedName("notes") val notes: String? = null
)

data class AdvanceCreateRequest(
    @SerializedName("amount") val amount: Double,
    @SerializedName("reason") val reason: String
)

data class MoneyEntryDto(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("date") val date: String = "",
    @SerializedName("amount") val amount: Double = 0.0,
    @SerializedName("type") val type: String = "",
    @SerializedName("description") val description: String? = null,
    @SerializedName("paid") val paid: Boolean = false,
    // Admin finance lists need to identify which employee owns the entry.
    @SerializedName("employeeId") val employeeId: Int = 0
)

data class RegularizationDto(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("date") val date: String = "",
    @SerializedName("inPunch") val inPunch: Boolean = true,
    @SerializedName("punchTime") val punchTime: String = "",
    @SerializedName("reason") val reason: String = "",
    @SerializedName("status") val status: String = "Pending",
    @SerializedName("remarks") val remarks: String? = null,
    @SerializedName("canResubmit") val canResubmit: Boolean = false
)

data class RegularizationCreateRequest(
    @SerializedName("dateOfPunch") val dateOfPunch: String,
    @SerializedName("isInPunch") val isInPunch: Boolean,
    @SerializedName("punchTimeNew") val punchTimeNew: String,
    @SerializedName("reason") val reason: String
)

data class ResignationDto(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("submissionDate") val submissionDate: String = "",
    @SerializedName("desiredLastWorkingDay") val desiredLastWorkingDay: String = "",
    @SerializedName("reason") val reason: String = "",
    @SerializedName("status") val status: String = "Pending",
    @SerializedName("approvedLastWorkingDay") val approvedLastWorkingDay: String? = null,
    @SerializedName("adminRemarks") val adminRemarks: String? = null,
    @SerializedName("settled") val settled: Boolean = false
)

data class ResignationCreateRequest(
    @SerializedName("desiredLastWorkingDay") val desiredLastWorkingDay: String,
    @SerializedName("reason") val reason: String
)

data class TaxDeclarationDto(
    @SerializedName("declarationId") val declarationId: Int = 0,
    @SerializedName("financialYear") val financialYear: Int = 0,
    @SerializedName("regime") val regime: String = "New",
    @SerializedName("section80C") val section80C: Double = 0.0,
    @SerializedName("section80D") val section80D: Double = 0.0,
    @SerializedName("hraRentPaid") val hraRentPaid: Double = 0.0,
    @SerializedName("otherExemptions") val otherExemptions: Double = 0.0,
    @SerializedName("status") val status: String = "Pending",
    @SerializedName("adminRemarks") val adminRemarks: String? = null,
    // Optional because Employee Self-Service records historically did not expose this field.
    @SerializedName("employeeId") val employeeId: Int = 0
) {
    val totalInvestmentAmount: Double
        get() = section80C + section80D + hraRentPaid + otherExemptions
}

data class TaxDeclarationRequest(
    @SerializedName("financialYear") val financialYear: Int,
    @SerializedName("regime") val regime: String,
    @SerializedName("section80C") val section80C: Double,
    @SerializedName("section80D") val section80D: Double,
    @SerializedName("hraRentPaid") val hraRentPaid: Double,
    @SerializedName("otherExemptions") val otherExemptions: Double
)

data class FbpDto(
    @SerializedName("declarationId") val declarationId: Int = 0,
    @SerializedName("financialYear") val financialYear: Int = 0,
    @SerializedName("componentName") val componentName: String = "",
    @SerializedName("annualAllocatedAmount") val annualAllocatedAmount: Double = 0.0,
    @SerializedName("monthlyAllocatedAmount") val monthlyAllocatedAmount: Double = 0.0,
    @SerializedName("status") val status: String = "Draft",
    @SerializedName("adminRemarks") val adminRemarks: String? = null
)

data class FbpRequest(
    @SerializedName("financialYear") val financialYear: Int,
    @SerializedName("componentName") val componentName: String,
    @SerializedName("annualAllocatedAmount") val annualAllocatedAmount: Double
)

data class ShiftDto(
    @SerializedName("date") val date: String = "",
    @SerializedName("day") val day: String = "",
    @SerializedName("startTime") val startTime: String = "",
    @SerializedName("endTime") val endTime: String = "",
    @SerializedName("status") val status: String = "Scheduled"
)

data class PunchStatusDto(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("message") val message: String? = null,
    @SerializedName("lastType") val lastType: String? = null,
    @SerializedName("nextType") val nextType: String = "IN",
    @SerializedName("lastPunchTime") val lastPunchTime: String? = null
)

data class EmployeePunchRequest(
    @SerializedName("Type") val type: String,
    @SerializedName("Latitude") val latitude: Double,
    @SerializedName("Longitude") val longitude: Double,
    @SerializedName("Accuracy") val accuracy: Double
)
