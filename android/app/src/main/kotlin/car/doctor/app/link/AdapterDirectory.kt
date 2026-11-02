package car.doctor.app.link

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper

enum class BluetoothStatus { READY, OFF, MISSING }

/**
 * Finds adapters the user can pick: phones list paired Bluetooth devices, and a short
 * BLE scan finds adapters that never pair. Only used from the main thread.
 */
@SuppressLint("MissingPermission") // Callers check [missingPermissions] first.
class AdapterDirectory(context: Context) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(BluetoothManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var scanning: ScanCallback? = null

    fun status(): BluetoothStatus {
        val adapter = manager?.adapter ?: return BluetoothStatus.MISSING
        return if (adapter.isEnabled) BluetoothStatus.READY else BluetoothStatus.OFF
    }

    /** Runtime permissions still needed to list paired devices and connect. */
    fun connectPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= 31) missing(Manifest.permission.BLUETOOTH_CONNECT) else emptyList()

    /** Runtime permissions still needed for a BLE scan. */
    fun scanPermissions(): List<String> = if (Build.VERSION.SDK_INT >= 31) {
        missing(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        missing(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun paired(): List<FoundAdapter> {
        val adapter = manager?.adapter ?: return emptyList()
        if (!adapter.isEnabled || connectPermissions().isNotEmpty()) return emptyList()
        return adapter.bondedDevices.orEmpty()
            .map { device ->
                FoundAdapter(
                    name = device.name ?: device.address,
                    address = device.address,
                    ble = device.type == BluetoothDevice.DEVICE_TYPE_LE,
                    paired = true,
                )
            }
            .sortedByDescending { looksLikeObd(it.name) }
    }

    /** Scans for BLE adapters for [seconds], reporting each new one, then calls [done]. */
    fun scanBle(seconds: Int, onFound: (FoundAdapter) -> Unit, done: () -> Unit) {
        stopScan()
        val scanner = manager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null || scanPermissions().isNotEmpty()) {
            done()
            return
        }
        val reported = mutableSetOf<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                if (!reported.add(result.device.address)) return
                main.post { onFound(FoundAdapter(name, result.device.address, ble = true, paired = false)) }
            }
        }
        scanning = callback
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(null, settings, callback) }.onFailure {
            scanning = null
            done()
            return
        }
        main.postDelayed({
            stopScan()
            done()
        }, seconds * 1_000L)
    }

    fun stopScan() {
        val callback = scanning ?: return
        scanning = null
        runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(callback) }
    }

    private fun missing(vararg permissions: String): List<String> = permissions.filter {
        context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }
}
