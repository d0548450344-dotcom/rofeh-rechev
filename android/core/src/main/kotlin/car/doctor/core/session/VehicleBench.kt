package car.doctor.core.session

import car.doctor.core.model.DtcStatus
import car.doctor.core.adapter.ObdAdapterFactory
import car.doctor.core.catalog.ignitionLabel
import car.doctor.core.machine.IgnitionMove
import car.doctor.core.machine.IgnitionStateMachine
import car.doctor.core.machine.ObdSessionStateMachine
import car.doctor.core.machine.ignitionMoves
import car.doctor.core.machine.shouldCommitScan
import car.doctor.core.model.ClearConfirmation
import car.doctor.core.model.ClearOutcome
import car.doctor.core.model.DiagnosticSession
import car.doctor.core.visit.GarageVisit
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
    /** True right after a scan on this link that found codes. Clearing needs that saved scan. */
    val canClear: Boolean = false,
    /** A scan on this link just finished and no garage visit is open: it can be the "before" scan. */
    val canStartVisit: Boolean = false,
    /** A visit is open and a newer scan on this link just finished: it can be the "after" scan. */
    val canFinishVisit: Boolean = false,
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

    /** The scan made on the current link. Clearing is allowed only right after it. */
    private var lastScanThisLink: String? = null

    /** Steps applied by the latest operation. Empty when the call was rejected. */
    var lastTransitionTrace: List<ObdSessionState> = emptyList()
        private set

    fun snapshot(): BenchSnapshot = buildSnapshot()

    fun sessions(): List<DiagnosticSession> {
        val id = selected()?.id ?: return emptyList()
        return repository.sessionsFor(id)
    }

    fun vehicles(): List<Vehicle> = repository.vehicles()

    /** Garage visits of the selected vehicle, newest first. */
    fun visits(): List<GarageVisit> {
        val id = selected()?.id ?: return emptyList()
        return repository.visitsFor(id)
    }

    /**
     * Opens a garage visit with the scan that just finished as the "before" picture.
     * The owner writes down what the garage plans to do, in their own words.
     */
    fun startGarageVisit(garageName: String, plannedWork: String): BenchEffect {
        val vehicle = selected() ?: return rejected("אין רכב נבחר.")
        if (openVisit(vehicle.id) != null) return rejected("כבר יש ביקור פתוח במוסך לרכב הזה. סוגרים אותו קודם.")
        val before = freshScan(vehicle.id) ?: return rejected("סורקים קודם, ואז פותחים ביקור: הסריקה הזו תהיה ה\"לפני\".")
        val name = garageName.trim()
        val plan = plannedWork.trim()
        if (name.isEmpty()) return rejected("חסר שם המוסך או נותן השירות.")
        if (plan.isEmpty()) return rejected("כותבים מה מתוכנן לעשות ברכב. זה מה שנשווה אליו אחר כך.")
        repository.upsertVisit(
            GarageVisit(
                id = ids.next("visit"),
                vehicleId = vehicle.id,
                garageName = name.take(80),
                plannedWork = plan.take(1_000),
                openedAt = clock.now(),
                beforeSessionId = before.id,
            ),
        )
        return accepted()
    }

    /** Closes the open visit with the scan that just finished as the "after" picture. */
    fun finishGarageVisit(reportedWork: String): BenchEffect {
        val vehicle = selected() ?: return rejected("אין רכב נבחר.")
        val visit = openVisit(vehicle.id) ?: return rejected("אין ביקור פתוח במוסך.")
        val after = freshScan(vehicle.id) ?: return rejected("סורקים אחרי היציאה מהמוסך, ואז סוגרים את הביקור.")
        if (after.id == visit.beforeSessionId || after.finishedAt <= visit.openedAt) {
            return rejected("צריך סריקה חדשה, מאחרי הביקור. הסריקה האחרונה היא עדיין ה\"לפני\".")
        }
        repository.upsertVisit(
            visit.copy(afterSessionId = after.id, closedAt = clock.now(), reportedWork = reportedWork.trim().take(1_000)),
        )
        return accepted()
    }

    private fun openVisit(vehicleId: String): GarageVisit? = repository.visitsFor(vehicleId).firstOrNull { it.isOpen }

    /** The scan made on the current link, if the link is still in the completed state. */
    private fun freshScan(vehicleId: String): DiagnosticSession? {
        if (obd.state != ObdSessionState.COMPLETE) return null
        val id = lastScanThisLink ?: return null
        return repository.sessionsFor(vehicleId).firstOrNull { it.id == id }
    }

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
            errorHe = "ההצתה ירדה מתחת ל-ON. החיבור נסגר במסלול ניתוק."
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
        lastScanThisLink = null
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

    fun quickScan(): BenchEffect = scan { it }

    /**
     * Quick scan plus VIN, freeze frame, permanent codes and more sensors. Slower, still
     * read-only. Extras that the car does not answer are simply absent.
     */
    fun deepScan(): BenchEffect {
        val vehicle = selected() ?: return rejected("אין רכב נבחר.")
        return scan { session ->
            val extras = adapters.open(vehicle).readDeepExtras(session.finishedAt) ?: return@scan session.copy(kind = "deep")
            session.copy(
                kind = "deep",
                dtcs = session.dtcs + extras.permanentCodes,
                pids = session.pids + extras.extraPids.filter { extra -> session.pids.none { it.id == extra.id } },
                vinRead = extras.vin,
                vinNoteHe = extras.vin?.let { compareVin(vehicle.vin, it) } ?: session.vinNoteHe,
                freezeFrame = extras.freezeFrame,
            )
        }
    }

    /**
     * Clears stored and pending codes (OBD mode 04), then immediately rescans so the user
     * sees what came back. Guards, in order:
     *  1. the user acknowledged both warnings;
     *  2. a scan on this same link just finished and found codes, and it is already saved,
     *     so nothing is lost;
     *  3. the adapter checks on the car that the engine is off and the vehicle is still.
     * Clearing never repairs anything; a real fault sets its code again.
     */
    fun clearCodes(confirmation: ClearConfirmation): BenchEffect {
        if (!confirmation.complete) return rejected("צריך לאשר את שתי האזהרות לפני מחיקה.")
        val vehicle = selected() ?: return rejected("אין רכב נבחר.")
        if (obd.state != ObdSessionState.COMPLETE) {
            return rejected("מחיקה אפשרית רק מיד אחרי סריקה, כשהמתאם עדיין מחובר.")
        }
        val prior = repository.sessionsFor(vehicle.id).firstOrNull { it.id == lastScanThisLink }
            ?: return rejected("סורקים קודם, כדי שהקודים יישמרו לפני המחיקה.")
        if (prior.dtcs.none { it.status != DtcStatus.PERMANENT }) {
            return rejected("אין קודים שאפשר למחוק בסריקה האחרונה. קוד קבוע נמחק רק כשהרכב עצמו מאשר שהתקלה תוקנה.")
        }

        when (val outcome = adapters.open(vehicle).clearCodes(clock.now())) {
            is ClearOutcome.Refused -> return rejected(outcome.messageHe)
            is ClearOutcome.Failed -> {
                errorHe = outcome.messageHe
                return rejected(outcome.messageHe)
            }
            ClearOutcome.Cleared -> Unit
        }
        val cleared = prior.dtcs.filter { it.status != DtcStatus.PERMANENT }.map { it.code }.distinct()
        return scan { it.copy(kind = "post-clear", clearedCodes = cleared, clearedFromSessionId = prior.id) }
    }

    private fun compareVin(typed: String, read: String): String {
        val clean = typed.trim().uppercase()
        return when {
            clean.isEmpty() -> "VIN נקרא מהרכב: $read. בפרופיל לא הוזן VIN."
            clean == read -> "VIN שנקרא מהרכב תואם ל-VIN שבפרופיל."
            else -> "VIN שנקרא מהרכב ($read) שונה מזה שבפרופיל ($clean). ייתכן שמחשב המנוע הוחלף, או שהפרופיל שגוי."
        }
    }

    private fun scan(decorate: (DiagnosticSession) -> DiagnosticSession): BenchEffect {
        val vehicle = selected() ?: return rejected("אין רכב נבחר.")
        if (!obd.canTransition(ObdSessionState.SCANNING)) {
            return rejected("סריקה דורשת חיבור. קודם מתחברים למתאם.")
        }
        resetTrace()
        val startedAt = clock.now()
        val epoch = ++scanEpoch
        move(ObdSessionState.SCANNING)
        if (!shouldCommitScan(epoch, scanEpoch, obd.state)) {
            return rejected("הסריקה הופסקה לפני שהושלמה. לא נשמרה תוצאה חלקית.")
        }
        val finishedAt = clock.now()
        val session = decorate(
            QuickScan.run(
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
            ).copy(labFaults = vehicle.sim.faults.toList(), labLostEcu = vehicle.sim.lostEcu),
        )
        if (!shouldCommitScan(epoch, scanEpoch, obd.state)) {
            return rejected("הסריקה הופסקה לפני שהושלמה. לא נשמרה תוצאה חלקית.")
        }
        repository.appendSession(session)
        lastScanThisLink = session.id
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
        lastScanThisLink = null
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
        canClear = obd.state == ObdSessionState.COMPLETE && lastScanHadCodes(),
        canStartVisit = visitCapability().first,
        canFinishVisit = visitCapability().second,
    )

    private fun visitCapability(): Pair<Boolean, Boolean> {
        val vehicle = selected() ?: return false to false
        val scan = freshScan(vehicle.id) ?: return false to false
        val open = openVisit(vehicle.id) ?: return true to false
        return false to (scan.id != open.beforeSessionId && scan.finishedAt > open.openedAt)
    }

    private fun lastScanHadCodes(): Boolean {
        val id = lastScanThisLink ?: return false
        val vehicle = selected() ?: return false
        return repository.sessionsFor(vehicle.id).firstOrNull { it.id == id }?.dtcs?.any { it.status != DtcStatus.PERMANENT } == true
    }

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
