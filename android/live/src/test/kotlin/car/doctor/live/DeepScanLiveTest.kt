package car.doctor.live

import car.doctor.core.model.AdapterKind
import car.doctor.core.model.ModuleKind
import car.doctor.protocol.Elm327Protocol
import car.doctor.core.model.ClearConfirmation
import car.doctor.core.model.DtcStatus
import car.doctor.core.model.Fuel
import car.doctor.core.model.NewVehicle
import car.doctor.core.persistence.SnapshotGarageRepository
import car.doctor.core.session.BenchEffect
import car.doctor.core.session.VehicleBench
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val DEEP_CAR = mapOf(
    "0100" to "41 00 BE 3E B8 11",
    "010C" to "41 0C 0C 80",
    "010D" to "41 0D 00",
    "0105" to "41 05 7B",
    "0111" to "41 11 26",
    "0142" to "41 42 37 DC",
    "0101" to "41 01 81 07 65 00",
    "03" to "43 01 03 00",
    "07" to "47 00",
    "0104" to "41 04 40", // 25 %
    "0106" to "41 06 8C", // +9.4 %
    "0902" to "49 02 01 4A 54 32 41 45 39 32 45 30 30 30 30 30 30 30 30 31",
    "0A" to "4A 01 01 71", // P0171 permanent
    "020200" to "42 02 00 03 00", // freeze frame for P0300
    "020C00" to "42 0C 00 1F 40", // 2000 rpm
    "020500" to "42 05 00 64", // 60 C
)

private fun deepBench(car: FakeCar, vin: String = ""): VehicleBench {
    val bench = VehicleBench(SnapshotGarageRepository(), Elm327AdapterFactory(AdapterKind.CLASSIC, car))
    bench.addVehicle(NewVehicle("טסט", "טויוטה", "קורולה", 2016, Fuel.GASOLINE, vin))
    return bench
}

class DeepScanLiveTest {

    @Test
    fun `deep scan reads VIN, freeze frame, permanent codes and extra PIDs`() {
        val car = FakeCar(DEEP_CAR)
        val bench = deepBench(car, vin = "JT2AE92E000000001")
        bench.connect()
        assertTrue(bench.deepScan() is BenchEffect.Accepted)

        val s = bench.sessions().first()
        assertEquals("deep", s.kind)
        assertEquals("JT2AE92E000000001", s.vinRead)
        assertTrue(s.vinNoteHe!!.contains("תואם"))
        assertEquals("P0300", s.freezeFrame!!.code)
        assertEquals(2000.0, s.freezeFrame!!.values.first { it.id == "ff_rpm" }.value!!, 0.1)
        assertEquals(60.0, s.freezeFrame!!.values.first { it.id == "ff_coolant" }.value!!, 0.1)
        assertTrue(s.dtcs.any { it.code == "P0171" && it.status == DtcStatus.PERMANENT })
        assertEquals(25.1, s.pids.first { it.id == "load" }.value!!, 0.1)
        assertNull(s.pids.firstOrNull { it.id == "maf" }) // NO DATA → left out, not zero
    }

    @Test
    fun `deep scan sends only read-only services`() {
        val car = FakeCar(DEEP_CAR)
        val bench = deepBench(car)
        bench.connect()
        bench.deepScan()
        val obd = car.sent.filterNot { it.startsWith("AT") }.map { it.take(2) }.toSet()
        assertTrue(obd.all { it in setOf("01", "02", "03", "07", "09", "0A") })
    }

    @Test
    fun `only permanent codes cannot be cleared`() {
        val car = FakeCar(DEEP_CAR + mapOf("03" to "43 00", "010C" to "41 0C 00 00"))
        val bench = deepBench(car)
        bench.connect()
        bench.deepScan()
        assertFalse(bench.snapshot().capabilities.canClear)
        val both = ClearConfirmation(understoodCodesReturn = true, understoodReadinessReset = true)
        assertTrue(bench.clearCodes(both) is BenchEffect.Rejected)
        assertFalse(car.sent.any { it == "04" })
    }

    @Test
    fun `a typed VIN that differs from the car is flagged`() {
        val car = FakeCar(DEEP_CAR)
        val bench = deepBench(car, vin = "WVWZZZ1KZAW000001")
        bench.connect()
        bench.deepScan()
        assertNotNull(bench.sessions().first().vinNoteHe)
        assertTrue(bench.sessions().first().vinNoteHe!!.contains("שונה"))
    }

    @Test
    fun `the communication log records what was sent and answered`() {
        val car = FakeCar(DEEP_CAR)
        val factory = Elm327AdapterFactory(AdapterKind.CLASSIC, car)
        val bench = VehicleBench(SnapshotGarageRepository(), factory)
        bench.addVehicle(NewVehicle("טסט", "טויוטה", "קורולה", 2016, Fuel.GASOLINE, ""))
        bench.connect()
        bench.quickScan()
        val log = factory.protocol.wireLog
        assertTrue(log.any { it.contains("→ ATZ") })
        assertTrue(log.any { it.contains("→ 03") })
        assertTrue(log.any { it.contains("← 43 01 03 00") })
        assertTrue(log.size <= Elm327Protocol.WIRE_LOG_LINES)
    }

    @Test
    fun `deep scan reads ABS and airbag codes for a known make and restores the header`() {
        val car = FakeCar(
            DEEP_CAR + mapOf(
                "ATDPN" to "A6",
                "7B0:1902FF" to "59 02 FF 40 35 1F 09", // ABS: C0035-1F confirmed
                // 780 (airbags) does not answer at all
            ),
        )
        val bench = deepBench(car)
        bench.connect()
        bench.deepScan()
        val modules = bench.sessions().first().modules
        val abs = modules.first { it.kind == ModuleKind.ABS }
        assertTrue(abs.reached)
        assertEquals("C0035-1F", abs.faults.single().label)
        val srs = modules.first { it.kind == ModuleKind.SRS }
        assertFalse(srs.reached)
        // every header change is undone, and nothing but reads went to a module
        val i = car.sent.lastIndexOf("ATSH 780")
        assertEquals("ATSH 7DF", car.sent.drop(i).first { it.startsWith("ATSH") && it != "ATSH 780" })
        assertTrue(car.sent.filter { it.matches(Regex("^1[0-9A-F].*")) }.all { it.startsWith("1902") || it.startsWith("1802") })
        assertFalse(car.sent.any { it.startsWith("14") || it.startsWith("10") || it.startsWith("27") })
    }

    @Test
    fun `unknown makes are not probed`() {
        val car = FakeCar(DEEP_CAR + mapOf("ATDPN" to "A6"))
        val bench = VehicleBench(SnapshotGarageRepository(), Elm327AdapterFactory(AdapterKind.CLASSIC, car))
        bench.addVehicle(NewVehicle("טסט", "סובארו", "אימפרזה", 2016, Fuel.GASOLINE, ""))
        bench.connect()
        bench.deepScan()
        assertTrue(bench.sessions().first().modules.isEmpty())
        assertFalse(car.sent.any { it.startsWith("ATSH") })
    }
}
