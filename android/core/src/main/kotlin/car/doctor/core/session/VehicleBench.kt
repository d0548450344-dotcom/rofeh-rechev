package car.doctor.core.session

import car.doctor.core.adapter.ObdAdapterFactory
import car.doctor.core.catalog.ignitionLabel
import car.doctor.core.machine.IgnitionMove
import car.doctor.core.machine.IgnitionStateMachine
import car.doctor.core.machine.ObdSessionStateMachine
import car.doctor.core.machine.ignitionMoves
import car.doctor.core.machine.shouldCommitScan
import car.doctor.core.model.DiagnosticSession
import car.doctor.core.model.FaultId
import car.doctor.core.model.IgnitionState
import car.doctor.core.model.NewVehicle
import car.doctor.core.model.ObdSessionState
import car.doctor.core.model.Posture
import car.doctor.core.model.Vehicle
import car.doctor.core.model.VehicleSim
import car.doctor.core.persistence.GarageRepository
import car.doctor.core.scan.QuickScan
import car.doctor.core.scan.QuickScanRequest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

fun interface IsoClock {
    fun now(): String
}

fun interface IdSource {
    fun next(prefix: String): String
}

data class BenchCapabilities(
    val canConnect: Boolean,
    val canScan: Boolean,
    val canDisconnect: Boolean,
)

data class BenchSnapshot(
    val vehicle: Vehicle?,
    val ignition: IgnitionState,
    val ignitionMoves: List<IgnitionMove>,
    val session: ObdSessionState,
    val protocolHe: String?,
    val errorHe: String?,
    val posture: Posture,
    val activeFaults: List<FaultId>,
    val historyIds: List<String>,
    val vehicles: List<Vehicle>,
    val capabilities: BenchCapabilities,
)

sealed class BenchEffect {
    abstract val snapshot: BenchSnapshot

    data class Accepted(override val snapshot: BenchSnapshot) : BenchEffect()
    data class Rejected(val messageHe: String, override val snapshot: BenchSnapshot) : BenchEffect()
}

/**
 * Sequences ignition, the OBD session, the adapter and the quick scan.
 * It does not decide what a code means. That stays in [car.doctor.core.rules.DiagnosticRules].
 */
