package com.biometric.app.data.network

import com.biometric.app.data.entity.LocationTrack
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

interface NeonApiService {
    @POST("api/employee-location/update")
    suspend fun updateLocation(@Body track: LocationTrack): Response<Unit>
}
