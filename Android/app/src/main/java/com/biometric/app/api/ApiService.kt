package com.biometric.app.api

import com.biometric.app.data.entity.Shop
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    // --- SYNC PUSH ---
    @POST("api/internal/attendance-refresh")
    suspend fun triggerSignalRRefresh(
        @Header("X-Attendance-Refresh-Secret") secret: String = "_SKmPG4ifIU8bL8JErEop_YVRGhxq-j-1xzat8oPn6TDdjLj"
    ): Response<Unit>

    @POST("sync/shops")
    suspend fun pushShops(@Body shops: List<Shop>): Response<Unit>

    // --- SYNC PULL (For Owner Dashboard) ---
    @GET("sync/all-shops-summary")
    suspend fun getGlobalSummary(): Response<GlobalSummaryResponse>
}

data class LoginRequest(val username: String, val password: String, val deviceId: String)
data class AuthResponse(val token: String, val ownerId: String)
data class GlobalSummaryResponse(
    val activeStaffToday: Int,
    val pendingSalaries: Double,
    val shopSummaries: List<ShopSummary>
)
data class ShopSummary(val shopId: String, val name: String, val staffCount: Int)
