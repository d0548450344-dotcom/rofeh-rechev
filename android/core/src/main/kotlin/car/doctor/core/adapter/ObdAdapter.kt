package car.doctor.core.adapter

import car.doctor.core.model.AdapterKind
import car.doctor.core.model.ClearOutcome
import car.doctor.core.model.ConnectResult
import car.doctor.core.model.Dtc
import car.doctor.core.model.EcuReport
import car.doctor.core.model.IgnitionState
import car.doctor.core.model.PidValue
import car.doctor.core.model.ScanFacts
import car.doctor.core.model.Vehicle

/**
 * Link contract. Reads, plus exactly one write: [clearCodes] (OBD mode 04).
 * A scan does not receive the fault list: the adapter already owns the link.
 * Simulated, BLE, classic, and Wi-Fi adapters are meant to implement this same surface.
 * There is intentionally no flash, coding, security-access or memory-write method.
 *
 * Implemented by the simulated adapter and by the ELM327 adapter in :live.
 */
interface ObdAdapter {
    val kind: AdapterKind
    val readOnly: Boolean

    fun info(): AdapterInfo
    fun probe(): ConnectResult
    fun discoverEcus(readAt: String): List<EcuReport>
    fun readDtcs(readAt: String): List<Dtc>
    fun readPids(readAt: String): List<PidValue>
    fun readFacts(readAt: String): ScanFacts

    /**
     * Ignition state the car itself revealed during the last read (for example, engine
     * RPM above idle means it is running). Null when the adapter cannot tell; the
     * simulated adapter returns null because the lab sets ignition directly.
     */
    fun observedIgnition(readAt: String): IgnitionState? = null

    /**
     * Clears stored/pending codes and readiness (mode 04) after checking, on the car itself,
     * that the engine is off and the vehicle is not moving. The caller (VehicleBench) has
     * already checked the user's confirmation. Default: not supported.
     */
    fun clearCodes(confirmedAt: String): ClearOutcome =
        ClearOutcome.Refused("המתאם הזה לא תומך במחיקת קודים.")

    /** Close the link underneath. This is not a code clear and not a diagnosis. */
    fun release()
}

fun interface ObdAdapterFactory {
    fun open(vehicle: Vehicle): ObdAdapter
}
