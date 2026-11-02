package car.doctor.app

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import car.doctor.app.link.AdapterDirectory
import car.doctor.app.link.AdapterPrefs
import car.doctor.app.link.AdapterSource
import car.doctor.app.link.BluetoothStatus
import car.doctor.app.link.FoundAdapter
import car.doctor.app.link.SwitchingAdapterFactory
import car.doctor.app.link.WIFI_DEFAULT_HOST
import car.doctor.app.link.WIFI_DEFAULT_PORT
import car.doctor.core.catalog.fuelLabel
import car.doctor.core.catalog.ignitionLabel
import car.doctor.core.catalog.postureLabel
import car.doctor.core.catalog.sessionLabel
import car.doctor.core.catalog.vinNote
import car.doctor.core.machine.IgnitionMove
import car.doctor.core.model.ClearConfirmation
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class DoctorScreen { HOME, BENCH, RESULT, HISTORY, SETTINGS }

enum class PickerMode { NONE, BLUETOOTH, WIFI }

/** Clear-codes flow: nothing, first warning, second confirmation. */
enum class ClearStep { NONE, WARN, CONFIRM }

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
    val sourceLabelHe: String,
    val live: Boolean,
    /** Set while the adapter is being talked to. Buttons stay disabled until it clears. */
    val busyHe: String?,
    val canChangeSource: Boolean,
    /** A scan on this link just found codes; clearing them is possible. */
    val canClear: Boolean,
)

/** Asks the activity for runtime permissions and reports whether all were granted. */
fun interface PermissionAsker {
    fun ask(permissions: List<String>, result: (Boolean) -> Unit)
}

/**
 * UI state only. Diagnosis is already inside the session the bench returned.
 * Screens never see bytes, frames, or the adapter link.
 *
 * Every bench call runs on one background thread: a real adapter can take seconds
 * to answer, and the bench is not thread-safe.
 */
