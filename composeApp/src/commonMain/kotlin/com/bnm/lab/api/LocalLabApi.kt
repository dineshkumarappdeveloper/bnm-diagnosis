package com.bnm.lab.api

import androidx.compose.runtime.staticCompositionLocalOf

val LocalLabApi = staticCompositionLocalOf<LabApi> {
    error("LabApi not provided")
}
