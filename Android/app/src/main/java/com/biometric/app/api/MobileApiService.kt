package com.biometric.app.api

import com.google.gson.annotations.SerializedName
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.DELETE
import retrofit2.http.Path
import retrofit2.http.Query

interface MobileApiService {
    @POST("api/mobile/employee/login")
    suspend fun login(@Body request: MobileLoginRequest): Response<MobileLoginResponse>

    @GET("api/mobile/employee/me")
    suspend fun me(@Header("Authorization") authorization: String): Response<MobileLoginResponse>

    @POST("api/mobile/employee/logout")
    suspend fun logout(@Header("Authorization") authorization: String): Response<Unit>

    @POST("api/mobile/employee/gps/start")
    suspend fun startGps(
        @Header("Authorization") authorization: String,
        @Body request: GpsSessionRequest
    ): Response<GpsSessionResponse>

    @POST("api/mobile/employee/gps/update")
    suspend fun updateGps(
        @Header("Authorization") authorization: String,
        @Body request: GpsUpdateRequest
    ): Response<GpsUpdateResponse>


    @GET("api/mobile/employee/punch-status")
    suspend fun punchStatus(@Header("Authorization") authorization: String): Response<PunchStatusDto>

    @POST("api/mobile/employee/punch")
    suspend fun punch(@Header("Authorization") authorization: String, @Body request: EmployeePunchRequest): Response<PunchStatusDto>

    @GET("api/mobile/employee/dashboard")
    suspend fun dashboard(@Header("Authorization") authorization: String): Response<EmployeeDashboardResponse>

    @GET("api/mobile/employee/company-settings")
    suspend fun getCompanySettings(@Header("Authorization") authorization: String): Response<CompanySettingsResponse>

    @GET("api/mobile/employee/attendance")
    suspend fun attendance(
        @Header("Authorization") authorization: String,
        @Query("from") from: String,
        @Query("to") to: String
    ): Response<List<AttendanceDayDto>>

    @GET("api/mobile/employee/payslips")
    suspend fun payslips(@Header("Authorization") authorization: String): Response<List<PayslipDto>>

    @GET("api/mobile/employee/payslips/{id}/pdf")
    suspend fun downloadPayslipPdf(@Header("Authorization") authorization: String, @Path("id") id: Int): Response<ResponseBody>

    @GET("api/mobile/employee/leaves")
    suspend fun leaves(@Header("Authorization") authorization: String): Response<List<LeaveDto>>

    @POST("api/mobile/employee/leaves")
    suspend fun createLeave(@Header("Authorization") authorization: String, @Body request: LeaveCreateRequest): Response<LeaveDto>

    @GET("api/mobile/employee/advances")
    suspend fun advances(@Header("Authorization") authorization: String): Response<List<MoneyEntryDto>>

    @POST("api/mobile/employee/advances")
    suspend fun createAdvance(@Header("Authorization") authorization: String, @Body request: AdvanceCreateRequest): Response<MoneyEntryDto>

    @GET("api/mobile/employee/bonuses")
    suspend fun bonuses(@Header("Authorization") authorization: String): Response<List<MoneyEntryDto>>

    @GET("api/mobile/employee/regularizations")
    suspend fun regularizations(@Header("Authorization") authorization: String): Response<List<RegularizationDto>>

    @POST("api/mobile/employee/regularizations")
    suspend fun createRegularization(@Header("Authorization") authorization: String, @Body request: RegularizationCreateRequest): Response<RegularizationDto>

    @GET("api/mobile/employee/resignation")
    suspend fun resignation(@Header("Authorization") authorization: String): Response<ResignationDto?>

    @POST("api/mobile/employee/resignation")
    suspend fun createResignation(@Header("Authorization") authorization: String, @Body request: ResignationCreateRequest): Response<ResignationDto>

