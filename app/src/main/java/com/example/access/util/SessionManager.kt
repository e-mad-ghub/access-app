package com.example.access.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.access.data.Config
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SessionManager(val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("easypass_prefs", Context.MODE_PRIVATE)

    companion object {
        const val ROLE_SCANNER = "scanner"
        const val ROLE_ADMIN = "admin"
        const val ROLE_OWNER = "owner"
        private const val TAG = "SessionManager"
    }

    private val _activeRole = MutableStateFlow(ROLE_SCANNER)
    val activeRole: StateFlow<String> = _activeRole

    private var _pendingRole: String? = null

    var currentConfig: Config? = null

    fun updateConfig(config: Config) {
        currentConfig = config
        Log.d(TAG, "Config updated in SessionManager. Hashes available: ${config.roleHashes.keys}")
    }

    fun getBaseRole(): String = ROLE_SCANNER

    fun getActiveRole(): String = _activeRole.value

    fun setActiveRole(role: String) {
        _activeRole.value = role
        Log.d(TAG, "Active role set to: $role")
    }

    fun setPendingRole(role: String?) {
        _pendingRole = role
        Log.d(TAG, "Pending role set to: $role")
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
        val matches = mutableListOf<String>()
        if (SecurityUtils.verifyPassword(password, config.roleHashes["owner"])) {
            matches.add(ROLE_OWNER)
        }
        if (SecurityUtils.verifyPassword(password, config.roleHashes["admin"])) {
            matches.add(ROLE_ADMIN)
        }
        return matches
    }

    fun checkPassword(password: String): String? {
        val matches = checkPasswordAll(password)
        // Return owner if both match (backward compatibility), else first match or null
        return when {
            matches.contains(ROLE_OWNER) -> ROLE_OWNER
            matches.contains(ROLE_ADMIN) -> ROLE_ADMIN
            else -> null
        }
    }

    fun resetSession() {
        _activeRole.value = ROLE_SCANNER
        Log.d(TAG, "Session reset to SCANNER")
    }
}
