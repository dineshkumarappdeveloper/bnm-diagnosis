package com.bnm.diagnosis.print

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

/** Discovery state for the Bluetooth printer picker in Settings. */
enum class BtScanState { IDLE, SCANNING, UNSUPPORTED, BLUETOOTH_OFF, PERMISSION_DENIED, ERROR }

/**
 * How confident discovery is that a device is a receipt printer — used to filter the picker so
 * TVs/earbuds/phones don't drown out the actual printer.
 *
 * - [PRINTER] — the device identifies as a printer (Android: Bluetooth IMAGING major class;
 *   iOS: advertises a known printer service UUID).
 * - [UNKNOWN] — can't tell (many cheap ESC/POS printers report no class at all) — shown by
 *   default when the device has a name.
 * - [OTHER] — confidently NOT a printer (audio/phone/computer/wearable…) — hidden by default.
 */
enum class BtDeviceKind { PRINTER, UNKNOWN, OTHER }

/** A Bluetooth printer found during discovery. [address] is a MAC (Android) or a
 *  CBPeripheral.identifier UUID string (iOS). */
data class BtPrinterDevice(
    val name: String?,
    val address: String,
    val kind: BtDeviceKind = BtDeviceKind.UNKNOWN,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: "Unknown printer"
}

/**
 * Bluetooth receipt-printer transport — the third BNMDiagnosis print connection mode alongside
 * System/AirPrint and Network(LAN). Discover → select → and on print, **connect → write ESC/POS
 * bytes → close** (per-print, mirroring [printToNetworkPrinter]).
 *
 * Platforms: **Android** = Classic SPP (RFCOMM socket) — how cheap ESC/POS printers connect;
 * **iOS** = BLE (CoreBluetooth GATT write, chunked) — Apple blocks classic/SPP for non-MFi;
 * **Desktop** = unsupported stub (use Network/LAN there). Follows the app's expect/actual +
 * getInstance()-singleton convention.
 */
expect class BtPrinter {
    companion object { fun getInstance(): BtPrinter }

    /** Whether this platform/device can print over Bluetooth at all. */
    val isSupported: Boolean

    val scanState: StateFlow<BtScanState>
    val discovered: StateFlow<List<BtPrinterDevice>>

    /** Request the runtime permissions needed to scan/connect (Android). Returns granted. */
    suspend fun requestPermissions(): Boolean

    fun startScan()
    fun stopScan()

    /**
     * Connect to the printer at [address], write [payload] (ESC/POS bytes), and close.
     * Returns a short human status for the UI. Never throws — errors come back as a message.
     */
    suspend fun printBytes(address: String, payload: ByteArray): String
}

/**
 * Self-contained Compose permission gate for Bluetooth scanning/printing (Android launches the
 * runtime request; iOS/desktop resolve immediately — iOS prompts lazily on first CoreBluetooth use).
 */
@Composable
expect fun RequestBtPrinterPermissions(onResult: (granted: Boolean) -> Unit)