    @GET("api/mobile/employee/tax")
    suspend fun tax(@Header("Authorization") authorization: String, @Query("financialYear") financialYear: Int): Response<TaxDeclarationDto?>

    @POST("api/mobile/employee/tax")
    suspend fun saveTax(@Header("Authorization") authorization: String, @Body request: TaxDeclarationRequest): Response<TaxDeclarationDto>

    @GET("api/mobile/employee/fbp")
    suspend fun fbp(@Header("Authorization") authorization: String, @Query("financialYear") financialYear: Int): Response<List<FbpDto>>

    @POST("api/mobile/employee/fbp")
    suspend fun saveFbp(@Header("Authorization") authorization: String, @Body request: FbpRequest): Response<FbpDto>

    @GET("api/mobile/employee/shifts")
    suspend fun shifts(@Header("Authorization") authorization: String, @Query("month") month: String): Response<List<ShiftDto>>

    @POST("api/mobile/employee/gps/end")
    suspend fun endGps(
        @Header("Authorization") authorization: String,
        @Body request: GpsSessionRequest
    ): Response<Unit>

    @GET("api/mobile/employee/theme")
    suspend fun getTheme(@Header("Authorization") authorization: String): Response<ThemePreferenceDto>

    @PUT("api/mobile/employee/theme")
    suspend fun saveTheme(
        @Header("Authorization") authorization: String,
        @Body request: ThemePreferenceRequest
    ): Response<ThemePreferenceDto>

    @POST("api/mobile/realtime/changed")
    suspend fun notifyRealtimeChanged(
        @Header("Authorization") authorization: String,
        @Body request: RealtimeChangedRequest
    ): Response<Unit>

    @GET("api/mobile/admin/live-locations")
    suspend fun getAdminLiveLocations(
        @Header("Authorization") authorization: String
    ): Response<List<AdminLiveLocationDto>>

    @GET("api/mobile/admin/feature-settings")
    suspend fun getAdminFeatureSettings(@Header("Authorization") authorization: String): Response<AdminFeatureSettingsDto>

    @retrofit2.http.PUT("api/mobile/admin/feature-settings")
    suspend fun saveAdminFeatureSettings(@Header("Authorization") authorization: String, @Body request: AdminFeatureSettingsDto): Response<AdminFeatureSettingsDto>

    @GET("api/mobile/admin/payroll/history")
    suspend fun adminPayrollHistory(
        @Header("Authorization") authorization: String,
        @Query("year") year: Int,
        @Query("month") month: Int
    ): Response<AdminPayrollHistoryResponse>

    @POST("api/mobile/admin/payroll/preview")
    suspend fun adminPayrollPreview(
        @Header("Authorization") authorization: String,
        @Body request: AdminPayrollPeriodRequest
    ): Response<AdminPayrollPreviewResponse>

    @POST("api/mobile/admin/payroll/finalize")
    suspend fun adminPayrollFinalize(
        @Header("Authorization") authorization: String,
        @Body request: AdminPayrollFinalizeRequest
    ): Response<AdminPayrollActionResponse>

    @GET("api/mobile/admin/attendance/daily")
    suspend fun adminAttendanceDaily(@Header("Authorization") authorization: String, @Query("from") from: String, @Query("to") to: String, @Query("employeeId") employeeId: Int = 0): Response<AdminAttendanceResponse>

    @GET("api/mobile/admin/attendance/company-summary")
    suspend fun adminCompanyAttendanceSummary(@Header("Authorization") authorization: String, @Query("from") from: String, @Query("to") to: String): Response<AdminCompanyAttendanceResponse>

    @GET("api/mobile/admin/leaves")
    suspend fun adminLeaves(@Header("Authorization") authorization: String, @Query("employeeId") employeeId: Int = 0, @Query("status") status: String = "Pending", @Query("from") from: String? = null, @Query("to") to: String? = null): Response<List<AdminLeaveDto>>

