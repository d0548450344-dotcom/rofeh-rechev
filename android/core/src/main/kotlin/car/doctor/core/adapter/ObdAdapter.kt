package car.doctor.core.adapter

import car.doctor.core.model.AdapterKind
import car.doctor.core.model.ConnectResult
import car.doctor.core.model.Dtc
import car.doctor.core.model.EcuReport
import car.doctor.core.model.PidValue
import car.doctor.core.model.ScanFacts
import car.doctor.core.model.Vehicle

/**
 * Read-only link contract.
 * A scan does not receive the fault list: the adapter already owns the link.
 * Simulated, BLE, classic, and Wi-Fi adapters are meant to implement this same surface.
 * There is intentionally no write, clear, coding, security-access, or flash method.
 *
 * Only [AdapterKind.SIMULATED] has an implementation in this phase.
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

    /** Close the link underneath. This is not a code clear and not a diagnosis. */
    fun release()
}

fun interface ObdAdapterFactory {
    fun open(vehicle: Vehicle): ObdAdapter
}
