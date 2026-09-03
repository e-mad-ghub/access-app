package com.example.access.data

import com.example.access.util.SessionManager
import java.util.Locale

data class Config(
    val activeDatabaseId: String = "",
    val storageFolderId: String? = null,
    val lastUpdated: String = "",
    val status: String = "active",
    val newConfigId: String? = null,
    val roleHashes: Map<String, String> = emptyMap(),
    val accessRoster: List<AccessRosterEntry> = emptyList(),
    val storageOwnerEmail: String? = null,
    val branding: BrandingConfig = BrandingConfig(),
    val tier: String = "free",
    val proPurchaseToken: String? = null,
    val proActivatedAt: String? = null
) {
    val isPro: Boolean get() = tier == "pro"

    fun hasAccessRoster(): Boolean = accessRoster.isNotEmpty()

    fun activeAccessEntryFor(email: String?): AccessRosterEntry? {
        val normalizedEmail = email.normalizeAccessEmail() ?: return null
        return accessRoster.firstOrNull { entry ->
            entry.isActive && entry.email.normalizeAccessEmail() == normalizedEmail
        }
    }

    fun canEmailUseRole(email: String?, role: String): Boolean {
        val entry = activeAccessEntryFor(email) ?: return false
        return when (SessionManager.canonicalRole(entry.role)) {
            SessionManager.ROLE_DIRECTOR -> SessionManager.canonicalRole(role) in setOf(
                SessionManager.ROLE_DIRECTOR,
                SessionManager.ROLE_MANAGER,
                SessionManager.ROLE_SCANNER
            )
            SessionManager.ROLE_MANAGER -> SessionManager.canonicalRole(role) in setOf(
                SessionManager.ROLE_MANAGER,
                SessionManager.ROLE_SCANNER
            )
            else -> SessionManager.canonicalRole(role) == SessionManager.ROLE_SCANNER
        }
    }

    fun maximumRoleFor(email: String?): String? {
        val entry = activeAccessEntryFor(email) ?: return null
        return SessionManager.canonicalRole(entry.role)
    }

    fun hashForRole(role: String): String? {
        return when (SessionManager.canonicalRole(role)) {
            SessionManager.ROLE_MANAGER -> roleHashes[SessionManager.ROLE_MANAGER] ?: roleHashes[SessionManager.LEGACY_ROLE_ADMIN]
            SessionManager.ROLE_DIRECTOR -> roleHashes[SessionManager.ROLE_DIRECTOR] ?: roleHashes[SessionManager.LEGACY_ROLE_OWNER]
            else -> null
        }
    }
}

data class AccessRosterEntry(
    val email: String = "",
    val role: String = SessionManager.ROLE_SCANNER,
    val isActive: Boolean = true,
    val createdAt: String? = null,
    val configPermissionId: String? = null,
    val databasePermissionId: String? = null,
    val logoPermissionId: String? = null
)

data class BrandingConfig(
    val organizationName: String = "easyPass",
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

private fun String?.normalizeAccessEmail(): String? {
    return this
        ?.trim()
        ?.lowercase(Locale.US)
        ?.takeIf { it.isNotBlank() }
}