    @POST("api/mobile/admin/leaves")
    suspend fun createAdminLeave(@Header("Authorization") authorization: String, @Body request: CreateAdminLeaveRequest): Response<AdminLeaveDto>

    @PUT("api/mobile/admin/leaves/{id}/status")
    suspend fun setAdminLeaveStatus(@Header("Authorization") authorization: String, @Path("id") id: Int, @Body request: AdminLeaveStatusRequest): Response<Unit>

    @DELETE("api/mobile/admin/leaves/{id}")
    suspend fun deleteAdminLeave(@Header("Authorization") authorization: String, @Path("id") id: Int): Response<Unit>

    @GET("api/mobile/admin/shifts")
    suspend fun adminShifts(@Header("Authorization") authorization: String, @Query("employeeId") employeeId: Int = 0, @Query("from") from: String? = null, @Query("to") to: String? = null): Response<List<AdminShiftDto>>

    @POST("api/mobile/admin/shifts")
    suspend fun createAdminShift(@Header("Authorization") authorization: String, @Body request: CreateAdminShiftRequest): Response<Unit>

    @DELETE("api/mobile/admin/shifts/{id}")
    suspend fun deleteAdminShift(@Header("Authorization") authorization: String, @Path("id") id: Int): Response<Unit>

    @POST("api/mobile/admin/shifts/generate")
    suspend fun generateAdminShifts(@Header("Authorization") authorization: String): Response<GenerateShiftsResponse>

    @POST("api/mobile/admin/workforce/leave")
    suspend fun adminCreateLeave(@Header("Authorization") authorization: String, @Body request: AdminLeaveCreateRequest): Response<BasicAdminResponse>

    @POST("api/mobile/admin/workforce/leave/{id}/status")
    suspend fun adminUpdateLeave(@Header("Authorization") authorization: String, @Path("id") id: Int, @Body request: AdminLeaveStatusRequest): Response<BasicAdminResponse>

    @DELETE("api/mobile/admin/workforce/leave/{id}")
    suspend fun adminDeleteLeave(@Header("Authorization") authorization: String, @Path("id") id: Int): Response<BasicAdminResponse>

    @POST("api/mobile/admin/workforce/shifts")
    suspend fun adminCreateShift(@Header("Authorization") authorization: String, @Body request: AdminShiftRequest): Response<BasicAdminResponse>

    @PUT("api/mobile/admin/workforce/shifts/{id}")
    suspend fun adminUpdateShift(@Header("Authorization") authorization: String, @Path("id") id: Int, @Body request: AdminShiftRequest): Response<BasicAdminResponse>

    @DELETE("api/mobile/admin/workforce/shifts/{id}")
    suspend fun adminDeleteShift(@Header("Authorization") authorization: String, @Path("id") id: Int): Response<BasicAdminResponse>

    @POST("api/mobile/admin/workforce/shifts/generate")
    suspend fun adminGenerateShifts(@Header("Authorization") authorization: String, @Body request: GenerateShiftsRequest): Response<GenerateShiftsResponse>

    @GET("api/mobile/admin/punches/issues")
    suspend fun adminPunchIssues(@Header("Authorization") authorization: String, @Query("employeeId") employeeId: Int = 0, @Query("from") from: String? = null, @Query("to") to: String? = null): Response<List<AdminPunchIssueDto>>

    @GET("api/mobile/admin/punches/pending")
    suspend fun adminPendingPunches(@Header("Authorization") authorization: String): Response<List<AdminPendingPunchDto>>

    @POST("api/mobile/admin/punches/manual")
    suspend fun adminAddPunch(@Header("Authorization") authorization: String, @Body request: AdminPunchMutationRequest): Response<BasicAdminResponse>

    @POST("api/mobile/admin/punches/manual/full-day")
    suspend fun adminAddFullDay(@Header("Authorization") authorization: String, @Body request: AdminFullDayPunchRequest): Response<BasicAdminResponse>

