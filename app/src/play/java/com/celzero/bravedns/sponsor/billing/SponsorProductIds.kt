/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.sponsor.billing

object SponsorProductIds {
    /**
     * Fixed-price one-time INAPP products, one per contribution level.
     *
     * Create each of these SKUs in the store backend with a matching id and the
     * corresponding price (e.g. id "sponsor.tier.5" priced at US$4.99).
     *
     * Why multiple SKUs (not one SKU x quantity): client-side purchase quantities
     * for one-time products are not available, so every amount needs its own
     * product.
     */
    private val AMOUNT_TO_PRODUCT = mapOf(
        1 to "sponsor.tier.1",
        5 to "sponsor.tier.5",
        10 to "sponsor.tier.10",
        15 to "sponsor.tier.15",
        25 to "sponsor.tier.25",
        50 to "sponsor.tier.50",
        100 to "sponsor.tier.100"
    )

    /** All sponsor SKUs, used to query their prices from the store. */
    val ALL_PRODUCT_IDS: List<String> = AMOUNT_TO_PRODUCT.values.toList()

    /** The product id for the given [amount], falling back to the default tier. */
    fun productIdFor(amount: Int): String =
        AMOUNT_TO_PRODUCT[amount] ?: AMOUNT_TO_PRODUCT.getValue(DEFAULT_AMOUNT)

    const val PRODUCT_TYPE = "inapp"

    // Must mirror the UI's selectable amounts (see SponsorUiState.SUPPORTED_AMOUNTS).
    private const val DEFAULT_AMOUNT = 5
}
