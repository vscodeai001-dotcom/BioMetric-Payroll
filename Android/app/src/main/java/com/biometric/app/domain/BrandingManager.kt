package com.biometric.app.domain

import com.biometric.app.data.entity.Shop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrandingManager @Inject constructor() {

    data class BrandingConfig(
        val appName: String,
        val logoUrl: String? = null
    )

    private val _brandingConfig = MutableStateFlow(BrandingConfig(appName = "Biometric Payroll"))
    val brandingConfig: StateFlow<BrandingConfig> = _brandingConfig.asStateFlow()

    fun updateBranding(config: BrandingConfig) {
        _brandingConfig.value = config
    }
    
    // Helper to load branding from Shop entity
    fun loadFromShop(shop: Shop) {
        updateBranding(BrandingConfig(
            appName = shop.brandingName ?: shop.name,
            logoUrl = shop.brandingLogoUrl
        ))
    }
}