    @PUT("api/mobile/admin/punches/manual/{id}")
    suspend fun adminEditPunch(@Header("Authorization") authorization: String, @Path("id") id: Int, @Body request: AdminEditPunchRequest): Response<BasicAdminResponse>

    @DELETE("api/mobile/admin/punches/manual/{id}")
    suspend fun adminDeletePunch(@Header("Authorization") authorization: String, @Path("id") id: Int): Response<BasicAdminResponse>

    @POST("api/mobile/admin/punches/pending/{id}/approve")
    suspend fun adminApprovePunch(@Header("Authorization") authorization: String, @Path("id") id: Int): Response<BasicAdminResponse>

    @POST("api/mobile/admin/punches/pending/{id}/reject")
    suspend fun adminRejectPunch(@Header("Authorization") authorization: String, @Path("id") id: Int): Response<BasicAdminResponse>

    @GET("api/mobile/admin/finance/advances")
    suspend fun adminAdvances(@Header("Authorization") authorization: String, @Query("unpaidOnly") unpaidOnly: Boolean = false): Response<List<MoneyEntryDto>>

    @POST("api/mobile/admin/finance/advances")
    suspend fun createAdminAdvance(@Header("Authorization") authorization: String, @Body request: AdminAdvanceRequest): Response<MoneyEntryDto>

    @DELETE("api/mobile/admin/finance/advances/{id}")
    suspend fun deleteAdminAdvance(@Header("Authorization") authorization: String, @Path("id") id: Int): Response<Unit>

    @GET("api/mobile/admin/finance/bonuses")
    suspend fun adminBonuses(@Header("Authorization") authorization: String): Response<List<MoneyEntryDto>>

    @POST("api/mobile/admin/finance/bonuses")
    suspend fun createAdminBonus(@Header("Authorization") authorization: String, @Body request: AdminBonusRequest): Response<MoneyEntryDto>

    @GET("api/mobile/admin/finance/tax-declarations")
    suspend fun adminTaxDeclarations(@Header("Authorization") authorization: String, @Query("financialYear") financialYear: Int): Response<List<TaxDeclarationDto>>

    @POST("api/mobile/admin/finance/tax-declarations/{id}/approve")
    suspend fun approveAdminTax(@Header("Authorization") authorization: String, @Path("id") id: Int, @Body request: AdminRemarkRequest): Response<Unit>

    @POST("api/mobile/admin/finance/tax-declarations/{id}/reject")
    suspend fun rejectAdminTax(@Header("Authorization") authorization: String, @Path("id") id: Int, @Body request: AdminRemarkRequest): Response<Unit>

    @GET("api/mobile/admin/finance/fbp/components")
    suspend fun adminFbpComponents(@Header("Authorization") authorization: String): Response<List<FbpComponentDto>>

    @POST("api/mobile/admin/finance/fbp/components")
    suspend fun saveAdminFbpComponent(@Header("Authorization") authorization: String, @Body request: FbpComponentDto): Response<FbpComponentDto>

    @GET("api/mobile/admin/finance/fbp/declarations")
    suspend fun adminFbpDeclarations(@Header("Authorization") authorization: String, @Query("employeeId") employeeId: Int, @Query("financialYear") financialYear: Int): Response<List<FbpDto>>

    @GET("api/mobile/admin/finance/exit")
    suspend fun adminExitRequests(@Header("Authorization") authorization: String): Response<List<ResignationDto>>

    @POST("api/mobile/admin/finance/exit/{id}/status")
    suspend fun updateAdminExit(@Header("Authorization") authorization: String, @Path("id") id: Int, @Body request: ExitStatusRequest): Response<Unit>

    @POST("api/mobile/admin/finance/exit/{id}/calculate-settlement")
    suspend fun calculateAdminSettlement(@Header("Authorization") authorization: String, @Path("id") id: Int): Response<Any>

