package com.bnm.diagnosis.print

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Desktop (JVM) stub — there is no portable JVM Bluetooth stack. On desktop use Network (LAN). */
actual class BtPrinter {
    private val state = MutableStateFlow(BtScanState.UNSUPPORTED)
    actual val scanState: StateFlow<BtScanState> = state.asStateFlow()

    private val empty = MutableStateFlow<List<BtPrinterDevice>>(emptyList())
    actual val discovered: StateFlow<List<BtPrinterDevice>> = empty.asStateFlow()

    actual val isSupported: Boolean = false

    actual suspend fun requestPermissions(): Boolean = false
    actual fun startScan() { state.value = BtScanState.UNSUPPORTED }
    actual fun stopScan() {}
    actual suspend fun printBytes(address: String, payload: ByteArray): String =
        "Bluetooth printing isn't supported on desktop — use Network (LAN)."

    actual companion object {
        private var instance: BtPrinter? = null
        actual fun getInstance(): BtPrinter = instance ?: BtPrinter().also { instance = it }
    }
}

@Composable
actual fun RequestBtPrinterPermissions(onResult: (granted: Boolean) -> Unit) {
    LaunchedEffect(Unit) { onResult(false) }
}
