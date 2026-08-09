package com.example.access.util

import android.util.Log
import com.example.access.data.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Manages tier state (free/pro) and synchronizes tier updates to the shared config.json on Drive.
 * Callers must provide a DriveSyncManager that has write access (owner device).
 */
object TierManager {

    /**
     * Upgrade the organization to Pro tier and write the updated config to Drive.
     * @param sync DriveSyncManager with write credentials (owner device)
     * @param configFileId the file ID of config.json on Drive
     * @param currentConfig the current Config object
     * @param purchaseToken the Google Play purchase token for verification
     * @return the updated Config with tier="pro" and token/activation timestamp
     */
    suspend fun activatePro(
        sync: DriveSyncManager,
        configFileId: String,
        currentConfig: Config,
        purchaseToken: String
    ): Config = withContext(Dispatchers.IO) {
        val newConfig = currentConfig.copy(
            tier = "pro",
            proPurchaseToken = purchaseToken,
            proActivatedAt = Instant.now().toString()
        )
        try {
            sync.updateConfigOnDrive(configFileId, newConfig)
            Log.d("TierManager", "Pro activation written to Drive")
        } catch (e: Exception) {
            Log.e("TierManager", "Failed to write Pro activation to Drive", e)
            // Still return the new config; the local UI will reflect the upgrade,
            // and the next sync will attempt to write again.
        }
        newConfig
    }

    /**
     * Downgrade the organization to Free tier (e.g., subscription lapsed).
     * Writes the updated config to Drive.
     */
    suspend fun deactivatePro(
        sync: DriveSyncManager,
        configFileId: String,
        currentConfig: Config
    ): Config = withContext(Dispatchers.IO) {
        val newConfig = currentConfig.copy(
            tier = "free",
            proPurchaseToken = null,
            proActivatedAt = null
        )
        try {
            sync.updateConfigOnDrive(configFileId, newConfig)
            Log.d("TierManager", "Pro deactivation written to Drive")
        } catch (e: Exception) {
            Log.e("TierManager", "Failed to write Pro deactivation to Drive", e)
        }
        newConfig
    }

    /**
     * Refresh tier state from the given config (called when config is loaded or synced).
     * Returns true if the config indicates Pro tier.
     */
    fun isPro(config: Config): Boolean = config.isPro
}