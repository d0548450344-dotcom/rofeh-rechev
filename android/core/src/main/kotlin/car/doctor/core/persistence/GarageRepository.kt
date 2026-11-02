package car.doctor.core.persistence

import car.doctor.core.model.DiagnosticSession
import car.doctor.core.model.Vehicle
import kotlinx.serialization.Serializable

const val HISTORY_LIMIT = 40

@Serializable
data class GarageSnapshot(
    val vehicles: List<Vehicle> = emptyList(),
    val sessions: List<DiagnosticSession> = emptyList(),
    val selectedId: String? = null,
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
        current = current.copy(sessions = (listOf(session) + current.sessions).take(HISTORY_LIMIT))
        onWrite(current)
        return sessionsFor(session.vehicleId)
    }

    override fun sessionsFor(vehicleId: String): List<DiagnosticSession> =
        current.sessions.filter { it.vehicleId == vehicleId }

    private fun copyOf(snapshot: GarageSnapshot): GarageSnapshot = snapshot.copy(
        vehicles = snapshot.vehicles.toList(),
        sessions = snapshot.sessions.toList(),
    )
}
