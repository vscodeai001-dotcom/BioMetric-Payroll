package com.biometric.app.api

import com.biometric.app.data.entity.*
import com.biometric.app.BuildConfig
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface NeonApiService {

    @GET(".")
    suspend fun getRoot(
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY
    ): Response<ResponseBody>

    // --- SHOPS ---
    @GET("shops")
    suspend fun getShops(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY
    ): Response<List<Shop>>

    @POST("shops")
    suspend fun createShop(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY,
        @Body shop: Shop
    ): Response<Unit>

    @PATCH("shops")
    suspend fun updateShop(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY,
        @Query("shopId") id: String,
        @Body shop: Map<String, @JvmSuppressWildcards Any?>
    ): Response<Unit>

    // --- EMPLOYEES ---
    @GET("employees")
    suspend fun getEmployees(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY
    ): Response<List<Employee>>

    @POST("employees")
    suspend fun createEmployee(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY,
        @Body employee: Employee
    ): Response<Unit>

    // --- ATTENDANCE ---
    @GET("attendance")
    suspend fun getAttendance(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY
    ): Response<List<Attendance>>

    @POST("attendance")
    suspend fun createAttendance(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY,
        @Body attendance: Attendance
    ): Response<Unit>

    // --- ADVANCES ---
    @GET("advances")
    suspend fun getAdvances(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY
    ): Response<List<AdvancePayment>>

    @POST("advances")
    suspend fun createAdvance(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY,
        @Body item: AdvancePayment
    ): Response<Unit>

    // --- HISTORY ---
    @GET("history")
    suspend fun getHistory(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY
    ): Response<List<EmployeeHistory>>

    @POST("history")
    suspend fun createHistory(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY,
        @Body item: EmployeeHistory
    ): Response<Unit>

    // --- CLOSED DAYS ---
    @GET("closed_days")
    suspend fun getClosedDays(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY
    ): Response<List<ShopClosedDay>>

    @POST("closed_days")
    suspend fun createClosedDay(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY,
        @Body item: ShopClosedDay
    ): Response<Unit>

    // --- REGULARIZATIONS ---
    @GET("regularizations")
    suspend fun getRegularizations(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY
    ): Response<List<RegularizationRequest>>

    @POST("regularizations")
    suspend fun createRegularization(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY,
        @Body item: RegularizationRequest
    ): Response<Unit>

    // --- PUNCHES ---
    @GET("punches")
    suspend fun getPunches(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY
    ): Response<List<AttendancePunch>>

    @POST("punches")
    suspend fun createPunch(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY,
        @Body item: AttendancePunch
    ): Response<Unit>

    @PATCH("punches")
    suspend fun updatePunch(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY,
        @Query("punchId") id: String,
        @Body item: Map<String, @JvmSuppressWildcards Any?>
    ): Response<Unit>

    @DELETE("punches")
    suspend fun deletePunch(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY,
        @Query("punchId") id: String
    ): Response<Unit>

    // --- SALARY SNAPSHOTS ---
    @GET("salary_snapshots")
    suspend fun getSalarySnapshots(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY
    ): Response<List<SalarySnapshot>>

    @POST("salary_snapshots")
    suspend fun createSalarySnapshot(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY,
        @Body item: SalarySnapshot
    ): Response<Unit>

    // --- AUDIT LOGS ---
    @GET("audit_logs")
    suspend fun getAuditLogs(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY
    ): Response<List<AuditLog>>

    @POST("audit_logs")
    suspend fun createAuditLog(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY,
        @Body item: AuditLog
    ): Response<Unit>

    // --- LEAVE REQUESTS ---
    @GET("leave_requests")
    suspend fun getLeaveRequests(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY
    ): Response<List<LeaveRequest>>

    @POST("leave_requests")
    suspend fun createLeaveRequest(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY,
        @Body item: LeaveRequest
    ): Response<Unit>

    // --- RESIGNATIONS ---
    @GET("resignations")
    suspend fun getResignationRequests(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY
    ): Response<List<ResignationRequest>>

    @POST("resignations")
    suspend fun createResignationRequest(
        @Header("Authorization") token: String?,
        @Header("apikey") apiKey: String = BuildConfig.NEON_API_KEY,
        @Body item: ResignationRequest
    ): Response<Unit>
}
