package car.doctor.live

import car.doctor.core.model.AdapterKind
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
}