class DoctorViewModel(
    private val bench: VehicleBench,
    private val links: SwitchingAdapterFactory,
    private val directory: AdapterDirectory,
    private val prefs: AdapterPrefs,
    private val storageNoticeHe: String? = null,
) : ViewModel() {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "obd-bench").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var noticeHe: String? = null
    @Volatile private var openId: String? = null
    @Volatile private var busyHe: String? = null

    var permissionAsker: PermissionAsker? = null

    var screen by mutableStateOf(DoctorScreen.HOME)
        private set

    var nickname by mutableStateOf("")
    var make by mutableStateOf("")
    var model by mutableStateOf("")
    var yearText by mutableStateOf("2016")
    var vin by mutableStateOf("")
    var fuel by mutableStateOf(Fuel.GASOLINE)

    var picker by mutableStateOf(PickerMode.NONE)
        private set
    var devices by mutableStateOf<List<FoundAdapter>>(emptyList())
        private set
    var scanningBle by mutableStateOf(false)
        private set
    var pickerNoticeHe by mutableStateOf<String?>(null)
        private set
    var wifiHost by mutableStateOf(WIFI_DEFAULT_HOST)
    var wifiPort by mutableStateOf(WIFI_DEFAULT_PORT.toString())

    var clearStep by mutableStateOf(ClearStep.NONE)
        private set

    var ui by mutableStateOf(compose())
        private set

    init {
        val saved = prefs.load()
        if (saved is AdapterSource.Wifi) {
            wifiHost = saved.host
            wifiPort = saved.port.toString()
        }
        links.use(saved)
        ui = compose()
    }

    fun showHome() = go(DoctorScreen.HOME)
    fun showBench() = go(DoctorScreen.BENCH)
    fun showResult() = go(DoctorScreen.RESULT)
    fun showHistory() = go(DoctorScreen.HISTORY)
    fun showSettings() = go(DoctorScreen.SETTINGS)

    fun connect() {
        val source = links.source
        if (source is AdapterSource.Classic || source is AdapterSource.Ble) {
            when (directory.status()) {
                BluetoothStatus.MISSING -> return say("אין בלוטות׳ במכשיר הזה. אפשר להשתמש במתאם Wi-Fi.")
                BluetoothStatus.OFF -> return say("הבלוטות׳ כבוי. מדליקים אותו בהגדרות הטלפון ומנסים שוב.")
                BluetoothStatus.READY -> Unit
            }
            val needed = directory.connectPermissions()
            if (needed.isNotEmpty()) {
                return withPermissions(needed) { connect() }
            }
        }
        val label = if (source.isLive) "מתחבר למתאם ובודק שהרכב עונה…" else "מתחבר…"
        act(label) { apply(bench.connect()) }
    }

    fun disconnect() = act(if (links.source.isLive) "מתנתק מהמתאם…" else null) { apply(bench.disconnect()) }
    fun ignite(to: IgnitionState) = act { apply(bench.setIgnition(to)) }
    fun posture(posture: Posture) = act { apply(bench.setPosture(posture)) }
    fun fault(fault: FaultId) = act { apply(bench.toggleFault(fault)) }
    fun chooseFuel(value: Fuel) {
        fuel = value
    }

    fun scan() {
        val label = if (links.source.isLive) "קורא קודים ונתונים מהרכב…" else "סורק…"
        act(label) {
            val effect = bench.quickScan()
            noticeHe = (effect as? BenchEffect.Rejected)?.messageHe
            if (effect is BenchEffect.Accepted && bench.snapshot().session == ObdSessionState.COMPLETE) {
                openId = bench.snapshot().historyIds.firstOrNull()
                DoctorScreen.RESULT
            } else {
                null
            }
        }
    }

    fun selectVehicle(id: String) = act {
        val effect = bench.selectVehicle(id)
        noticeHe = (effect as? BenchEffect.Rejected)?.messageHe
        if (effect is BenchEffect.Accepted) DoctorScreen.BENCH else null
    }

    fun createVehicle() {
        val year = yearText.trim().toIntOrNull()
        if (make.isBlank() || model.isBlank()) return say("חסרים יצרן או דגם.")
        if (year == null || year !in 1970..2035) return say("שנת ייצור צריכה להיות בין 1970 ל-2035.")
        val input = NewVehicle(nickname = nickname, make = make, model = model, year = year, fuel = fuel, vin = vin)
        act {
            val effect = bench.addVehicle(input)
            noticeHe = (effect as? BenchEffect.Rejected)?.messageHe
            if (effect is BenchEffect.Accepted) {
                main.post {
                    nickname = ""
                    vin = ""
                }
                DoctorScreen.BENCH
            } else {
                null
            }
        }
    }

    fun addDemo() = act {
        val effect = bench.addVehicle(
            NewVehicle(nickname = "קורולה", make = "טויוטה", model = "קורולה", year = 2016, fuel = Fuel.GASOLINE, vin = ""),
        )
        noticeHe = (effect as? BenchEffect.Rejected)?.messageHe
        if (effect is BenchEffect.Accepted) DoctorScreen.BENCH else null
    }

    fun openSession(id: String) {
        openId = id
        screen = DoctorScreen.RESULT
        ui = compose()
    }

    // ---- Clear codes: two confirmations, then the bench re-checks everything ----

    fun startClear() {
        if (ui.canClear) clearStep = ClearStep.WARN
    }

    fun clearNext() {
        if (clearStep == ClearStep.WARN) clearStep = ClearStep.CONFIRM
    }

    fun cancelClear() {
        clearStep = ClearStep.NONE
    }

    fun confirmClear() {
        if (clearStep != ClearStep.CONFIRM) return
        clearStep = ClearStep.NONE
        act("מוחק קודים ומריץ סריקת אימות…") {
            val effect = bench.clearCodes(ClearConfirmation(understoodCodesReturn = true, understoodReadinessReset = true))
            noticeHe = (effect as? BenchEffect.Rejected)?.messageHe
            if (effect is BenchEffect.Accepted) {
                openId = bench.snapshot().historyIds.firstOrNull()
                DoctorScreen.RESULT
            } else {
                null
            }
        }
    }

    // ---- Adapter picker ----

    fun openPicker(mode: PickerMode) {
        picker = mode
        pickerNoticeHe = null
        if (mode == PickerMode.BLUETOOTH) refreshPaired()
    }

    fun closePicker() {
        directory.stopScan()
        scanningBle = false
        picker = PickerMode.NONE
    }

    fun useSimulator() = switchTo(AdapterSource.Simulated)

    fun useDevice(device: FoundAdapter) = switchTo(device.toSource())

    fun useWifi() {
        val port = wifiPort.trim().toIntOrNull()
        val host = wifiHost.trim()
        if (host.isEmpty() || port == null || port !in 1..65535) {
            pickerNoticeHe = "כתובת או פורט לא תקינים. ברוב המתאמים: 192.168.0.10 ופורט 35000."
            return
        }
        switchTo(AdapterSource.Wifi(host, port))
    }

    fun refreshPaired() {
        when (directory.status()) {
            BluetoothStatus.MISSING -> {
                pickerNoticeHe = "אין בלוטות׳ במכשיר הזה."
                return
            }
            BluetoothStatus.OFF -> {
                pickerNoticeHe = "הבלוטות׳ כבוי. מדליקים אותו בהגדרות הטלפון."
                return
            }
            BluetoothStatus.READY -> Unit
        }
        val needed = directory.connectPermissions()
        if (needed.isNotEmpty()) return withPermissions(needed) { refreshPaired() }
        devices = merge(directory.paired(), devices.filterNot { it.paired })
        pickerNoticeHe = if (devices.isEmpty()) {
            "אין מכשירים מצומדים. מתאם Bluetooth רגיל מצמדים קודם בהגדרות הבלוטות׳ של הטלפון (קוד נפוץ: 1234 או 0000). מתאם BLE מוצאים בחיפוש."
        } else {
            null
        }
    }

    fun scanBle() {
        if (scanningBle) return
        if (directory.status() != BluetoothStatus.READY) return refreshPaired()
        val needed = directory.scanPermissions()
        if (needed.isNotEmpty()) return withPermissions(needed) { scanBle() }
        scanningBle = true
        pickerNoticeHe = null
        directory.scanBle(
            seconds = 8,
            onFound = { found -> devices = merge(devices, listOf(found)) },
            done = {
                scanningBle = false
                if (devices.isEmpty()) pickerNoticeHe = "לא נמצאו מתאמי BLE. בודקים שהמתאם מחובר לרכב ושהנורה שלו דולקת."
            },
        )
    }

    private fun switchTo(next: AdapterSource) {
        closePicker()
        act(if (next.isLive) "מחליף מתאם…" else null) {
            // A link must never be swapped under an open session.
            bench.disconnect()
            links.use(next)
            prefs.save(next)
            noticeHe = null
            null
        }
    }

    private fun withPermissions(needed: List<String>, then: () -> Unit) {
        val asker = permissionAsker ?: return say("חסרה הרשאת בלוטות׳. מאשרים אותה בהגדרות האפליקציה.")
        asker.ask(needed) { granted ->
            if (granted) {
                then()
            } else {
                say("בלי הרשאת בלוטות׳ אי אפשר לדבר עם המתאם. אפשר לאשר אותה בהגדרות האפליקציה.")
                pickerNoticeHe = "בלי הרשאת בלוטות׳ אי אפשר לראות את המתאמים."
            }
        }
    }

    private fun go(next: DoctorScreen) {
        screen = next
        ui = compose()
    }

    private fun say(text: String) {
        noticeHe = text
        if (busyHe == null) ui = compose()
    }

    private fun apply(effect: BenchEffect): DoctorScreen? {
        noticeHe = (effect as? BenchEffect.Rejected)?.messageHe
        return null
    }

    /**
     * Runs [block] on the bench thread, then publishes fresh UI state on the main thread.
     * The block may return a screen to navigate to.
     */
    private fun act(busyLabel: String? = null, block: () -> DoctorScreen?) {
        if (busyHe != null) return
        busyHe = busyLabel ?: ""
        if (busyLabel != null) ui = ui.copy(busyHe = busyLabel)
        worker.execute {
            val next = try {
                block()
            } catch (e: Exception) {
                noticeHe = "שגיאה לא צפויה: ${e.message ?: e.javaClass.simpleName}"
                null
            }
            busyHe = null
            val fresh = compose()
            main.post {
                if (next != null) screen = next
                ui = fresh
            }
        }
    }

    private fun compose(): DoctorUi {
        val snap = bench.snapshot()
        val history = bench.sessions().map(SessionPresenter::present)
        if (openId != null && history.none { it.id == openId }) openId = null
        val vehicle = snap.vehicle
        val open = history.firstOrNull { it.id == openId }
        val source = links.source
        val idle = busyHe == null
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
            canConnect = idle && vehicle != null && snap.capabilities.canConnect,
            canScan = idle && vehicle != null && snap.capabilities.canScan,
            canDisconnect = idle && vehicle != null && snap.capabilities.canDisconnect,
            latest = history.firstOrNull(),
            history = history,
            open = open ?: history.firstOrNull(),
            sourceLabelHe = source.labelHe,
            live = source.isLive,
            busyHe = busyHe?.takeIf { it.isNotEmpty() },
            canChangeSource = idle,
            canClear = idle && vehicle != null && snap.capabilities.canClear,
        )
    }

    override fun onCleared() {
        directory.stopScan()
        worker.execute { runCatching { bench.disconnect() } }
        worker.shutdown()
    }

    private companion object {
        val LIVE_PROTOCOL = setOf(
            ObdSessionState.CONNECTED,
            ObdSessionState.SCANNING,
            ObdSessionState.COMPLETE,
        )

        fun merge(first: List<FoundAdapter>, second: List<FoundAdapter>): List<FoundAdapter> =
            (first + second).distinctBy { it.address }
    }
}
