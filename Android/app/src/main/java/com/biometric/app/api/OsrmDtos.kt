package com.biometric.app.api

import com.google.gson.annotations.SerializedName

data class OsrmResponse(
    @SerializedName("code") val code: String,
    @SerializedName("routes") val routes: List<OsrmRoute>?
)

data class OsrmRoute(
    @SerializedName("geometry") val geometry: String,
    @SerializedName("distance") val distance: Double,
    @SerializedName("duration") val duration: Double
)
