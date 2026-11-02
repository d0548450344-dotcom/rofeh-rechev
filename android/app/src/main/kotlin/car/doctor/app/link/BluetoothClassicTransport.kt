package car.doctor.app.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import car.doctor.transport.StreamLink
import car.doctor.transport.StreamLinkOpener
import car.doctor.transport.StreamTransport
import car.doctor.transport.TransportLog
import java.io.IOException
import java.util.UUID

/** Serial Port Profile: what almost every Bluetooth Classic ELM327 adapter speaks. */
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

/**
 * RFCOMM link to a paired Bluetooth Classic adapter.
 * Tries the standard secure socket, then the insecure one, then channel 1 directly;
 * many clones only accept one of the three.
 */
class BluetoothClassicTransport(
    context: Context,
    address: String,
    log: TransportLog = TransportLog.NONE,
) : StreamTransport(
    transportType = "bt-classic",
    opener = ClassicOpener(context.applicationContext, address),
    log = log,
)

private class ClassicOpener(
    private val context: Context,
    private val address: String,
) : StreamLinkOpener {
    @SuppressLint("MissingPermission") // The view model requests BLUETOOTH_CONNECT before connecting.
    override fun open(): StreamLink {
        val adapter: BluetoothAdapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("no-bluetooth")
        if (!adapter.isEnabled) throw IOException("bluetooth-off")
        val device = adapter.getRemoteDevice(address)
        runCatching { adapter.cancelDiscovery() }

        val attempts: List<(BluetoothDevice) -> BluetoothSocket> = listOf(
            { it.createRfcommSocketToServiceRecord(SPP_UUID) },
            { it.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            { channelOne(it) },
        )
        var last: Exception? = null
        for (make in attempts) {
            val socket = try {
                make(device)
            } catch (e: Exception) {
                last = e
                continue
            }
            try {
                socket.connect()
                return StreamLink(socket.inputStream, socket.outputStream) { socket.close() }
            } catch (e: IOException) {
                last = e
                runCatching { socket.close() }
            }
        }
        throw IOException(last?.message ?: "rfcomm-failed", last)
    }

    /** Hidden API used by many OBD apps for clones that ignore the SDP record. */
    private fun channelOne(device: BluetoothDevice): BluetoothSocket {
        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        return method.invoke(device, 1) as BluetoothSocket
    }
}
