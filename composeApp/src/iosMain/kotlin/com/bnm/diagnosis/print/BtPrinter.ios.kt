package com.bnm.diagnosis.print

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.darwin.NSObject

/**
 * iOS Bluetooth printing over **BLE** (CoreBluetooth). Discover peripherals, then per print:
 * connect → discover services → find a writable characteristic → write the ESC/POS bytes in
 * MTU-sized chunks. Apple blocks classic/SPP for non-MFi printers, so BLE is the only option.
 *
 * NOTE: BLE printers vary in which service/characteristic accepts writes and in chunk size; this
 * picks the first writable characteristic and chunks at 180 bytes — good defaults, but expect to
 * tune per printer on a physical device (the simulator has no Bluetooth radio).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class BtPrinter {

    private val _scanState = MutableStateFlow(BtScanState.IDLE)
    actual val scanState: StateFlow<BtScanState> = _scanState.asStateFlow()

    private val _discovered = MutableStateFlow<List<BtPrinterDevice>>(emptyList())
    actual val discovered: StateFlow<List<BtPrinterDevice>> = _discovered.asStateFlow()

    actual val isSupported: Boolean = true

    private val peripherals = mutableMapOf<String, CBPeripheral>()

    private class PrintJob(val chunks: List<NSData>, val done: CompletableDeferred<String>) {
        var writeChar: CBCharacteristic? = null
        var withResponse: Boolean = true
        var index: Int = 0
    }

    private var job: PrintJob? = null

    private val peripheralDelegate = object : NSObject(), CBPeripheralDelegateProtocol {
        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            val j = job ?: return
            if (didDiscoverServices != null) { complete(j, "Bluetooth print failed: ${didDiscoverServices.localizedDescription}"); return }
            val services = peripheral.services?.filterIsInstance<CBService>().orEmpty()
            if (services.isEmpty()) { complete(j, "Printer exposed no services"); return }
            for (s in services) peripheral.discoverCharacteristics(null, forService = s)
        }

        override fun peripheral(peripheral: CBPeripheral, didDiscoverCharacteristicsForService: CBService, error: NSError?) {
            val j = job ?: return
            if (j.writeChar != null) return
            val chars = didDiscoverCharacteristicsForService.characteristics?.filterIsInstance<CBCharacteristic>().orEmpty()
            val withResp = chars.firstOrNull { (it.properties and PROP_WRITE) != 0uL }
            val noResp = chars.firstOrNull { (it.properties and PROP_WRITE_NR) != 0uL }
            val chosen = withResp ?: noResp ?: return
            j.writeChar = chosen
            j.withResponse = withResp != null
            j.index = 0
            writeNext(peripheral, j)
        }

        override fun peripheral(peripheral: CBPeripheral, didWriteValueForCharacteristic: CBCharacteristic, error: NSError?) {
            val j = job ?: return
            if (error != null) { complete(j, "Bluetooth print failed: ${error.localizedDescription}"); return }
            j.index++
            writeNext(peripheral, j)
        }
    }

    private fun writeNext(peripheral: CBPeripheral, j: PrintJob) {
        val char = j.writeChar ?: return
        if (j.withResponse) {
            if (j.index >= j.chunks.size) { complete(j, "Sent to printer"); return }
            peripheral.writeValue(j.chunks[j.index], forCharacteristic = char, type = CBCharacteristicWriteWithResponse)
            // next chunk fires from didWriteValueForCharacteristic
        } else {
            for (c in j.chunks) peripheral.writeValue(c, forCharacteristic = char, type = CBCharacteristicWriteWithoutResponse)
            complete(j, "Sent to printer")
        }
    }

    private fun complete(j: PrintJob, msg: String) { if (!j.done.isCompleted) j.done.complete(msg) }

    private val centralDelegate = object : NSObject(), CBCentralManagerDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            if (central.state != CBManagerStatePoweredOn && _scanState.value == BtScanState.SCANNING)
                _scanState.value = BtScanState.BLUETOOTH_OFF
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            val id = didDiscoverPeripheral.identifier.UUIDString
            peripherals[id] = didDiscoverPeripheral
            if (_discovered.value.any { it.address == id }) return
            // BLE has no device class — a peripheral advertising a known printer service UUID is a
            // printer; everything else stays UNKNOWN (many ESC/POS printers advertise nothing useful).
            val advertised = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? List<*>)
                ?.filterIsInstance<CBUUID>().orEmpty()
            val kind = if (advertised.any { it.UUIDString.uppercase() in PRINTER_SERVICE_UUIDS })
                BtDeviceKind.PRINTER else BtDeviceKind.UNKNOWN
            _discovered.value = _discovered.value + BtPrinterDevice(didDiscoverPeripheral.name, id, kind)
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            didConnectPeripheral.delegate = peripheralDelegate
            didConnectPeripheral.discoverServices(null)
        }

        override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
            job?.let { complete(it, "Bluetooth connect failed: ${error?.localizedDescription ?: "printer unreachable"}") }
        }
    }

    private val central: CBCentralManager by lazy { CBCentralManager(centralDelegate, null) }

    actual suspend fun requestPermissions(): Boolean { central; return true }

    actual fun startScan() {
        _discovered.value = emptyList()
        peripherals.clear()
        if (central.state != CBManagerStatePoweredOn) { _scanState.value = BtScanState.BLUETOOTH_OFF; return }
        central.scanForPeripheralsWithServices(null, null)
        _scanState.value = BtScanState.SCANNING
    }

    actual fun stopScan() {
        try { central.stopScan() } catch (_: Exception) {}
        if (_scanState.value == BtScanState.SCANNING) _scanState.value = BtScanState.IDLE
    }

    actual suspend fun printBytes(address: String, payload: ByteArray): String {
        if (address.isBlank()) return "No Bluetooth printer selected — pick one in Settings ▸ Printer"
        if (central.state != CBManagerStatePoweredOn) return "Turn on Bluetooth to print"
        val peripheral = peripherals[address] ?: return "Printer not found — scan again in Settings ▸ Printer"
        val chunks = payload.toList().chunked(180).map { it.toByteArray().toNSData() }
        if (chunks.isEmpty()) return "Nothing to print"
        val deferred = CompletableDeferred<String>()
        job = PrintJob(chunks, deferred)
        peripheral.delegate = peripheralDelegate
        central.connectPeripheral(peripheral, null)
        val result = withTimeoutOrNull(20_000) { deferred.await() } ?: "Bluetooth print timed out"
        try { central.cancelPeripheralConnection(peripheral) } catch (_: Exception) {}
        job = null
        return result
    }

    private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = this.size.toULong())
    }

    actual companion object {
        private var instance: BtPrinter? = null
        actual fun getInstance(): BtPrinter = instance ?: BtPrinter().also { instance = it }

        // CBCharacteristicProperties bitmask values (write / write-without-response).
        private const val PROP_WRITE: ULong = 8u          // CBCharacteristicPropertyWrite
        private const val PROP_WRITE_NR: ULong = 4u       // CBCharacteristicPropertyWriteWithoutResponse

        /** Service UUIDs commonly advertised by BLE receipt printers (18F0/FF00 = generic ESC/POS
         *  print services; the long UUIDs = common printer-module vendor services). */
        private val PRINTER_SERVICE_UUIDS = setOf(
            "18F0",
            "FF00",
            "E7810A71-73AE-499D-8C15-FAA9AEF0C3F2",
            "49535343-FE7D-4AE5-8FA9-9FAFD205E455",
        )
    }
}

@Composable
actual fun RequestBtPrinterPermissions(onResult: (granted: Boolean) -> Unit) {
    // iOS has no explicit runtime request; the OS prompts on first CoreBluetooth use.
    LaunchedEffect(Unit) { onResult(true) }
}
