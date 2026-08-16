package com.example.access.util

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BillingManager(context: Context) : PurchasesUpdatedListener, BillingClientStateListener {
    companion object {
        private const val TAG = "BillingManager"
        const val SUBSCRIPTION_PRODUCT_ID = "easypass_pro_monthly"
        private const val PRODUCT_TYPE_SUBS = BillingClient.ProductType.SUBS
    }
    
    private val billingClient = BillingClient.newBuilder(context)
        .setListener(this as PurchasesUpdatedListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails.asStateFlow()
    
    private val _billingState = MutableStateFlow<BillingState>(BillingState.LOADING)
    val billingState: StateFlow<BillingState> = _billingState.asStateFlow()
    
    private var onPurchaseComplete: ((Purchase) -> Unit)? = null
    
    init {
        billingClient.startConnection(this)
    }

    override fun onBillingSetupFinished(billingResult: BillingResult) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            Log.d(TAG, "Billing connected")
            verifyProductDetailsSupportAndQuery()
            queryExistingPurchases()
        } else {
            Log.e(TAG, "Billing setup failed: ${billingResult.debugMessage}")
            _billingState.value = BillingState.ERROR(billingResult.debugMessage)
        }
    }
    override fun onBillingServiceDisconnected() {
        Log.d(TAG, "Billing service disconnected")
        _billingState.value = BillingState.DISCONNECTED
    }
    
    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.let { processPurchases(it) }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG, "Purchase canceled")
                _billingState.value = BillingState.IDLE
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.i(TAG, "Item already owned")
                queryExistingPurchases()
                _billingState.value = BillingState.IDLE
            }
            else -> {
                Log.e(TAG, "Purchase error: ${billingResult.debugMessage}")
                _billingState.value = BillingState.ERROR(billingResult.debugMessage)
            }
        }
    }
    
    fun launchPurchase(activity: Activity, onComplete: (Purchase) -> Unit) {
        val productDetails = _productDetails.value
        if (productDetails == null) {
            _billingState.value = BillingState.ERROR("Product not loaded")
            return
        }
        
        val subscriptionOfferDetails = productDetails.subscriptionOfferDetails?.firstOrNull()
        if (subscriptionOfferDetails == null) {
            _billingState.value = BillingState.ERROR("Subscription offer not available")
            return
        }
        
        val offerToken = subscriptionOfferDetails.offerToken
        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .setOfferToken(offerToken)
            .build()
        
        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()
        
        onPurchaseComplete = onComplete
        _billingState.value = BillingState.PURCHASING
        
        val response = billingClient.launchBillingFlow(activity, billingFlowParams)
        if (response.responseCode != BillingClient.BillingResponseCode.OK) {
            _billingState.value = BillingState.ERROR(response.debugMessage)
            onPurchaseComplete = null
        }
    }
    
    private fun acknowledgePurchase(purchase: Purchase) {
        scope.launch {
            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
                val params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                val result = billingClient.acknowledgePurchase(params)
                withContext(Dispatchers.Main) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        Log.d(TAG, "Purchase acknowledged")
                        onPurchaseComplete?.invoke(purchase)
                        onPurchaseComplete = null
                        _billingState.value = BillingState.PURCHASE_COMPLETE(purchase)
                    } else {
                        Log.e(TAG, "Acknowledge failed: ${result.debugMessage}")
                        _billingState.value = BillingState.ERROR(result.debugMessage)
                    }
                }
            } else {
                withContext(Dispatchers.Main) {
                    onPurchaseComplete?.invoke(purchase)
                    onPurchaseComplete = null
                    _billingState.value = BillingState.PURCHASE_COMPLETE(purchase)
                }
            }
        }
    }
    
    private fun queryExistingPurchases() {
        scope.launch {
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(PRODUCT_TYPE_SUBS)
                .build()
            val purchasesResult = billingClient.queryPurchasesAsync(params)
            purchasesResult.purchasesList
                .firstOrNull { it.products.contains(SUBSCRIPTION_PRODUCT_ID) }
                ?.let { acknowledgePurchase(it) }
        }
    }
    
    private fun processPurchases(purchases: List<Purchase>) {
        purchases.firstOrNull { it.products.contains(SUBSCRIPTION_PRODUCT_ID) }
            ?.let { acknowledgePurchase(it) }
    }

    private fun verifyProductDetailsSupportAndQuery() {
        val billingResult = billingClient.isFeatureSupported(BillingClient.FeatureType.PRODUCT_DETAILS)
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            queryProductDetails()
        } else {
            val message = "Product details unsupported: ${billingResult.debugMessage}"
            Log.e(TAG, message)
            _billingState.value = BillingState.ERROR(message)
        }
    }

    private fun queryProductDetails() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(SUBSCRIPTION_PRODUCT_ID)
                .setProductType(PRODUCT_TYPE_SUBS)
                .build()
        )
        
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, queryProductDetailsResult ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val matchedProduct = queryProductDetailsResult.productDetailsList
                    .firstOrNull { it.productId == SUBSCRIPTION_PRODUCT_ID }
                _productDetails.value = matchedProduct
                if (matchedProduct != null) {
                    _billingState.value = BillingState.READY
                } else {
                    val unfetched = queryProductDetailsResult.unfetchedProductList
                        .firstOrNull { it.productId == SUBSCRIPTION_PRODUCT_ID }
                    val message = buildString {
                        append("Product ")
                        append(SUBSCRIPTION_PRODUCT_ID)
                        append(" unavailable")
                        if (unfetched != null) {
                            append(" (status=")
                            append(unfetched.statusCode)
                            append(")")
                        }
                        append(". Verify the Play Console product id, package name, and that the subscription/base plan is active.")
                    }
                    Log.e(
                        TAG,
                        "queryProductDetails returned no match for $SUBSCRIPTION_PRODUCT_ID. " +
                            "unfetched=${queryProductDetailsResult.unfetchedProductList}"
                    )
                    _billingState.value = BillingState.ERROR(message)
                }
            } else {
                val message = "Product query failed: ${billingResult.debugMessage}"
                Log.e(TAG, message)
                _billingState.value = BillingState.ERROR(message)
            }
        }
    }
    
    fun endConnection() {
        scope.cancel()
        billingClient.endConnection()
        _billingState.value = BillingState.DISCONNECTED
    }

    sealed class BillingState {
        object LOADING : BillingState()
        object READY : BillingState()
        object IDLE : BillingState()
        object DISCONNECTED : BillingState()
        object PURCHASING : BillingState()
        data class PURCHASE_COMPLETE(val purchase: Purchase) : BillingState()
        data class ERROR(val message: String) : BillingState()
    }
}
    