    @POST("api/mobile/admin/finance/exit/settlements/finalize")
    suspend fun finalizeAdminSettlement(@Header("Authorization") authorization: String, @Body request: Any): Response<Unit>

    @GET("api/mobile/admin/finance/year-end")
    suspend fun adminYearEnd(@Header("Authorization") authorization: String, @Query("year") year: Int): Response<List<Any>>

    @POST("api/mobile/admin/finance/year-end/consolidate")
    suspend fun consolidateAdminYearEnd(@Header("Authorization") authorization: String, @Query("year") year: Int): Response<Unit>
}

data class MobileLoginRequest(
    @SerializedName("Email") val email: String,
    @SerializedName("Password") val password: String,
    @SerializedName("DeviceId") val deviceId: String,
    @SerializedName("EmployeeId") val employeeId: String = "",
    @SerializedName("ForceReplace") val forceReplace: Boolean = false
)

data class MobileLoginResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("token") val token: String? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("employeeId") val employeeId: Int = 0,
    @SerializedName("name") val name: String = "",
    @SerializedName("email") val email: String = "",
    @SerializedName("role") val role: String? = null,
    @SerializedName("monthlySalary") val monthlySalary: Double = 0.0,
    @SerializedName("paidLeaveBalance") val paidLeaveBalance: Double = 0.0,
    @SerializedName("sickLeaveBalance") val sickLeaveBalance: Double = 0.0
)

data class GpsSessionRequest(val sessionId: String)

data class GpsSessionResponse(
    val success: Boolean = false,
    val sessionId: String? = null
)

data class GpsUpdateRequest(
    val sessionId: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Double,
    val speed: Double,
    val timestamp: Long,
    val batteryLevel: Int,
    /** Client-generated id used for enterprise idempotency on the server. */
    val clientEventId: String? = null,
    /** Monotonic GPS sequence within the mobile tracking session. */
    val sequence: Long? = null
)

data class GpsUpdateResponse(
    val success: Boolean = false,
    val employeeId: Int = 0,
    val distanceMeters: Double = 0.0,
    val allowedRadiusMeters: Int = 0,
    val isWithinAllowedRadius: Boolean = false,
    val timestamp: String? = null
)

data class AdminLiveLocationDto(
    @SerializedName("employeeId") val employeeId: Int = 0,
    @SerializedName("latitude") val latitude: Double = 0.0,
    @SerializedName("longitude") val longitude: Double = 0.0,
    @SerializedName("accuracyMeters") val accuracyMeters: Double = 0.0,
    @SerializedName("distanceMeters") val distanceMeters: Double = 0.0,
    @SerializedName("allowedRadiusMeters") val allowedRadiusMeters: Int = 0,
    @SerializedName("isWithinAllowedRadius") val isWithinAllowedRadius: Boolean = false,
    @SerializedName("lastUpdatedUtc") val lastUpdatedUtc: String? = null,
    @SerializedName("sessionStartedUtc") val sessionStartedUtc: String? = null,
    @SerializedName("sessionId") val sessionId: String? = null,
    @SerializedName("speedMps") val speedMps: Double = 0.0,
    @SerializedName("movementState") val movementState: String = "Stopped"
)