class VehicleBench(
    private val repository: GarageRepository,
    private val adapters: ObdAdapterFactory,
    private val clock: IsoClock = IsoClock { defaultNow() },
    private val ids: IdSource = IdSource { prefix -> "$prefix-${UUID.randomUUID()}" },
) {
    private val obd = ObdSessionStateMachine()
    private var protocolHe: String? = null
    private var errorHe: String? = null
    private var scanEpoch: Long = 0

    /** Steps applied by the latest operation. Empty when the call was rejected. */
    var lastTransitionTrace: List<ObdSessionState> = emptyList()
        private set

    fun snapshot(): BenchSnapshot = buildSnapshot()

    fun sessions(): List<DiagnosticSession> {
        val id = selected()?.id ?: return emptyList()
        return repository.sessionsFor(id)
    }

    fun vehicles(): List<Vehicle> = repository.vehicles()

    fun selectVehicle(id: String): BenchEffect {
        if (repository.vehicle(id) == null) return rejected("הרכב לא נמצא.")
        if (repository.selectedId() == id) return accepted()
        if (!closeLinkIfNeeded()) {
            return rejected("אי אפשר להחליף רכב בלי מסלול ניתוק חוקי.")
        }
        repository.select(id)
        protocolHe = null
        errorHe = null
        return accepted()
    }

    fun addVehicle(input: NewVehicle): BenchEffect {
        if (!closeLinkIfNeeded()) {
            return rejected("אי אפשר להחליף רכב בלי מסלול ניתוק חוקי.")
        }
        val vehicle = Vehicle(
            id = ids.next("veh"),
            nickname = input.nickname.trim().ifEmpty { "רכב" },
            make = input.make.trim(),
            model = input.model.trim(),
            year = input.year,
            fuel = input.fuel,
            vin = input.vin.trim(),
            createdAt = clock.now(),
            sim = VehicleSim(),
        )
        repository.upsertVehicle(vehicle)
        repository.select(vehicle.id)
        protocolHe = null
        errorHe = null
        return accepted()
    }

    fun setIgnition(to: IgnitionState): BenchEffect {
        val vehicle = selected() ?: return rejected("אין רכב נבחר.")
        val from = vehicle.sim.ignition
        if (!IgnitionStateMachine.can(from, to)) {
            return rejected("אי אפשר לעבור ממצב ${ignitionLabel(from)} למצב ${ignitionLabel(to)}.")
        }
        val powerLoss = !IgnitionStateMachine.allowsObd(to) && obd.state != ObdSessionState.DISCONNECTED
        if (powerLoss) {
            val path = ObdSessionStateMachine.disconnectPath(obd.state)
                ?: return rejected("אי אפשר לסגור את החיבור מהמצב הנוכחי. אין מסלול ניתוק חוקי.")
            applyPath(path)
            protocolHe = null
            errorHe = "ההצתה ירדה מתחת ל-ON. החיבור המדומה נסגר במסלול ניתוק."
        } else {
            resetTrace()
            errorHe = null
        }
        repository.upsertVehicle(vehicle.copy(sim = vehicle.sim.copy(ignition = to)))
        return accepted()
    }

    fun setPosture(posture: Posture): BenchEffect {
        val vehicle = selected() ?: return rejected("אין רכב נבחר.")
        repository.upsertVehicle(vehicle.copy(sim = vehicle.sim.copy(posture = posture)))
        return accepted()
    }

    fun toggleFault(fault: FaultId): BenchEffect {
        val vehicle = selected() ?: return rejected("אין רכב נבחר.")
        val faults = if (fault in vehicle.sim.faults) {
            vehicle.sim.faults.filterNot { it == fault }
        } else {
            vehicle.sim.faults + fault
        }
        repository.upsertVehicle(vehicle.copy(sim = vehicle.sim.copy(faults = faults)))
        return accepted()
    }

    fun connect(): BenchEffect {
        val vehicle = selected() ?: return rejected("אין רכב נבחר.")
        if (!obd.canTransition(ObdSessionState.CONNECTING)) {
            return rejected("אי אפשר להתחבר מהמצב הנוכחי. קודם מתנתקים.")
        }
        resetTrace()
        move(ObdSessionState.CONNECTING)
        val result = adapters.open(vehicle).probe()
        if (!result.ok) {
            move(ObdSessionState.ERROR)
            protocolHe = null
            errorHe = result.errorHe
            return accepted()
        }
        move(ObdSessionState.CONNECTED)
        protocolHe = result.protocolHe
        errorHe = null
        return accepted()
    }

    fun disconnect(): BenchEffect {
        val path = ObdSessionStateMachine.disconnectPath(obd.state)
            ?: return rejected("אין מסלול ניתוק חוקי מהמצב הנוכחי.")
        if (path.isEmpty()) {
            releaseTransport()
            return accepted()
        }
        applyPath(path)
        protocolHe = null
        if (errorHe == null || obd.state == ObdSessionState.DISCONNECTED) {
            errorHe = null
        }
        return accepted()
    }

    fun quickScan(): BenchEffect {
        val vehicle = selected() ?: return rejected("אין רכב נבחר.")
        if (!obd.canTransition(ObdSessionState.SCANNING)) {
            return rejected("סריקה דורשת חיבור. קודם מתחברים למתאם המדומה.")
        }
        resetTrace()
        val startedAt = clock.now()
        val epoch = ++scanEpoch
        move(ObdSessionState.SCANNING)
        if (!shouldCommitScan(epoch, scanEpoch, obd.state)) {
            return rejected("הסריקה הופסקה לפני שהושלמה. לא נשמרה תוצאה חלקית.")
        }
        val finishedAt = clock.now()
        val session = QuickScan.run(
            QuickScanRequest(
                adapter = adapters.open(vehicle),
                vehicleId = vehicle.id,
                ignition = vehicle.sim.ignition,
                posture = vehicle.sim.posture,
                id = ids.next("scan"),
                startedAt = startedAt,
                finishedAt = finishedAt,
                durationMs = 0,
            ),
        ).copy(labFaults = vehicle.sim.faults.toList(), labLostEcu = vehicle.sim.lostEcu)
        if (!shouldCommitScan(epoch, scanEpoch, obd.state)) {
            return rejected("הסריקה הופסקה לפני שהושלמה. לא נשמרה תוצאה חלקית.")
        }
        repository.appendSession(session)
        move(ObdSessionState.COMPLETE)
        protocolHe = session.protocolHe
        errorHe = null
        return accepted()
    }

    private fun closeLinkIfNeeded(): Boolean {
        if (obd.state == ObdSessionState.DISCONNECTED) {
            resetTrace()
            releaseTransport()
            return true
        }
        val path = ObdSessionStateMachine.disconnectPath(obd.state) ?: return false
        applyPath(path)
        return true
    }

    private fun applyPath(path: List<ObdSessionState>) {
        resetTrace()
        for (step in path) {
            check(move(step)) { "disconnect step $step is not legal from ${obd.state}" }
        }
        if (obd.state == ObdSessionState.DISCONNECTED) releaseTransport()
    }

    private fun releaseTransport() {
        val vehicle = selected() ?: return
        adapters.open(vehicle).release()
    }

    private fun move(to: ObdSessionState): Boolean {
        if (!obd.transition(to)) return false
        lastTransitionTrace = lastTransitionTrace + obd.state
        return true
    }

    private fun resetTrace() {
        lastTransitionTrace = emptyList()
    }

    private fun selected(): Vehicle? {
        val id = repository.selectedId() ?: return null
        return repository.vehicle(id)
    }

    private fun capabilities(): BenchCapabilities = BenchCapabilities(
        canConnect = obd.canTransition(ObdSessionState.CONNECTING),
        canScan = obd.canTransition(ObdSessionState.SCANNING),
        canDisconnect = ObdSessionStateMachine.disconnectPath(obd.state)?.isNotEmpty() == true,
    )

    private fun buildSnapshot(): BenchSnapshot {
        val vehicle = selected()
        val history = vehicle?.let { repository.sessionsFor(it.id) }.orEmpty()
        return BenchSnapshot(
            vehicle = vehicle,
            ignition = vehicle?.sim?.ignition ?: IgnitionState.OFF,
            ignitionMoves = vehicle?.let { ignitionMoves(it.sim.ignition) }.orEmpty(),
            session = obd.state,
            protocolHe = protocolHe,
            errorHe = errorHe,
            posture = vehicle?.sim?.posture ?: Posture.IDLE,
            activeFaults = vehicle?.sim?.faults.orEmpty(),
            historyIds = history.map { it.id },
            vehicles = repository.vehicles(),
            capabilities = capabilities(),
        )
    }

    private fun accepted(): BenchEffect.Accepted = BenchEffect.Accepted(buildSnapshot())

    private fun rejected(messageHe: String): BenchEffect.Rejected {
        resetTrace()
        return BenchEffect.Rejected(messageHe, buildSnapshot())
    }

    companion object {
        private val ISO: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        fun defaultNow(): String = ISO.format(Instant.now().truncatedTo(ChronoUnit.MILLIS))
    }
}
