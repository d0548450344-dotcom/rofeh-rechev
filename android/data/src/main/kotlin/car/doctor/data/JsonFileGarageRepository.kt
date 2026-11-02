package car.doctor.data

import car.doctor.core.model.DiagnosticSession
import car.doctor.core.model.Vehicle
import car.doctor.core.persistence.GarageRepository
import car.doctor.core.persistence.GarageSnapshot
import car.doctor.core.persistence.SnapshotGarageRepository
import java.io.File
import kotlinx.serialization.json.Json

/**
 * Local file store behind [GarageRepository].
 * Room can replace this class later. Callers keep using the same port.
 * A corrupt file is treated as an empty garage, not as a successful diagnosis.
 */
class JsonFileGarageRepository(private val file: File) : GarageRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
    private val inner: SnapshotGarageRepository

    var recoveredFromCorrupt: Boolean = false
        private set

    init {
        val loaded = readOrEmpty()
        inner = SnapshotGarageRepository(loaded) { snapshot ->
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(GarageSnapshot.serializer(), snapshot))
        }
    }

    override fun load(): GarageSnapshot = inner.load()
    override fun save(snapshot: GarageSnapshot) = inner.save(snapshot)
    override fun upsertVehicle(vehicle: Vehicle) = inner.upsertVehicle(vehicle)
    override fun removeVehicle(id: String) = inner.removeVehicle(id)
    override fun select(id: String?) = inner.select(id)
    override fun selectedId(): String? = inner.selectedId()
    override fun vehicles(): List<Vehicle> = inner.vehicles()
    override fun vehicle(id: String): Vehicle? = inner.vehicle(id)
    override fun appendSession(session: DiagnosticSession): List<DiagnosticSession> = inner.appendSession(session)
    override fun sessionsFor(vehicleId: String): List<DiagnosticSession> = inner.sessionsFor(vehicleId)

    private fun readOrEmpty(): GarageSnapshot {
        if (!file.isFile) return GarageSnapshot()
        return runCatching {
            json.decodeFromString(GarageSnapshot.serializer(), file.readText())
        }.getOrElse {
            recoveredFromCorrupt = true
            GarageSnapshot()
        }
    }
}
