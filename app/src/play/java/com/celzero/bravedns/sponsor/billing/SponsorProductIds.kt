package com.celzero.bravedns.sponsor.billing

object SponsorProductIds {
    /**
     * Fixed-price one-time INAPP products, one per contribution level.
     *
     * Create each of these SKUs in the Google Play Console with a matching id
     * and the corresponding price (e.g. id "sponsor.5" priced at US$4.99).
     *
     * Why multiple SKUs (not one SKU x quantity): Google removed client-side
     * purchase quantities for one-time INAPP products in Play Billing 7.x/8.x
     * (the app ships 8.3.0), so every amount needs its own product.
     */
    private val AMOUNT_TO_PRODUCT = mapOf(
        1 to "sponsor.1",
        2 to "sponsor.2",
        3 to "sponsor.3",
        4 to "sponsor.4",
        5 to "sponsor.5",
        10 to "sponsor.10",
        15 to "sponsor.15",
        25 to "sponsor.25",
        50 to "sponsor.50",
        100 to "sponsor.100"
    )

    /** All sponsor SKUs, used to query their prices from the Play Store. */
    val ALL_PRODUCT_IDS: List<String> = AMOUNT_TO_PRODUCT.values.toList()

    /** The product id for the given [amount], falling back to the default tier. */
    fun productIdFor(amount: Int): String =
        AMOUNT_TO_PRODUCT[amount] ?: AMOUNT_TO_PRODUCT.getValue(DEFAULT_AMOUNT)

    const val PRODUCT_TYPE = "inapp"

    // Must mirror the UI's selectable amounts (see SponsorUiState.SUPPORTED_AMOUNTS).
    private const val DEFAULT_AMOUNT = 5
}
