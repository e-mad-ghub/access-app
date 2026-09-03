package com.example.access.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.access.data.Config
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

class SessionManager(val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("easypass_prefs", Context.MODE_PRIVATE)

    companion object {
        const val ROLE_SCANNER = "scanner"
        const val ROLE_MANAGER = "manager"
        const val ROLE_DIRECTOR = "director"
        const val LEGACY_ROLE_ADMIN = "admin"
        const val LEGACY_ROLE_OWNER = "owner"
        @Deprecated("Use ROLE_MANAGER")
        const val ROLE_ADMIN = ROLE_MANAGER
        @Deprecated("Use ROLE_DIRECTOR")
        const val ROLE_OWNER = ROLE_DIRECTOR
        private const val TAG = "SessionManager"

        fun canonicalRole(role: String?): String {
            return when (role?.trim()?.lowercase(Locale.US)) {
                ROLE_MANAGER, LEGACY_ROLE_ADMIN -> ROLE_MANAGER
                ROLE_DIRECTOR, LEGACY_ROLE_OWNER -> ROLE_DIRECTOR
                else -> ROLE_SCANNER
            }
        }

        fun roleDisplayName(role: String?): String {
            return when (canonicalRole(role)) {
                ROLE_MANAGER -> "Manager"
                ROLE_DIRECTOR -> "Director"
                else -> "Scanner"
            }
        }
    }

    private val _activeRole = MutableStateFlow(ROLE_SCANNER)
    val activeRole: StateFlow<String> = _activeRole

    private var _pendingRole: String? = null
    private var currentAuthorizedEmail: String? = null

    var currentConfig: Config? = null

    fun updateConfig(config: Config) {
        currentConfig = config
        Log.d(TAG, "Config updated in SessionManager. Hashes available: ${config.roleHashes.keys}")
    }

    fun setAuthorizedEmail(email: String?) {
        currentAuthorizedEmail = email
        Log.d(TAG, "Authorized email updated: ${email ?: "<none>"}")
    }

    fun getAuthorizedEmail(): String? = currentAuthorizedEmail

    fun canCurrentEmailUseRole(role: String): Boolean {
        return currentConfig?.canEmailUseRole(currentAuthorizedEmail, role) == true
    }

    fun getBaseRole(): String = ROLE_SCANNER

    fun getActiveRole(): String = _activeRole.value

    fun setActiveRole(role: String) {
        val canonicalRole = canonicalRole(role)
        _activeRole.value = canonicalRole
        Log.d(TAG, "Active role set to: $canonicalRole")
    }

    fun setPendingRole(role: String?) {
        _pendingRole = role?.let(::canonicalRole)
        Log.d(TAG, "Pending role set to: ${_pendingRole}")
    }

    fun applyPendingRole(): String? {
        val role = _pendingRole
        if (role != null) {
            _activeRole.value = role
            Log.d(TAG, "Applied pending role: $role")
            _pendingRole = null
        }
        return role
    }

    fun checkPasswordAll(password: String): List<String> {
        val config = currentConfig ?: run {
            Log.e(TAG, "checkPasswordAll failed: currentConfig is null")
            return emptyList()
        }
        val email = currentAuthorizedEmail
        val matches = mutableListOf<String>()
        if (config.canEmailUseRole(email, ROLE_DIRECTOR) && SecurityUtils.verifyPassword(password, config.hashForRole(ROLE_DIRECTOR))) {
            matches.add(ROLE_DIRECTOR)
        }
        if (config.canEmailUseRole(email, ROLE_MANAGER) && SecurityUtils.verifyPassword(password, config.hashForRole(ROLE_MANAGER))) {
            matches.add(ROLE_MANAGER)
        }
        return matches
    }

    fun checkPassword(password: String): String? {
        val matches = checkPasswordAll(password)
        // Return director if both match, else manager or null.
        return when {
            matches.contains(ROLE_DIRECTOR) -> ROLE_DIRECTOR
            matches.contains(ROLE_MANAGER) -> ROLE_MANAGER
            else -> null
        }
    }

    fun resetSession() {
        _activeRole.value = ROLE_SCANNER
        _pendingRole = null
        Log.d(TAG, "Session reset to SCANNER")
    }
}
