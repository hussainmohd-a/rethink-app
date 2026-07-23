package com.celzero.bravedns.sponsor.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.celzero.bravedns.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class SponsorBillingManagerImpl(context: Context) : SponsorBillingManager {

    companion object {
        private const val TAG = "SponsorBilling"
    }

    private val appContext: Context = context.applicationContext

    private var billingClient: BillingClient? = null

    private val _products = MutableStateFlow<List<SponsorProduct>>(emptyList())
    override val products: Flow<List<SponsorProduct>> = _products.asStateFlow()

    private val _purchaseResult = MutableSharedFlow<SponsorPurchaseResult>(extraBufferCapacity = 3)
    override val purchaseResult: Flow<SponsorPurchaseResult> = _purchaseResult.asSharedFlow()

    private val _isBillingReady = MutableStateFlow(false)
    override val isBillingReady: Flow<Boolean> = _isBillingReady.asStateFlow()

    private var isInitialized = false

    private val purchaseListener = PurchasesUpdatedListener { billingResult, purchases ->
        onPurchasesUpdated(billingResult, purchases)
    }

    override fun initialize() {
        if (isInitialized) return
        isInitialized = true
        setupBillingClient()
    }

    private fun setupBillingClient() {
        try {
            billingClient = BillingClient.newBuilder(appContext)
                .setListener(purchaseListener)
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder().enableOneTimeProducts().enablePrepaidPlans()
                        .build())
                .build()

            billingClient?.startConnection(object : com.android.billingclient.api.BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        _isBillingReady.value = true
                        queryExistingPurchases()
                        queryProducts()
                    } else {
                        _isBillingReady.value = false
                    }
                }
                override fun onBillingServiceDisconnected() {
                    _isBillingReady.value = false
                }
            })
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to setup billing client: ${e.message}", e)
            _purchaseResult.tryEmit(SponsorPurchaseResult.BillingUnavailable)
        }
    }

    override fun queryProducts() {
        val client = billingClient ?: return
        if (!client.isReady) return

        // Query every contribution level so the UI can show localized prices.
        val productList = SponsorProductIds.ALL_PRODUCT_IDS.map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(ProductType.INAPP)
                .build()
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        client.queryProductDetailsAsync(params) { _: BillingResult, result ->
            _products.value = result.productDetailsList.map { it.toSponsorProduct() }
        }
    }

    override fun launchBillingFlow(activity: Activity, amount: Int) {
        val client = billingClient
        if (client == null || !client.isReady) {
            _purchaseResult.tryEmit(SponsorPurchaseResult.BillingUnavailable)
            return
        }

        // Each amount is its own fixed-price SKU (one-time product, quantity 1).
        val productId = SponsorProductIds.productIdFor(amount)

        val prodParam = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(productId)
            .setProductType(ProductType.INAPP)
            .build()

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(prodParam))
            .build()

        client.queryProductDetailsAsync(params) { _: BillingResult, detailsResult ->
            val productDetails = detailsResult.productDetailsList.firstOrNull()
            if (productDetails == null) {
                _purchaseResult.tryEmit(SponsorPurchaseResult.Error("Product not found"))
                return@queryProductDetailsAsync
            }

            val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .build()

            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productParams))
                .build()

            val billingResult = client.launchBillingFlow(activity, flowParams)

            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                val error = when (billingResult.responseCode) {
                    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> SponsorPurchaseResult.AlreadyOwned
                    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> SponsorPurchaseResult.BillingUnavailable
                    BillingClient.BillingResponseCode.USER_CANCELED -> SponsorPurchaseResult.Cancelled
                    else -> SponsorPurchaseResult.Error(billingResult.debugMessage, billingResult.responseCode)
                }
                _purchaseResult.tryEmit(error)
            }
        }
    }

    override fun consumePurchase(purchaseToken: String) {
        val client = billingClient ?: return
        if (!client.isReady) return

        val consumeParams = ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build()
        client.consumeAsync(consumeParams) { result, _ ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Logger.e(TAG, "Consume failed: ${result.debugMessage}")
            }
        }
    }

    override fun destroy() {
        try { billingClient?.endConnection() } catch (_: Exception) { }
        billingClient = null
        isInitialized = false
    }

    private fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (!purchases.isNullOrEmpty()) handlePurchases(purchases)
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> _purchaseResult.tryEmit(SponsorPurchaseResult.Cancelled)
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> queryExistingPurchases()
            else -> _purchaseResult.tryEmit(SponsorPurchaseResult.Error(billingResult.debugMessage, billingResult.responseCode))
        }
    }

    private fun handlePurchases(purchases: List<Purchase>) {
        purchases.forEach { purchase ->
            when (purchase.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> {
                    // Forward the authoritative purchaseTime/token/productId so the
                    // repository records the real purchase time, not wall-clock now.
                    _purchaseResult.tryEmit(
                        SponsorPurchaseResult.Success(
                            purchaseTime = purchase.purchaseTime,
                            purchaseToken = purchase.purchaseToken,
                            productId = purchase.products.firstOrNull().orEmpty()
                        )
                    )
                    if (!purchase.isAcknowledged) {
                        val ackParams = AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchase.purchaseToken).build()
                        billingClient?.acknowledgePurchase(ackParams) { _ -> }
                    }
                    // Sponsorship is a one-time INAPP product. Consume it immediately on
                    // success so the SKU is re-purchasable (contributors can give again),
                    // and so the purchase doesn't linger as an un-consumed entitlement.
                    consumePurchase(purchase.purchaseToken)
                }
                Purchase.PurchaseState.PENDING -> _purchaseResult.tryEmit(SponsorPurchaseResult.Pending)
                else -> {}
            }
        }
    }

    private fun queryExistingPurchases() {
        val client = billingClient ?: return
        if (!client.isReady) return

        val params = QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()
        client.queryPurchasesAsync(params) { _, purchaseResult ->
            handlePurchases(purchaseResult)
        }
    }

    private fun ProductDetails.toSponsorProduct(): SponsorProduct {
        val price = oneTimePurchaseOfferDetails
        return SponsorProduct(
            productId = productId,
            title = title,
            description = description,
            price = price?.formattedPrice,
            priceMicros = price?.priceAmountMicros ?: 0L
        )
    }
}
