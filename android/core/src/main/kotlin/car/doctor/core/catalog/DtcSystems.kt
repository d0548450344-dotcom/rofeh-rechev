package car.doctor.core.catalog

import car.doctor.core.model.EcuId

/** The dashboard group a fault code belongs to. Each code belongs to exactly one group. */
enum class DtcSystem { ENGINE, SENSORS, TRANSMISSION, ELECTRICAL, ABS, AIRBAGS }

/**
 * Groups a fault code by its standard number (SAE J2012 / ISO 15031-6) and by the unit that
 * reported it. This is a label for display, not a diagnosis: it says which family the code's
 * number is in, never what is wrong.
 *
 * - Airbag unit → AIRBAGS; ABS unit → ABS (whatever the letter).
 * - P07xx–P09xx → TRANSMISSION (reported through the powertrain over generic OBD-II).
 * - U (network), B (body electrics), P06xx (control module and output circuits),
 *   P0560–P0563 (system voltage), and anything from the body unit → ELECTRICAL.
 * - P00xx–P02xx (fuel and air metering: air flow, pressure, temperature, oxygen sensors) → SENSORS.
 * - Every other powertrain code → ENGINE.
 */
fun dtcSystem(code: String, ecu: EcuId): DtcSystem {
    when (ecu) {
        EcuId.SRS -> return DtcSystem.AIRBAGS
        EcuId.ABS -> return DtcSystem.ABS
        EcuId.BCM -> return DtcSystem.ELECTRICAL
        EcuId.ENGINE -> Unit
    }
    val c = code.trim().uppercase()
    if (c.length != 5) return DtcSystem.ENGINE
    return when (c[0]) {
        'U', 'B' -> DtcSystem.ELECTRICAL
        'C' -> DtcSystem.ABS
        'P' -> powertrainSystem(c)
        else -> DtcSystem.ENGINE
    }
}

private fun powertrainSystem(c: String): DtcSystem {
    // Only the generic (P0xxx) range has standard sub-groups; P1xxx and up are the maker's own.
    if (c[1] != '0') return DtcSystem.ENGINE
    return when (c[2]) {
        '0', '1', '2' -> DtcSystem.SENSORS
        '6' -> DtcSystem.ELECTRICAL
        '5' -> if (c.substring(2, 4) == "56" && c[4] in '0'..'3') DtcSystem.ELECTRICAL else DtcSystem.ENGINE
        '7', '8', '9' -> DtcSystem.TRANSMISSION
        else -> DtcSystem.ENGINE
    }
}
