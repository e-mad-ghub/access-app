package com.example.access

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.access.util.BillingManager
import com.example.access.util.DriveSyncManager
import com.example.access.util.TierManager
import com.example.access.data.Config
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for managing billing state and purchase flow.
 * Integrates BillingManager with TierManager for Pro tier activation.
 */
class BillingViewModel(application: Application) : AndroidViewModel(application) {
    private val billingManager = BillingManager(application)
    
    val productDetails: StateFlow<com.android.billingclient.api.ProductDetails?>
        get() = billingManager.productDetails
    
    val billingState: StateFlow<BillingManager.BillingState>
        get() = billingManager.billingState
    
    /**
     * Launch purchase flow for Pro subscription.
     * @param onPurchaseComplete Callback invoked when purchase is completed and acknowledged.
     */
    fun launchPurchase(activity: android.app.Activity, onPurchaseComplete: (com.android.billingclient.api.Purchase) -> Unit) {
        billingManager.launchPurchase(activity, onPurchaseComplete)
    }
    
    /**
     * Activate Pro tier after successful purchase.
     * Updates config with purchase token and writes to Drive.
     */
    fun activateProAfterPurchase(
        purchase: com.android.billingclient.api.Purchase,
        currentConfig: Config,
        configFileId: String,
        onConfigUpdated: (Config) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val account = GoogleSignIn.getLastSignedInAccount(getApplication())
                if (account == null) {
                    Log.e("BillingViewModel", "No Google account signed in")
                    return@launch
                }
                
                val cred = GoogleAccountCredential.usingOAuth2(
                    getApplication(),
                    listOf(DriveScopes.DRIVE)
                ).apply { selectedAccount = account.account }
                
                val sync = DriveSyncManager.createWithCredential(getApplication(), cred)
                val updatedConfig = TierManager.activatePro(
                    sync = sync,
                    configFileId = configFileId,
                    currentConfig = currentConfig,
                    purchaseToken = purchase.purchaseToken
                )
                
                onConfigUpdated(updatedConfig)
                Log.d("BillingViewModel", "Pro tier activated successfully")
            } catch (e: Exception) {
                Log.e("BillingViewModel", "Failed to activate Pro tier", e)
            }
        }
    }
    
    /**
     * Check subscription status and update tier if needed.
     * This can be called periodically to sync subscription state.
     */
    fun refreshSubscriptionStatus(
        configFileId: String,
        currentConfig: Config,
        onConfigUpdated: (Config) -> Unit
    ) {
        viewModelScope.launch {
            // In a full implementation, you would query Play Billing for subscription status
            // and downgrade to free tier if subscription expired.
            // For now, we rely on the config's tier field which is synced via Drive.
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        billingManager.endConnection()
    }
}