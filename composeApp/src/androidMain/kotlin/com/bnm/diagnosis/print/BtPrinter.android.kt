package com.bnm.diagnosis.print

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

private var btContext: Context? = null

/** Call once from MainActivity (mirrors initPrintContext). */
fun initBtPrinterContext(context: Context) { btContext = context.applicationContext }

/** Android Bluetooth printing over Classic SPP (RFCOMM). Discover devices, then per print open an
 *  RFCOMM socket to the SPP UUID and stream the ESC/POS bytes. */
actual class BtPrinter {

    private val adapter: BluetoothAdapter?
        get() = (btContext?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _scanState = MutableStateFlow(BtScanState.IDLE)
    actual val scanState: StateFlow<BtScanState> = _scanState.asStateFlow()

    private val _discovered = MutableStateFlow<List<BtPrinterDevice>>(emptyList())
    actual val discovered: StateFlow<List<BtPrinterDevice>> = _discovered.asStateFlow()

    actual val isSupported: Boolean get() = adapter != null

    private var receiver: BroadcastReceiver? = null

    private fun granted(p: String): Boolean {
        val ctx = btContext ?: return false
        return ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasPerms(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        granted(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    actual suspend fun requestPermissions(): Boolean = hasPerms()

    @SuppressLint("MissingPermission")
    actual fun startScan() {
        val ctx = btContext; val a = adapter
        if (ctx == null || a == null) { _scanState.value = BtScanState.UNSUPPORTED; return }
        if (!a.isEnabled) { _scanState.value = BtScanState.BLUETOOTH_OFF; return }
        if (!hasPerms()) { _scanState.value = BtScanState.PERMISSION_DENIED; return }
        _discovered.value = bonded()
        try {
            if (receiver == null) {
                receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) {
                        when (i?.action) {
                            BluetoothDevice.ACTION_FOUND -> {
                                @Suppress("DEPRECATION")
                                val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                                d?.let { add(it) }
                            }
                            BluetoothAdapter.ACTION_DISCOVERY_FINISHED ->
                                if (_scanState.value == BtScanState.SCANNING) _scanState.value = BtScanState.IDLE
                        }
                    }
                }
                ctx.registerReceiver(receiver, IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                })
            }
            if (a.isDiscovering) a.cancelDiscovery()
            a.startDiscovery()
            _scanState.value = BtScanState.SCANNING
        } catch (e: SecurityException) {
            _scanState.value = BtScanState.PERMISSION_DENIED
        } catch (e: Exception) {
            _scanState.value = BtScanState.ERROR
        }
    }

    @SuppressLint("MissingPermission")
    private fun nameOf(d: BluetoothDevice): String? = try { d.name } catch (e: SecurityException) { null }

    /** IMAGING class ⇒ printer; no/uncategorized class ⇒ unknown (cheap ESC/POS printers often
     *  report none); any other major class (audio, phone, computer…) ⇒ confidently not a printer. */
    @SuppressLint("MissingPermission")
    private fun kindOf(d: BluetoothDevice): BtDeviceKind = try {
        when (d.bluetoothClass?.majorDeviceClass) {
            BluetoothClass.Device.Major.IMAGING -> BtDeviceKind.PRINTER
            null,
            BluetoothClass.Device.Major.UNCATEGORIZED,
            BluetoothClass.Device.Major.MISC -> BtDeviceKind.UNKNOWN
            else -> BtDeviceKind.OTHER
        }
    } catch (e: SecurityException) { BtDeviceKind.UNKNOWN }

    @SuppressLint("MissingPermission")
    private fun bonded(): List<BtPrinterDevice> = try {
        adapter?.bondedDevices.orEmpty().map { BtPrinterDevice(nameOf(it), it.address, kindOf(it)) }
    } catch (e: Exception) { emptyList() }

    private fun add(d: BluetoothDevice) {
        val addr = d.address ?: return
        val cur = _discovered.value
        if (cur.any { it.address == addr }) return
        _discovered.value = cur + BtPrinterDevice(nameOf(d), addr, kindOf(d))
    }

    @SuppressLint("MissingPermission")
    actual fun stopScan() {
        try { adapter?.let { if (it.isDiscovering) it.cancelDiscovery() } } catch (_: Exception) {}
        receiver?.let { r -> try { btContext?.unregisterReceiver(r) } catch (_: Exception) {} }
        receiver = null
        if (_scanState.value == BtScanState.SCANNING) _scanState.value = BtScanState.IDLE
    }

    @SuppressLint("MissingPermission")
    actual suspend fun printBytes(address: String, payload: ByteArray): String = withContext(Dispatchers.Default) {
        val a = adapter ?: return@withContext "Bluetooth not available on this device"
        if (!hasPerms()) return@withContext "Bluetooth permission needed — grant it in Settings"
        if (address.isBlank()) return@withContext "No Bluetooth printer selected — pick one in Settings ▸ Printer"
        try { if (a.isDiscovering) a.cancelDiscovery() } catch (_: Exception) {}
        val device = try { a.getRemoteDevice(address) } catch (e: Exception) {
            return@withContext "Printer not found: ${e.message}"
        }
        var socket: BluetoothSocket? = null
        try {
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            socket.connect()
            socket.outputStream.apply { write(payload); flush() }
            "Sent to ${nameOf(device) ?: address}"
        } catch (e: SecurityException) {
            "Bluetooth permission denied"
        } catch (e: Exception) {
            "Bluetooth print failed: ${e.message ?: "couldn't connect"}"
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    actual companion object {
        private var instance: BtPrinter? = null
        actual fun getInstance(): BtPrinter = instance ?: BtPrinter().also { instance = it }

        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

@Composable
actual fun RequestBtPrinterPermissions(onResult: (granted: Boolean) -> Unit) {
    val perms = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        onResult(perms.all { res[it] == true })
    }
    LaunchedEffect(Unit) { launcher.launch(perms) }
}
