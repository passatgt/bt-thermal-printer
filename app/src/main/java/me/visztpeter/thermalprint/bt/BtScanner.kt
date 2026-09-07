package me.visztpeter.thermalprint.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DeviceEntry(
    val address: String,
    val name: String,
    val bonded: Boolean,
    val likelyPrinter: Boolean,
)

/**
 * Finds pairable printers: already-bonded devices, plus a combined Classic + LE scan,
 * because these printers are split roughly 50/50 between the two radios.
 */
@SuppressLint("MissingPermission")
class BtScanner(private val context: Context) {

    private val _devices = MutableStateFlow<List<DeviceEntry>>(emptyList())
    val devices: StateFlow<List<DeviceEntry>> = _devices

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val found = linkedMapOf<String, DeviceEntry>()
    private var registered = false

    private val adapter: BluetoothAdapter? get() = ThermalPrinter.adapterOrNull(context)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    device(intent)?.let { add(it) }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    device(intent)?.let { add(it) }
                    refreshBonded()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _scanning.value = false
            }
        }

        @Suppress("DEPRECATION")
        private fun device(intent: Intent): BluetoothDevice? =
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }

    private val leCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            add(result.device, result.scanRecord?.deviceName)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "BLE scan failed: $errorCode")
        }
    }

    fun start() {
        if (registered) return
        context.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            },
        )
        registered = true
        refreshBonded()
    }

    fun stop() {
        stopScan()
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
    }

    fun refreshBonded() {
        val a = adapter ?: return
        runCatching { a.bondedDevices }.getOrNull()?.forEach { add(it) }
        publish()
    }

    fun startScan() {
        val a = adapter ?: return
        if (!a.isEnabled) return
        _scanning.value = true
        runCatching { a.cancelDiscovery() }
        runCatching { a.startDiscovery() }
        runCatching {
            a.bluetoothLeScanner?.startScan(
                emptyList(),
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                leCallback,
            )
        }
    }

    fun stopScan() {
        val a = adapter ?: return
        runCatching { a.cancelDiscovery() }
        runCatching { a.bluetoothLeScanner?.stopScan(leCallback) }
        _scanning.value = false
    }

    /** Kicks off system pairing; returns false if the request couldn't be started. */
    fun pair(address: String): Boolean {
        val a = adapter ?: return false
        stopScan()
        val device = runCatching { a.getRemoteDevice(address) }.getOrNull() ?: return false
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true
        return runCatching { device.createBond() }.getOrDefault(false)
    }

    fun isBonded(address: String): Boolean =
        adapter?.let { a ->
            runCatching { a.getRemoteDevice(address).bondState == BluetoothDevice.BOND_BONDED }
                .getOrDefault(false)
        } ?: false

    private fun add(device: BluetoothDevice, advertisedName: String? = null) {
        val name = runCatching { device.name }.getOrNull()
            ?: advertisedName
            ?: "Unknown device"
        val bonded = runCatching { device.bondState == BluetoothDevice.BOND_BONDED }
            .getOrDefault(false)
        found[device.address] = DeviceEntry(
            address = device.address,
            name = name,
            bonded = bonded,
            likelyPrinter = looksLikePrinter(device, name),
        )
        publish()
    }

    private fun looksLikePrinter(device: BluetoothDevice, name: String): Boolean {
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
        if (major == BluetoothClass.Device.Major.IMAGING) return true
        val n = name.lowercase()
        return listOf("print", "pos", "ptr", "rpp", "mtp", "bt-", "thermal", "hop", "gp-", "xp-")
            .any { n.contains(it) }
    }

    private fun publish() {
        // Likely printers first, then anything already paired, then by name.
        _devices.value = found.values.sortedWith(
            compareByDescending<DeviceEntry> { it.likelyPrinter }
                .thenByDescending { it.bonded }
                .thenBy { it.name.lowercase() }
        )
    }

    companion object {
        private const val TAG = "BtScanner"
    }
}
