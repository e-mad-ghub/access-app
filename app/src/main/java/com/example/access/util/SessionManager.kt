package com.example.access.util

import android.content.Context
import android.content.SharedPreferences
import com.example.access.data.Config
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SessionManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("vault_access_prefs", Context.MODE_PRIVATE)

    companion object {
        const val ROLE_SCANNER = "scanner"
        const val ROLE_ADMIN = "admin"
        const val ROLE_OWNER = "owner"
    }

    private val _activeRole = MutableStateFlow(getBaseRole())
    val activeRole: StateFlow<String> = _activeRole

    private var currentConfig: Config? = null

    fun updateConfig(config: Config) {
        this.currentConfig = config
    }

    fun getBaseRole(): String {
        return prefs.getString("base_role", ROLE_SCANNER) ?: ROLE_SCANNER
    }

    fun getActiveRole(): String = _activeRole.value

    fun setActiveRole(role: String) {
        _activeRole.value = role
    }

    fun checkPassword(plainText: String): String? {
        val hash = SecurityUtils.hashPassword(plainText)
        val hashes = currentConfig?.roleHashes ?: return null
        
        return when {
            hash == hashes["owner"] -> ROLE_OWNER
            hash == hashes["admin"] -> ROLE_ADMIN
            else -> null
        }
    }

    fun resetSession() {
        _activeRole.value = getBaseRole()
    }
}
