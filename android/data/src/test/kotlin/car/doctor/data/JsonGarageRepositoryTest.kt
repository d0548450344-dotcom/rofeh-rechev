package car.doctor.data

import car.doctor.core.model.AdapterKind
import car.doctor.core.model.DiagnosticSession
import car.doctor.core.model.Fuel
import car.doctor.core.model.IgnitionState
import car.doctor.core.model.Posture
import car.doctor.core.model.Vehicle
import car.doctor.core.model.VehicleSim
import car.doctor.core.persistence.HISTORY_LIMIT
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonGarageRepositoryTest {
    @Test
    fun historySurvivesANewRepositoryAndACorruptFileDoesNotInventData() {
        val file = Files.createTempDirectory("garage").resolve("garage.json").toFile()
        val first = JsonFileGarageRepository(file)
        assertFalse(first.recoveredFromCorrupt)
        first.upsertVehicle(vehicle())
        first.select("v1")
        first.appendSession(session("scan-1"))
        first.appendSession(session("scan-1"))
        first.appendSession(session("scan-2"))

        val reloaded = JsonFileGarageRepository(file)
        assertEquals("v1", reloaded.selectedId())
        assertEquals("קורולה", reloaded.vehicle("v1")?.nickname)
        assertEquals(IgnitionState.RUNNING, reloaded.vehicle("v1")?.sim?.ignition)
        assertEquals(listOf("scan-2", "scan-1"), reloaded.sessionsFor("v1").map { it.id })

        repeat(HISTORY_LIMIT) { index -> reloaded.appendSession(session("extra-$index")) }
        val capped = JsonFileGarageRepository(file)
        assertEquals(HISTORY_LIMIT, capped.sessionsFor("v1").size)

        file.writeText("{ this is not json")
        val recovered = JsonFileGarageRepository(file)
        assertTrue(recovered.recoveredFromCorrupt)
        assertTrue(recovered.vehicles().isEmpty())
        assertTrue(recovered.sessionsFor("v1").isEmpty())
    }
}

private fun vehicle() = Vehicle(
    id = "v1",
    nickname = "קורולה",
    make = "טויוטה",
    model = "קורולה",
    year = 2016,
    fuel = Fuel.GASOLINE,
    vin = "",
    createdAt = "2026-10-22T12:00:00.000Z",
    sim = VehicleSim(ignition = IgnitionState.RUNNING, posture = Posture.STRAIGHT),
)

private fun session(id: String) = DiagnosticSession(
    id = id,
    vehicleId = "v1",
    adapterKind = AdapterKind.SIMULATED,
    adapterLabelHe = "מתאם OBD מדומה",
    startedAt = "2026-10-22T12:00:00.000Z",
    finishedAt = "2026-10-22T12:00:00.000Z",
    durationMs = 1,
    ignition = IgnitionState.RUNNING,
    posture = Posture.STRAIGHT,
    protocolHe = null,
    vinNoteHe = "ידני",
    ecus = emptyList(),
    gaps = emptyList(),
    dtcs = emptyList(),
    pids = emptyList(),
    readiness = emptyList(),
    findings = emptyList(),
)
