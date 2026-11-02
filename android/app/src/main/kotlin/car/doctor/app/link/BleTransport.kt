package car.doctor.app.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import car.doctor.transport.ObdTransport
import car.doctor.transport.TransportEvent
import car.doctor.transport.TransportFailure
import car.doctor.transport.TransportLog
import car.doctor.transport.TransportOutcome
import car.doctor.transport.TransportState
import car.doctor.transport.TransportStateMachine
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** Known (service, notify, write) layouts of BLE OBD adapters, most common first. */
private val KNOWN_LAYOUTS: List<Triple<UUID, UUID, UUID>> = listOf(
    Triple(uuid16(0xFFF0), uuid16(0xFFF1), uuid16(0xFFF2)),
    Triple(uuid16(0xFFE0), uuid16(0xFFE1), uuid16(0xFFE1)),
    Triple(uuid16(0x18F0), uuid16(0x2AF0), uuid16(0x2AF1)),
    Triple(
        UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2"),
        UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"),
        UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"),
    ),
)

private val STANDARD_SERVICES = setOf(uuid16(0x1800), uuid16(0x1801), uuid16(0x180A), uuid16(0x180F))

private fun uuid16(short: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(short))

/**
 * BLE link to an ELM327-style adapter: one characteristic we write commands to and
 * one that notifies replies. Blocking surface, like the other transports.
 */
