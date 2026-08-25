package com.bnm.diagnosis.chat

import androidx.compose.runtime.staticCompositionLocalOf

/** App-wide [BillingRepository], provided once in App.kt. */
val LocalBillingRepository = staticCompositionLocalOf<BillingRepository> {
    error("LocalBillingRepository not provided — wrap content in CompositionLocalProvider in App.kt")
}
