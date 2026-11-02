package car.doctor.core.adapter

import car.doctor.core.model.AdapterKind

/** What this adapter can actually do. Declaring one does not implement it. */
enum class AdapterCapability {
    READ_DTC,
    READ_PID,
    READ_FREEZE_FRAME,
    READ_READINESS,
    MULTI_ECU,
    CAN,
    ISO_TP,
    UDS,
}

enum class TransportKind { MOCK, BLE, CLASSIC, WIFI }

data class AdapterInfo(
    val adapterId: String,
    val adapterType: AdapterKind,
    val firmwareVersion: String?,
    val capabilities: Set<AdapterCapability>,
    val transportType: TransportKind,
)
