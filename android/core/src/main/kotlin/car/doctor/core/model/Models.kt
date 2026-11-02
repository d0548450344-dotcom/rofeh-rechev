package car.doctor.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class IgnitionState { OFF, ACC, ON, STARTING, RUNNING }

@Serializable
enum class ObdSessionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    SCANNING,
    COMPLETE,
    DISCONNECTING,
    ERROR,
}

@Serializable
enum class EcuId { ENGINE, ABS, BCM, SRS }

@Serializable
enum class EcuAccess { CHECKED, INACCESSIBLE, NOT_SCANNED, COMM_ERROR }

@Serializable
enum class Fuel { GASOLINE, DIESEL, HYBRID, ELECTRIC }

@Serializable
enum class Posture { IDLE, STRAIGHT, TURN, BRAKE }

@Serializable
enum class FaultId {
    LOW_BATTERY,
    HIGH_COOLANT,
    MISFIRE,
    O2_ANOMALY,
    WHEEL_SPEED_MISMATCH,
    STEERING_ANGLE_ANOMALY,
    LOST_ECU,
    COMM_TIMEOUT,
    INTERMITTENT_SENSOR,
    MULTIPLE_DTCS,
}

@Serializable
enum class DtcStatus { STORED, PENDING, PERMANENT }

@Serializable
enum class Severity { INFO, LOW, MEDIUM, HIGH, CRITICAL }

@Serializable
enum class Confidence { INSUFFICIENT, LOW, MEDIUM, HIGH }

@Serializable
enum class PidQuality { VALID, UNAVAILABLE }

@Serializable
enum class AdapterKind { SIMULATED, BLE, CLASSIC, WIFI }

@Serializable
enum class FindingSource { RULE }

@Serializable
enum class MonitorStatus { COMPLETE, INCOMPLETE, UNSUPPORTED }

@Serializable
enum class Coverage { NOT_SCANNED }

/**
 * Lab-only vehicle condition. Diagnostic rules never read this object.
 * They only see what an [car.doctor.core.adapter.ObdAdapter] returned.
 */
@Serializable
data class VehicleSim(
    val ignition: IgnitionState = IgnitionState.OFF,
    val posture: Posture = Posture.IDLE,
    val faults: List<FaultId> = emptyList(),
    val lostEcu: EcuId? = null,
)

@Serializable
data class Vehicle(
    val id: String,
    val nickname: String,
    val make: String,
    val model: String,
    val year: Int,
    val fuel: Fuel,
    /** Typed by the user. Never treated as a value read from the car. */
    val vin: String,
    val createdAt: String,
    val sim: VehicleSim = VehicleSim(),
)

data class NewVehicle(
    val nickname: String,
    val make: String,
    val model: String,
    val year: Int,
    val fuel: Fuel,
    val vin: String,
)

/** Identity of a controller. Availability during a scan is an [EcuReport], not this type. */
@Serializable
data class Ecu(
    val id: EcuId,
    val nameHe: String,
    val address: String,
    val protocolHe: String,
)

@Serializable
data class Dtc(
    val code: String,
    val status: DtcStatus,
    val ecuId: EcuId,
    val descriptionHe: String,
    val severity: Severity,
    /** When this code was read. Not when the controller first stored it. */
    val readAt: String,
)

@Serializable
data class PidValue(
    val id: String,
    val nameHe: String,
    val value: Double?,
    val unit: String,
    val ecuId: EcuId,
    val quality: PidQuality,
    /** Standard OBD-II mode 01 signal. False means a simulated manufacturer channel. */
    val genericObd: Boolean,
    /** False when this vehicle is not expected to expose the signal. */
    val expected: Boolean,
    val readAt: String,
)

@Serializable
data class Monitor(
    val id: String,
    val nameHe: String,
    val status: MonitorStatus,
)

@Serializable
data class EcuReport(
    val id: EcuId,
    val access: EcuAccess,
    val reasonHe: String,
    val protocolHe: String?,
)

@Serializable
data class SystemGap(
    val id: String,
    val nameHe: String,
    val reasonHe: String,
    val coverage: Coverage = Coverage.NOT_SCANNED,
)

@Serializable
data class Finding(
    val id: String,
    val ruleId: String,
    val code: String?,
    val titleHe: String,
    val summaryHe: String,
    val severity: Severity,
    val confidence: Confidence,
    /** What the confidence applies to. Never a hidden part replacement. */
    val claimHe: String,
    val evidence: List<String>,
    val conditions: List<String>,
    val nextStepHe: String,
    val source: FindingSource = FindingSource.RULE,
    val ecuId: EcuId? = null,
)

@Serializable
data class ScanFacts(
    val readAt: String,
    val fuel: Fuel,
    val protocolHe: String?,
    val vinNoteHe: String,
    val ecus: List<EcuReport>,
    val gaps: List<SystemGap>,
    val dtcs: List<Dtc>,
    val pids: List<PidValue>,
    val readiness: List<Monitor>,
    val engineTimeout: Boolean,
)

@Serializable
data class ConnectResult(
    val ok: Boolean,
    val protocolHe: String?,
    val errorHe: String?,
)

@Serializable
data class DiagnosticSession(
    val id: String,
    val vehicleId: String,
    val kind: String = "quick",
    val adapterKind: AdapterKind,
    val adapterLabelHe: String,
    val startedAt: String,
    val finishedAt: String,
    val durationMs: Long,
    val ignition: IgnitionState,
    val posture: Posture,
    val protocolHe: String?,
    val vinNoteHe: String,
    val ecus: List<EcuReport>,
    val gaps: List<SystemGap>,
    val dtcs: List<Dtc>,
    val pids: List<PidValue>,
    val readiness: List<Monitor>,
    val findings: List<Finding>,
    /** Lab metadata copied after the rules ran. Rules never read this. */
    val labFaults: List<FaultId> = emptyList(),
    val labLostEcu: EcuId? = null,
    val errorHe: String? = null,
)