@SuppressLint("MissingPermission") // The view model requests BLUETOOTH_CONNECT before connecting.
class BleTransport(
    context: Context,
    private val address: String,
    private val log: TransportLog = TransportLog.NONE,
    private val connectTimeoutMs: Long = 12_000,
) : ObdTransport {
    override val transportType: String = "ble"

    private val context = context.applicationContext
    private val machine = TransportStateMachine()
    private val seen = mutableListOf<TransportEvent>()
    private val inbox = LinkedBlockingQueue<ByteArray>()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writer: BluetoothGattCharacteristic? = null
    @Volatile private var ready: CountDownLatch = CountDownLatch(1)
    @Volatile private var writeDone: CountDownLatch = CountDownLatch(0)
    @Volatile private var setupError: String? = null
    @Volatile private var dropped = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                if (ready.count > 0) {
                    setupError = "gatt-disconnected-$status"
                    ready.countDown()
                } else {
                    dropped = true
                }
                writeDone.countDown()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("discovery-$status")
            val (notify, write) = pick(g) ?: return fail("no-obd-service")
            writer = write
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD) ?: return ready.countDown() // some clones notify without it
            val enable = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, enable)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enable
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (d.uuid == CCCD) ready.countDown()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone.countDown()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            inbox.offer(value.copyOf())
        }

        @Deprecated("Android 12 and below")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            c.value?.let { inbox.offer(it.copyOf()) }
        }

        private fun fail(reason: String) {
            setupError = reason
            ready.countDown()
        }
    }

    @Synchronized
    override fun connect(): TransportOutcome {
        if (machine.state == TransportState.CONNECTED) return TransportOutcome.Ok()
        if (!machine.transition(TransportState.CONNECTING)) return refuse(TransportState.CONNECTING)
        log.connect(TransportState.CONNECTING.name)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return failConnect("no-bluetooth")
        if (!adapter.isEnabled) return failConnect("bluetooth-off")
        inbox.clear()
        setupError = null
        dropped = false
        ready = CountDownLatch(1)
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return failConnect("bad-address")
        }
        gatt = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            return failConnect("permission")
        }
        if (!ready.await(connectTimeoutMs, TimeUnit.MILLISECONDS)) {
            closeGatt()
            machine.transition(TransportState.ERROR)
            seen += TransportEvent.Timeout("connect")
            log.timeout("connect")
            return TransportOutcome.Failed(TransportFailure.Timeout("ble-connect"))
        }
        setupError?.let {
            closeGatt()
            return failConnect(it)
        }
        check(machine.transition(TransportState.CONNECTED))
        seen += TransportEvent.Connected
        return TransportOutcome.Ok()
    }

    @Synchronized
    override fun disconnect(): TransportOutcome {
        closeGatt()
        return when (machine.state) {
            TransportState.DISCONNECTED -> TransportOutcome.Ok()
            TransportState.CONNECTED -> {
                machine.transition(TransportState.DISCONNECTING)
                machine.transition(TransportState.DISCONNECTED)
                seen += TransportEvent.Disconnected(clean = true)
                log.disconnect("clean")
                TransportOutcome.Ok()
            }
            TransportState.ERROR -> {
                machine.transition(TransportState.DISCONNECTED)
                TransportOutcome.Ok()
            }
            else -> refuse(TransportState.DISCONNECTED)
        }
    }

    override fun isConnected(): Boolean = machine.state == TransportState.CONNECTED && !dropped

    @Synchronized
    override fun send(payload: ByteArray): TransportOutcome {
        val g = gatt
        val c = writer
        if (!isConnected() || g == null || c == null) {
            if (dropped) lose()
            return TransportOutcome.Failed(TransportFailure.Disconnected("send"))
        }
        val noResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val type = if (noResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        for (chunk in payload.toList().chunked(BLE_CHUNK)) {
            val bytes = chunk.toByteArray()
            writeDone = CountDownLatch(1)
            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, bytes, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = bytes
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            if (!accepted) return TransportOutcome.Failed(TransportFailure.TransportError("ble-write"))
            if (!writeDone.await(2, TimeUnit.SECONDS)) {
                return TransportOutcome.Failed(TransportFailure.Timeout("ble-write"))
            }
            if (dropped) return lose()
        }
        return TransportOutcome.Ok()
    }

    override fun receive(maxBytes: Int): TransportOutcome {
        if (maxBytes <= 0) return TransportOutcome.Failed(TransportFailure.UnsupportedOperation("receive"))
        if (dropped) return synchronized(this) { lose() }
        if (machine.state != TransportState.CONNECTED) {
            return TransportOutcome.Failed(TransportFailure.Disconnected("receive"))
        }
        val first = inbox.poll(40, TimeUnit.MILLISECONDS) ?: return TransportOutcome.Ok(byteArrayOf())
        val out = ArrayList<Byte>(first.size)
        first.forEach { out += it }
        while (out.size < maxBytes) {
            val next = inbox.poll() ?: break
            next.forEach { out += it }
        }
        return TransportOutcome.Ok(out.toByteArray())
    }

    override fun connectionState(): TransportState = machine.state

    override fun events(): List<TransportEvent> = seen.toList()

    private fun pick(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        for ((service, notify, write) in KNOWN_LAYOUTS) {
            val s = g.getService(service) ?: continue
            val n = s.getCharacteristic(notify) ?: continue
            val w = s.getCharacteristic(write) ?: continue
            return n to w
        }
        for (s in g.services.filter { it.uuid !in STANDARD_SERVICES }) {
            val n = s.characteristics.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            } ?: continue
            val w = s.characteristics.firstOrNull {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            } ?: continue
            return n to w
        }
        return null
    }

    private fun closeGatt() {
        val g = gatt
        gatt = null
        writer = null
        if (g != null) {
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
    }

    private fun failConnect(detail: String): TransportOutcome {
        machine.transition(TransportState.ERROR)
        seen += TransportEvent.ConnectionFailed(detail)
        log.error("connection_failed", "ble")
        return TransportOutcome.Failed(TransportFailure.ConnectionFailed(detail))
    }

    private fun lose(): TransportOutcome {
        closeGatt()
        if (machine.state == TransportState.CONNECTED) {
            machine.transition(TransportState.ERROR)
            seen += TransportEvent.Disconnected(clean = false)
            log.error("transport", "ble-dropped")
        }
        return TransportOutcome.Failed(TransportFailure.Disconnected("ble-dropped"))
    }

    private fun refuse(attempted: TransportState): TransportOutcome =
        TransportOutcome.Failed(TransportFailure.InvalidState(machine.state, attempted))

    private companion object {
        /** Default ATT MTU leaves 20 bytes per write. ELM commands are shorter anyway. */
        const val BLE_CHUNK = 20
    }
}
