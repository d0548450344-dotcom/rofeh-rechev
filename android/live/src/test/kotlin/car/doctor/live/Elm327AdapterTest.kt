package car.doctor.live

import car.doctor.core.model.AdapterKind
import car.doctor.core.model.DtcStatus
import car.doctor.core.model.EcuAccess
import car.doctor.core.model.EcuId
import car.doctor.core.model.Fuel
import car.doctor.core.model.IgnitionState
import car.doctor.core.model.MonitorStatus
import car.doctor.core.model.NewVehicle
import car.doctor.core.model.ObdSessionState
import car.doctor.core.model.PidQuality
import car.doctor.core.persistence.SnapshotGarageRepository
import car.doctor.core.session.BenchEffect
import car.doctor.core.session.VehicleBench
import car.doctor.transport.ObdTransport
import car.doctor.transport.TransportEvent
import car.doctor.transport.TransportFailure
import car.doctor.transport.TransportOutcome
import car.doctor.transport.TransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A car behind an ELM327, at the text level. Unknown commands get "?". */
private class FakeCar(private val replies: Map<String, String>) : ObdTransport {
    override val transportType = "fake"
    val sent = mutableListOf<String>()
    private var connected = false
    private val outbox = StringBuilder()
    private val line = StringBuilder()
    var failConnect = false

    override fun connect(): TransportOutcome {
        if (failConnect) return TransportOutcome.Failed(TransportFailure.ConnectionFailed("refused"))
        connected = true
        return TransportOutcome.Ok()
    }

    override fun disconnect(): TransportOutcome {
        connected = false
        return TransportOutcome.Ok()
    }

    override fun isConnected() = connected

    override fun send(payload: ByteArray): TransportOutcome {
        for (b in payload) {
            val c = b.toInt().toChar()
            if (c != '\r') {
                line.append(c)
                continue
            }
            val cmd = line.toString()
            line.clear()
            sent += cmd
            val body = replies[cmd] ?: when {
                cmd == "ATZ" -> "ELM327 v2.1"
                cmd.startsWith("AT") -> "OK"
                else -> "NO DATA"
            }
            outbox.append(body).append("\r\r>")
        }
        return TransportOutcome.Ok()
    }

    override fun receive(maxBytes: Int): TransportOutcome {
        if (!connected) return TransportOutcome.Failed(TransportFailure.Disconnected("receive"))
        val n = minOf(maxBytes, outbox.length)
        val chunk = outbox.substring(0, n)
        outbox.delete(0, n)
        return TransportOutcome.Ok(chunk.toByteArray(Charsets.US_ASCII))
    }

    override fun connectionState() = if (connected) TransportState.CONNECTED else TransportState.DISCONNECTED
    override fun events(): List<TransportEvent> = emptyList()
}

private val RUNNING_CAR = mapOf(
    "0100" to "SEARCHING...\r41 00 BE 3F A8 13",
    "ATDPN" to "A6",
    "010C" to "41 0C 0C 80", // 800 rpm
    "010D" to "41 0D 00",
    "0105" to "41 05 7B", // 83 C
    "0111" to "41 11 26",
    "0142" to "41 42 37 DC", // 14.3 V
    "0101" to "41 01 81 07 65 00", // MIL on, 1 code
    "03" to "43 01 03 00", // P0300
    "07" to "47 00",
)

private fun benchWith(car: FakeCar): VehicleBench {
    val bench = VehicleBench(
        repository = SnapshotGarageRepository(),
        adapters = Elm327AdapterFactory(AdapterKind.CLASSIC, car),
    )
    bench.addVehicle(NewVehicle("טסט", "טויוטה", "קורולה", 2016, Fuel.GASOLINE, ""))
    return bench
}

private val ENGINE_OFF_WITH_CODE = RUNNING_CAR + mapOf("010C" to "41 0C 00 00", "04" to "44")
private val BOTH = car.doctor.core.model.ClearConfirmation(understoodCodesReturn = true, understoodReadinessReset = true)

class Elm327AdapterTest {

