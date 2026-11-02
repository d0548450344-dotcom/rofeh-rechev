package car.doctor.core.scan

import car.doctor.core.adapter.ObdAdapter
import car.doctor.core.model.AdapterKind
import car.doctor.core.model.DiagnosticSession
import car.doctor.core.model.IgnitionState
import car.doctor.core.model.Posture
import car.doctor.core.rules.DiagnosticRules

data class QuickScanRequest(
    val adapter: ObdAdapter,
    val vehicleId: String,
    val ignition: IgnitionState,
    val posture: Posture,
    val id: String,
    val startedAt: String,
    val finishedAt: String,
    val durationMs: Long,
)

fun adapterLabel(kind: AdapterKind): String = when (kind) {
    AdapterKind.SIMULATED -> "מתאם OBD מדומה"
    AdapterKind.BLE -> "מתאם BLE"
    AdapterKind.CLASSIC -> "מתאם Bluetooth Classic"
    AdapterKind.WIFI -> "מתאם Wi-Fi"
}

/**
 * Vehicle → adapter → ECU discovery → DTC/PID collection → session → rules → findings.
 * Callers (a ViewModel included) do not inspect codes to invent a diagnosis.
 */
object QuickScan {
    fun run(request: QuickScanRequest): DiagnosticSession {
        val readAt = request.finishedAt
        val discovered = request.adapter.discoverEcus(readAt)
        val dtcs = request.adapter.readDtcs(readAt)
        val pids = request.adapter.readPids(readAt)
        val collected = request.adapter.readFacts(readAt).copy(
            ecus = discovered,
            dtcs = dtcs,
            pids = pids,
        )
        val ignition = request.adapter.observedIgnition(readAt) ?: request.ignition
        val findings = DiagnosticRules.evaluate(collected, ignition)
        return DiagnosticSession(
            id = request.id,
            vehicleId = request.vehicleId,
            adapterKind = request.adapter.kind,
            adapterLabelHe = adapterLabel(request.adapter.kind),
            startedAt = request.startedAt,
            finishedAt = request.finishedAt,
            durationMs = request.durationMs,
            ignition = ignition,
            posture = request.posture,
            protocolHe = collected.protocolHe,
            vinNoteHe = collected.vinNoteHe,
            ecus = collected.ecus,
            gaps = collected.gaps,
            dtcs = collected.dtcs,
            pids = collected.pids,
            readiness = collected.readiness,
            findings = findings,
            labFaults = emptyList(),
            labLostEcu = null,
            errorHe = null,
        )
    }
}
