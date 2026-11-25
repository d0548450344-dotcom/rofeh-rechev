package car.doctor.core.persistence

import car.doctor.core.care.ServiceRecord
import car.doctor.core.model.DiagnosticSession
import car.doctor.core.model.Vehicle
import car.doctor.core.visit.GarageVisit
import kotlinx.serialization.Serializable

/**
 * Scans kept across all vehicles. Large enough for long-term trends (about a year of
 * weekly scans for several cars). Scans that a garage visit points to are never dropped.
 */
const val HISTORY_LIMIT = 300

@Serializable
data class GarageSnapshot(
    val vehicles: List<Vehicle> = emptyList(),
    val sessions: List<DiagnosticSession> = emptyList(),
    val selectedId: String? = null,
    val visits: List<GarageVisit> = emptyList(),
    val services: List<ServiceRecord> = emptyList(),
)

/**
 * Persistence port. Room, or any other Android store, can implement this later
 * without the rules or the UI knowing which one is behind it.
 */
interface GarageRepository {
    fun load(): GarageSnapshot
    fun save(snapshot: GarageSnapshot)
    fun upsertVehicle(vehicle: Vehicle)
    fun removeVehicle(id: String)
    fun select(id: String?)
    fun selectedId(): String?
    fun vehicles(): List<Vehicle>
    fun vehicle(id: String): Vehicle?
    fun appendSession(session: DiagnosticSession): List<DiagnosticSession>
    fun sessionsFor(vehicleId: String): List<DiagnosticSession>
    fun upsertVisit(visit: GarageVisit)
    fun visitsFor(vehicleId: String): List<GarageVisit>
    fun upsertService(record: ServiceRecord)
    fun removeService(id: String)

    /** Newest service first. */
    fun servicesFor(vehicleId: String): List<ServiceRecord>
}

/**
 * Process-local store. [onWrite] lets a file-backed repository persist after each change.
 * Callers always receive copies, so a later Room implementation can keep the same contract.
 */
class SnapshotGarageRepository(
    initial: GarageSnapshot = GarageSnapshot(),
    private val onWrite: (GarageSnapshot) -> Unit = {},
) : GarageRepository {
    private var current = copyOf(initial)

    override fun load(): GarageSnapshot = copyOf(current)

    override fun save(snapshot: GarageSnapshot) {
        current = copyOf(snapshot)
        onWrite(current)
    }

    override fun upsertVehicle(vehicle: Vehicle) {
        val vehicles = current.vehicles.toMutableList()
        val index = vehicles.indexOfFirst { it.id == vehicle.id }
        if (index >= 0) vehicles[index] = vehicle else vehicles.add(0, vehicle)
        current = current.copy(vehicles = vehicles.toList())
        onWrite(current)
    }

    override fun removeVehicle(id: String) {
        val selected = if (current.selectedId == id) {
            current.vehicles.firstOrNull { it.id != id }?.id
        } else {
            current.selectedId
        }
        current = current.copy(
            vehicles = current.vehicles.filterNot { it.id == id },
            sessions = current.sessions.filterNot { it.vehicleId == id },
            visits = current.visits.filterNot { it.vehicleId == id },
            services = current.services.filterNot { it.vehicleId == id },
            selectedId = selected,
        )
        onWrite(current)
    }

    override fun select(id: String?) {
        current = current.copy(selectedId = id)
        onWrite(current)
    }

    override fun selectedId(): String? = current.selectedId

    override fun vehicles(): List<Vehicle> = current.vehicles.toList()

    override fun vehicle(id: String): Vehicle? = current.vehicles.find { it.id == id }

    override fun appendSession(session: DiagnosticSession): List<DiagnosticSession> {
        if (current.sessions.any { it.id == session.id }) return sessionsFor(session.vehicleId)
        current = current.copy(sessions = trimmed(listOf(session) + current.sessions))
        onWrite(current)
        return sessionsFor(session.vehicleId)
    }

    override fun upsertVisit(visit: GarageVisit) {
        val visits = current.visits.toMutableList()
        val index = visits.indexOfFirst { it.id == visit.id }
        if (index >= 0) visits[index] = visit else visits.add(0, visit)
        current = current.copy(visits = visits.toList())
        onWrite(current)
    }

    override fun visitsFor(vehicleId: String): List<GarageVisit> =
        current.visits.filter { it.vehicleId == vehicleId }

    override fun upsertService(record: ServiceRecord) {
        val services = current.services.toMutableList()
        val index = services.indexOfFirst { it.id == record.id }
        if (index >= 0) services[index] = record else services.add(record)
        current = current.copy(services = services.toList())
        onWrite(current)
    }

    override fun removeService(id: String) {
        current = current.copy(services = current.services.filterNot { it.id == id })
        onWrite(current)
    }

    override fun servicesFor(vehicleId: String): List<ServiceRecord> =
        current.services.filter { it.vehicleId == vehicleId }.sortedByDescending { it.date }

    /** Newest first, at most [HISTORY_LIMIT], but never a scan a garage visit refers to. */
    private fun trimmed(sessions: List<DiagnosticSession>): List<DiagnosticSession> {
        if (sessions.size <= HISTORY_LIMIT) return sessions
        val pinned = current.visits.flatMap { listOfNotNull(it.beforeSessionId, it.afterSessionId) }.toSet() +
            sessions.mapNotNull { it.clearedFromSessionId }
        val keep = sessions.take(HISTORY_LIMIT).toMutableList()
        keep += sessions.drop(HISTORY_LIMIT).filter { it.id in pinned }
        return keep
    }

    override fun sessionsFor(vehicleId: String): List<DiagnosticSession> =
        current.sessions.filter { it.vehicleId == vehicleId }

    private fun copyOf(snapshot: GarageSnapshot): GarageSnapshot = snapshot.copy(
        vehicles = snapshot.vehicles.toList(),
        sessions = snapshot.sessions.toList(),
        visits = snapshot.visits.toList(),
        services = snapshot.services.toList(),
    )
}
