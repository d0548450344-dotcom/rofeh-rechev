package car.doctor.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import car.doctor.core.catalog.fuelLabel
import car.doctor.core.catalog.ignitionLabel
import car.doctor.core.catalog.postureLabel
import car.doctor.core.catalog.sessionLabel
import car.doctor.core.catalog.vinNote
import car.doctor.core.machine.IgnitionMove
import car.doctor.core.model.FaultId
import car.doctor.core.model.Fuel
import car.doctor.core.model.IgnitionState
import car.doctor.core.model.NewVehicle
import car.doctor.core.model.ObdSessionState
import car.doctor.core.model.Posture
import car.doctor.core.present.SessionPresenter
import car.doctor.core.present.SessionView
import car.doctor.core.session.BenchEffect
import car.doctor.core.session.VehicleBench

enum class DoctorScreen { HOME, BENCH, RESULT, HISTORY, SETTINGS }

data class VehicleCard(
    val id: String,
    val titleHe: String,
    val metaHe: String,
    val selected: Boolean,
)

data class DoctorUi(
    val nickname: String,
    val metaHe: String,
    val vinNoteHe: String,
    val hasVehicle: Boolean,
    val vehicles: List<VehicleCard>,
    val ignitionLabelHe: String,
    val sessionLabelHe: String,
    val protocolHe: String?,
    val errorHe: String?,
    val noticeHe: String?,
    val storageNoticeHe: String?,
    val moves: List<IgnitionMove>,
    val posture: Posture,
    val postureLabelHe: String,
    val activeFaults: Set<FaultId>,
    val canConnect: Boolean,
    val canScan: Boolean,
    val canDisconnect: Boolean,
    val latest: SessionView?,
    val history: List<SessionView>,
    val open: SessionView?,
)

/**
 * UI state only. Diagnosis is already inside the session the bench returned.
 * Screens never see bytes, frames, or the mock pipe.
 */
class DoctorViewModel(
    private val bench: VehicleBench,
    private val storageNoticeHe: String? = null,
) : ViewModel() {
    private var noticeHe: String? = null
    private var openId: String? = null

    var screen by mutableStateOf(DoctorScreen.HOME)
        private set

    var nickname by mutableStateOf("")
    var make by mutableStateOf("")
    var model by mutableStateOf("")
    var yearText by mutableStateOf("2016")
    var vin by mutableStateOf("")
    var fuel by mutableStateOf(Fuel.GASOLINE)

    var ui by mutableStateOf(compose())
        private set

    fun showHome() = go(DoctorScreen.HOME)
    fun showBench() = go(DoctorScreen.BENCH)
    fun showResult() = go(DoctorScreen.RESULT)
    fun showHistory() = go(DoctorScreen.HISTORY)
    fun showSettings() = go(DoctorScreen.SETTINGS)

    fun connect() = apply(bench.connect())
    fun disconnect() = apply(bench.disconnect())
    fun ignite(to: IgnitionState) = apply(bench.setIgnition(to))
    fun posture(posture: Posture) = apply(bench.setPosture(posture))
    fun fault(fault: FaultId) = apply(bench.toggleFault(fault))
    fun chooseFuel(value: Fuel) {
        fuel = value
    }

    fun scan() {
        val effect = bench.quickScan()
        noticeHe = (effect as? BenchEffect.Rejected)?.messageHe
        if (effect is BenchEffect.Accepted && bench.snapshot().session == ObdSessionState.COMPLETE) {
            openId = bench.snapshot().historyIds.firstOrNull()
            screen = DoctorScreen.RESULT
        }
        ui = compose()
    }

    fun selectVehicle(id: String) {
        val effect = bench.selectVehicle(id)
        noticeHe = (effect as? BenchEffect.Rejected)?.messageHe
        if (effect is BenchEffect.Accepted) screen = DoctorScreen.BENCH
        ui = compose()
    }

    fun createVehicle() {
        val year = yearText.trim().toIntOrNull()
        if (make.isBlank() || model.isBlank()) {
            noticeHe = "חסרים יצרן או דגם."
            ui = compose()
            return
        }
        if (year == null || year !in 1970..2035) {
            noticeHe = "שנת ייצור צריכה להיות בין 1970 ל-2035."
            ui = compose()
            return
        }
        val effect = bench.addVehicle(
            NewVehicle(
                nickname = nickname,
                make = make,
                model = model,
                year = year,
                fuel = fuel,
                vin = vin,
            ),
        )
        noticeHe = (effect as? BenchEffect.Rejected)?.messageHe
        if (effect is BenchEffect.Accepted) {
            nickname = ""
            vin = ""
            screen = DoctorScreen.BENCH
        }
        ui = compose()
    }

    fun addDemo() {
        val effect = bench.addVehicle(
            NewVehicle(
                nickname = "קורולה",
                make = "טויוטה",
                model = "קורולה",
                year = 2016,
                fuel = Fuel.GASOLINE,
                vin = "",
            ),
        )
        noticeHe = (effect as? BenchEffect.Rejected)?.messageHe
        if (effect is BenchEffect.Accepted) screen = DoctorScreen.BENCH
        ui = compose()
    }

    fun openSession(id: String) {
        openId = id
        screen = DoctorScreen.RESULT
        ui = compose()
    }

    private fun go(next: DoctorScreen) {
        screen = next
        ui = compose()
    }

    private fun apply(effect: BenchEffect) {
        noticeHe = (effect as? BenchEffect.Rejected)?.messageHe
        ui = compose()
    }

    private fun compose(): DoctorUi {
        val snap = bench.snapshot()
        val history = bench.sessions().map(SessionPresenter::present)
        if (openId != null && history.none { it.id == openId }) openId = null
        val vehicle = snap.vehicle
        val open = history.firstOrNull { it.id == openId }
        return DoctorUi(
            nickname = vehicle?.nickname ?: "אין רכב",
            metaHe = vehicle?.let { "${it.make} ${it.model} · ${it.year} · ${fuelLabel(it.fuel)}" }
                ?: "לא נבחר רכב",
            vinNoteHe = vinNote(vehicle?.vin.orEmpty()),
            hasVehicle = vehicle != null,
            vehicles = snap.vehicles.map { item ->
                VehicleCard(
                    id = item.id,
                    titleHe = item.nickname,
                    metaHe = "${item.make} ${item.model} · ${item.year} · ${fuelLabel(item.fuel)}",
                    selected = item.id == vehicle?.id,
                )
            },
            ignitionLabelHe = ignitionLabel(snap.ignition),
            sessionLabelHe = sessionLabel(snap.session),
            protocolHe = snap.protocolHe?.takeIf { snap.session in LIVE_PROTOCOL },
            errorHe = snap.errorHe,
            noticeHe = noticeHe,
            storageNoticeHe = storageNoticeHe,
            moves = snap.ignitionMoves,
            posture = snap.posture,
            postureLabelHe = postureLabel(snap.posture),
            activeFaults = snap.activeFaults.toSet(),
            canConnect = vehicle != null && snap.capabilities.canConnect,
            canScan = vehicle != null && snap.capabilities.canScan,
            canDisconnect = vehicle != null && snap.capabilities.canDisconnect,
            latest = history.firstOrNull(),
            history = history,
            open = open ?: history.firstOrNull(),
        )
    }

    private companion object {
        val LIVE_PROTOCOL = setOf(
            ObdSessionState.CONNECTED,
            ObdSessionState.SCANNING,
            ObdSessionState.COMPLETE,
        )
    }
}
