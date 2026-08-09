package com.example.access.data

data class Config(
    val activeDatabaseId: String = "",
    val lastUpdated: String = "",
    val status: String = "active",
    val newConfigId: String? = null,
    val roleHashes: Map<String, String> = emptyMap(),
    val branding: BrandingConfig = BrandingConfig(),
    val tier: String = "free",
    val proPurchaseToken: String? = null,
    val proActivatedAt: String? = null
) {
    val isPro: Boolean get() = tier == "pro"
}

data class BrandingConfig(
    val organizationName: String = "EasyPass",
    val primaryColor: String = "#006064",
    val logoFileId: String? = null,
    val fieldConfig: FieldConfig = FieldConfig()
)

data class FieldConfig(
    val showPhone: Boolean = false,
    val showEmail: Boolean = false,
    val showAddress: Boolean = false,
    val showNotes: Boolean = false
)
