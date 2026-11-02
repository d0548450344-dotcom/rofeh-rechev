package car.doctor.core.adapter

import car.doctor.core.model.AdapterKind
import car.doctor.core.model.ConnectResult
import car.doctor.core.model.Dtc
import car.doctor.core.model.EcuReport
import car.doctor.core.model.IgnitionState
import car.doctor.core.model.PidValue
import car.doctor.core.model.ScanFacts
import car.doctor.core.model.Vehicle

/**
 * Read-only link contract.
 * A scan does not receive the fault list: the adapter already owns the link.
 * Simulated, BLE, classic, and Wi-Fi adapters are meant to implement this same surface.
 * There is intentionally no write, clear, coding, security-access, or flash method.
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

    /** Close the link underneath. This is not a code clear and not a diagnosis. */
    fun release()
}

fun interface ObdAdapterFactory {
    fun open(vehicle: Vehicle): ObdAdapter
}