data class AdminFeatureSettingsDto(
    @SerializedName("enablePayroll") var enablePayroll: Boolean = true,
    @SerializedName("enableSalaryAdvance") var enableSalaryAdvance: Boolean = true,
    @SerializedName("enableBonusManagement") var enableBonusManagement: Boolean = true,
    @SerializedName("enableSalaryStructuring") var enableSalaryStructuring: Boolean = false,
    @SerializedName("employeeCanViewAdvance") var employeeCanViewAdvance: Boolean = true,
    @SerializedName("employeeCanViewBonus") var employeeCanViewBonus: Boolean = true,
    @SerializedName("enableTdsDeduction") var enableTdsDeduction: Boolean = false,
    @SerializedName("enableShiftScheduling") var enableShiftScheduling: Boolean = false,
    @SerializedName("enableLeaveManagement") var enableLeaveManagement: Boolean = true,
    @SerializedName("enablePunchCorrection") var enablePunchCorrection: Boolean = true,
    @SerializedName("enableEmployeeManagement") var enableEmployeeManagement: Boolean = true,
    @SerializedName("enableCompanyReports") var enableCompanyReports: Boolean = true,
    @SerializedName("enableStatutoryCompliance") var enableStatutoryCompliance: Boolean = false,
    @SerializedName("adminCanViewDashboard") var adminCanViewDashboard: Boolean = true,
    @SerializedName("adminCanManageEmployees") var adminCanManageEmployees: Boolean = true,
    @SerializedName("adminCanViewAttendance") var adminCanViewAttendance: Boolean = true,
    @SerializedName("adminCanRunPayroll") var adminCanRunPayroll: Boolean = false,
    @SerializedName("adminCanEditSettings") var adminCanEditSettings: Boolean = false,
    @SerializedName("adminCanManageShifts") var adminCanManageShifts: Boolean = true,
    @SerializedName("adminCanManagePunchApprovals") var adminCanManagePunchApprovals: Boolean = true,
    @SerializedName("adminCanViewReports") var adminCanViewReports: Boolean = true,
    @SerializedName("employeeCanViewDashboard") var employeeCanViewDashboard: Boolean = true,
    @SerializedName("employeeCanViewPayslip") var employeeCanViewPayslip: Boolean = true,
    @SerializedName("employeeCanViewAttendance") var employeeCanViewAttendance: Boolean = true,
    @SerializedName("employeeCanViewLeave") var employeeCanViewLeave: Boolean = true,
    @SerializedName("employeeCanViewLeaveHistory") var employeeCanViewLeaveHistory: Boolean = true,
    @SerializedName("employeeToolsVisible") var employeeToolsVisible: Boolean = true,
    @SerializedName("showThemeToggle") var showThemeToggle: Boolean = true,
    @SerializedName("adminCanManageEmployeePermissions") var adminCanManageEmployeePermissions: Boolean = false,
    @SerializedName("enableProfessionalTax") var enableProfessionalTax: Boolean = false,
    @SerializedName("enableEmailNotifications") var enableEmailNotifications: Boolean = false,
    @SerializedName("enableLeaveAccrual") var enableLeaveAccrual: Boolean = true,
    @SerializedName("enableSandwichRule") var enableSandwichRule: Boolean = false,
    @SerializedName("enableShiftAllowance") var enableShiftAllowance: Boolean = false,
    @SerializedName("enableAuditLog") var enableAuditLog: Boolean = true,
    @SerializedName("employeeCanViewShifts") var employeeCanViewShifts: Boolean = true,
    @SerializedName("enableYearEndSummary") var enableYearEndSummary: Boolean = false,
    @SerializedName("enableRecycleBin") var enableRecycleBin: Boolean = false,
    @SerializedName("enableTaxDeclarations") var enableTaxDeclarations: Boolean = false,
    @SerializedName("enableGeoFencing") var enableGeoFencing: Boolean = true,
    @SerializedName("enableDualAttendance") var enableDualAttendance: Boolean = false,
    @SerializedName("enableAutomaticGeofencePunching") var enableAutomaticGeofencePunching: Boolean = false,
    @SerializedName("enableResignationModule") var enableResignationModule: Boolean = false,
    @SerializedName("employeeCanViewResignation") var employeeCanViewResignation: Boolean = true,
    @SerializedName("employeeCanViewTax") var employeeCanViewTax: Boolean = true,
    @SerializedName("enableCustomReporting") var enableCustomReporting: Boolean = false,
    @SerializedName("employeeCanViewReports") var employeeCanViewReports: Boolean = false,
    @SerializedName("enableRegularizationRequest") var enableRegularizationRequest: Boolean = true,
    @SerializedName("enableAutoShiftRotation") var enableAutoShiftRotation: Boolean = false,
    @SerializedName("enableFlexibleBenefits") var enableFlexibleBenefits: Boolean = false,
    @SerializedName("enableInAppNotifications") var enableInAppNotifications: Boolean = true
)

