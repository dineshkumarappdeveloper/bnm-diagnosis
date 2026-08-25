package com.bnm.diagnosis.lab

import androidx.compose.runtime.staticCompositionLocalOf

/** App-wide [LabRepository], provided once in App.kt. */
val LocalLabRepository = staticCompositionLocalOf<LabRepository> {
    error("LocalLabRepository not provided — wrap content in CompositionLocalProvider in App.kt")
}
