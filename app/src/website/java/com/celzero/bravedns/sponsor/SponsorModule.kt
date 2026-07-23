package com.celzero.bravedns.sponsor

import com.celzero.bravedns.sponsor.billing.SponsorBillingManager
import com.celzero.bravedns.sponsor.billing.SponsorBillingManagerImpl
import com.celzero.bravedns.sponsor.provider.GooglePlaySponsorProvider
import com.celzero.bravedns.sponsor.provider.SponsorProvider
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

object SponsorModule {
    private val sponsorModule: Module = module {
        // createdAtStart: Koin instantiates this single during startKoin (after the
        // module list is loaded), so the billing client connects at app startup
        // rather than lazily when the Sponsor screen is first opened. This keeps
        // isBillingReady/products warm and lets existing sponsor purchases be
        // queried promptly, so the home/about UI reflects subscription state on
        // the initial launch.
        single<SponsorBillingManager>(createdAtStart = true) {
            SponsorBillingManagerImpl(androidContext()).also { it.initialize() }
        }
        single<SponsorProvider> { GooglePlaySponsorProvider() }
    }

    val modules: List<Module> = listOf(sponsorModule)
}
