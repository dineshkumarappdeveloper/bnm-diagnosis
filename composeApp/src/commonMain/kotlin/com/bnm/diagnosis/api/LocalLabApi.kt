package com.bnm.diagnosis.api

import androidx.compose.runtime.staticCompositionLocalOf

val LocalLabApi = staticCompositionLocalOf<LabApi> {
    error("LabApi not provided")
}
