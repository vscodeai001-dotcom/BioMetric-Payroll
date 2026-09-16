package com.biometric.app.data.entity

import com.google.gson.annotations.SerializedName

data class CompanySettings(
    @SerializedName("companyName") var companyName: String = "",
    @SerializedName("officeLatitude") var officeLatitude: Double = 0.0,
    @SerializedName("officeLongitude") var officeLongitude: Double = 0.0,
    @SerializedName("geoRadiusMeters") var geoRadiusMeters: Int = 1000
)