    @Test
    fun `clearing with the engine off sends 04 once and rescans`() {
        val car = FakeCar(ENGINE_OFF_WITH_CODE)
        val bench = benchWith(car)
        bench.connect()
        bench.quickScan()

        assertTrue(bench.clearCodes(BOTH) is BenchEffect.Accepted)
        assertEquals(1, car.sent.count { it == "04" })
        val after = bench.sessions().first()
        assertEquals("post-clear", after.kind)
        assertEquals(listOf("P0300"), after.clearedCodes)
        assertEquals(2, bench.sessions().size)
    }

    @Test
    fun `clearing is refused while the engine runs and 04 is never sent`() {
        val car = FakeCar(RUNNING_CAR + mapOf("04" to "44"))
        val bench = benchWith(car)
        bench.connect()
        bench.quickScan()

        val effect = bench.clearCodes(BOTH) as BenchEffect.Rejected
        assertTrue(effect.messageHe.contains("המנוע פועל"))
        assertFalse(car.sent.contains("04"))
    }

    @Test
    fun `an ECU refusal is reported and nothing is marked as cleared`() {
        val car = FakeCar(ENGINE_OFF_WITH_CODE + mapOf("04" to "7F 04 22"))
        val bench = benchWith(car)
        bench.connect()
        bench.quickScan()

        assertTrue(bench.clearCodes(BOTH) is BenchEffect.Rejected)
        assertEquals(1, bench.sessions().size)
    }

    @Test
    fun `a real-car scan goes through the bench and stores a session`() {
        val car = FakeCar(RUNNING_CAR)
        val bench = benchWith(car)

        bench.connect()
        assertEquals(ObdSessionState.CONNECTED, bench.snapshot().session)
        assertTrue(bench.snapshot().protocolHe!!.contains("CAN"))

        assertTrue(bench.quickScan() is BenchEffect.Accepted)
        val session = bench.sessions().first()

        assertEquals(AdapterKind.CLASSIC, session.adapterKind)
        assertEquals(IgnitionState.RUNNING, session.ignition)
        assertEquals(listOf("P0300"), session.dtcs.map { it.code })
        assertEquals(DtcStatus.STORED, session.dtcs.single().status)
        assertEquals(800.0, session.pids.first { it.id == "rpm" }.value!!, 0.01)
        assertEquals(14.3, session.pids.first { it.id == "voltage" }.value!!, 0.01)
        assertEquals(EcuAccess.CHECKED, session.ecus.first { it.id == EcuId.ENGINE }.access)
        assertEquals(EcuAccess.NOT_SCANNED, session.ecus.first { it.id == EcuId.ABS }.access)
        assertEquals(MonitorStatus.COMPLETE, session.readiness.first { it.id == "misfire" }.status)
        assertTrue(session.findings.isNotEmpty())
    }

    @Test
    fun `an unsupported PID is reported as unavailable, not as zero`() {
        val car = FakeCar(RUNNING_CAR - "0111")
        val bench = benchWith(car)
        bench.connect()
        bench.quickScan()

        val throttle = bench.sessions().first().pids.first { it.id == "throttle" }
        assertEquals(PidQuality.UNAVAILABLE, throttle.quality)
        assertNull(throttle.value)
    }

    @Test
    fun `ignition off gives a clear connect error and no session`() {
        val car = FakeCar(mapOf("0100" to "SEARCHING...\rUNABLE TO CONNECT"))
        val bench = benchWith(car)

        bench.connect()
        val snap = bench.snapshot()
        assertEquals(ObdSessionState.ERROR, snap.session)
        assertTrue(snap.errorHe!!.contains("הרכב לא ענה"))
        assertTrue(bench.sessions().isEmpty())
    }

    @Test
    fun `adapter that cannot be reached explains what to check`() {
        val car = FakeCar(RUNNING_CAR).apply { failConnect = true }
        val bench = benchWith(car)

        bench.connect()
        assertNotNull(bench.snapshot().errorHe)
        assertTrue(bench.snapshot().errorHe!!.contains("לא הצלחנו להתחבר למתאם"))
    }

    @Test
    fun `the scan never sends a clear-codes request`() {
        val car = FakeCar(RUNNING_CAR)
        val bench = benchWith(car)
        bench.connect()
        bench.quickScan()
        bench.disconnect()

        assertFalse(car.sent.any { it.replace(" ", "").startsWith("04") })
        assertFalse(car.sent.any { it.isEmpty() })
        assertFalse(car.isConnected())
    }
}