data class AdminPayrollPeriodRequest(val year: Int, val month: Int)

data class AdminPayrollFinalizeRequest(val year: Int, val month: Int, val rows: List<AdminPayrollRowDto>)

data class AdminPayrollPreviewResponse(val success: Boolean = false, val message: String? = null, val rows: List<AdminPayrollRowDto> = emptyList())

data class AdminPayrollHistoryResponse(val success: Boolean = false, val message: String? = null, val rows: List<AdminPayrollHistoryRowDto> = emptyList())

data class AdminPayrollActionResponse(val success: Boolean = false, val message: String? = null)

data class AdminPayrollRowDto(
    val employeeID: Int = 0, val employeeName: String? = null, val baseSalary: Double? = null,
    val hourlyRate: Double = 0.0, val earnedStandardHours: Double = 0.0, val earnedPay: Double = 0.0,
    val overtimeMinutes: Double = 0.0, val overtimePay: Double = 0.0, val penaltyMinutes: Double = 0.0,
    val penaltyDeduction: Double = 0.0, val advanceDeduction: Double = 0.0, val bonus: Double = 0.0,
    val totalShiftAllowance: Double = 0.0, val tdsDeduction: Double = 0.0, val basicSalary: Double = 0.0,
    val pfDeduction: Double = 0.0, val esiDeduction: Double = 0.0, val ptDeduction: Double = 0.0,
    val employerPfContribution: Double = 0.0, val employerEsiContribution: Double = 0.0,
    val isPfEnabled: Boolean = false, val isEsiEnabled: Boolean = false, val netPayable: Double = 0.0,
    val leaveDays: Int = 0, val absentDays: Int = 0
)

data class AdminPayrollHistoryRowDto(
    val payrollID: Int = 0, val employeeID: Int = 0, val employeeName: String? = null,
    val payMonth: Int = 0, val payYear: Int = 0, val baseSalary: Double? = null, val hourlyRate: Double = 0.0,
    val totalHoursWorked: Double = 0.0, val totalOvertimeMinutes: Double = 0.0, val totalPenaltyMinutes: Double = 0.0,
    val deductionsHours: Double = 0.0, val deductionsAdvance: Double = 0.0, val bonus: Double = 0.0,
    val tdsDeduction: Double = 0.0, val totalShiftAllowance: Double = 0.0, val basicComponent: Double = 0.0,
    val pfDeduction: Double = 0.0, val esiDeduction: Double = 0.0, val ptDeduction: Double = 0.0,
    val absentDays: Int = 0, val manualLeaveDays: Int = 0, val netSalary: Double? = null
)

data class AdminAttendanceResponse(val success: Boolean=false, val rows: List<AdminAttendanceRow> = emptyList(), val message: String?=null)

data class AdminAttendanceRow(val employeeID:Int=0, val employeeName:String="", val date:String="", val status:String="Absent", val workedHours:Double=0.0, val overtimeMinutes:Double=0.0, val penaltyMinutes:Double=0.0, val latenessMinutes:Double=0.0, val breakPenaltyMinutes:Double=0.0, val scheduledMinutes:Double=0.0, val punches:String="")

data class AdminCompanyAttendanceResponse(val success:Boolean=false, val summary:AdminCompanyAttendanceSummary?=null, val message:String?=null)

data class AdminCompanyAttendanceSummary(val totalEmployeesProcessed:Int=0, val totalScheduledMinutes:Double=0.0, val totalWorkedHours:Double=0.0, val totalOvertimeMinutes:Double=0.0, val totalPenaltyMinutes:Double=0.0, val totalLatenessMinutes:Double=0.0, val totalBreakPenaltyMinutes:Double=0.0)

data class AdminLeaveDto(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("employeeId") val employeeId: Int = 0,
    @SerializedName("employeeName") val employeeName: String = "",
    @SerializedName("leaveDate") val leaveDate: String? = null,
    @SerializedName("leaveType") val leaveType: String = "",
    @SerializedName("isHalfDay") val isHalfDay: Boolean = false,
    @SerializedName("approved") val approved: Boolean = false,
    @SerializedName("notes") val notes: String? = null
)

data class CreateAdminLeaveRequest(
    @SerializedName("employeeId") val employeeId: Int,
    @SerializedName("leaveDate") val leaveDate: String,
    @SerializedName("leaveType") val leaveType: String,
    @SerializedName("isHalfDay") val isHalfDay: Boolean = false,
    @SerializedName("notes") val notes: String? = null
)

data class AdminLeaveStatusRequest(
    @SerializedName("approved") val approved: Boolean,
    @SerializedName("remarks") val remarks: String? = null
)

data class AdminShiftDto(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("employeeId") val employeeId: Int = 0,
    @SerializedName("employeeName") val employeeName: String = "",
    @SerializedName("shiftDate") val shiftDate: String = "",
    @SerializedName("startTime") val startTime: String = "",
    @SerializedName("endTime") val endTime: String = "",
    @SerializedName("isRecurringPattern") val isRecurringPattern: Boolean = false,
    @SerializedName("patternDurationDays") val patternDurationDays: Int = 7,
    @SerializedName("dayOfWeek") val dayOfWeek: Int = 0
)

data class CreateAdminShiftRequest(
    @SerializedName("employeeId") val employeeId: Int,
    @SerializedName("shiftDate") val shiftDate: String,
    @SerializedName("startTime") val startTime: String,
    @SerializedName("endTime") val endTime: String,
    @SerializedName("isRecurringPattern") val isRecurringPattern: Boolean = false,
    @SerializedName("patternDurationDays") val patternDurationDays: Int = 7
)

data class GenerateShiftsResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("count") val count: Int = 0
)

data class BasicAdminResponse(val success: Boolean = false, val message: String? = null)

data class AdminPunchDto(val id: Int = 0, val employeeId: Int = 0, val punchTime: String = "", val logType: String = "", val deviceId: String = "", val isApproved: Boolean = true)

data class AdminPunchIssueDto(val employeeId: Int = 0, val employeeName: String = "", val date: String = "", val punches: List<AdminPunchDto> = emptyList())

data class AdminPendingPunchDto(val id: Int = 0, val employeeId: Int = 0, val employeeName: String = "", val punchTime: String = "", val logType: String = "", val deviceId: String = "")

data class AdminPunchMutationRequest(val employeeId: Int, val punchTime: String)

data class AdminFullDayPunchRequest(val employeeId: Int, val date: String, val startTime: String = "09:00", val endTime: String = "18:00")

data class AdminEditPunchRequest(val time: String)

data class AdminLeaveCreateRequest(val employeeId: Int, val leaveDate: String, val leaveType: String, val isHalfDay: Boolean, val notes: String? = null)

data class AdminShiftRequest(val employeeId: Int, val shiftDate: String, val startTime: String, val endTime: String, val isRecurringPattern: Boolean, val patternDurationDays: Int, val appliesToDayOfWeek: String? = null)

data class GenerateShiftsRequest(val startDate: String, val endDate: String)








data class RealtimeChangedRequest(
    val changes: List<RealtimeChangedItem>
)

data class RealtimeChangedItem(
    val entity: String,
    val action: String = "MODIFIED"
)


data class ThemePreferenceDto(
    @SerializedName("theme") val theme: String = "light"
)

data class ThemePreferenceRequest(
    @SerializedName("theme") val theme: String
)
