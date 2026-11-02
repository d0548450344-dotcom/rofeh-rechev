package car.doctor.app.link

import car.doctor.transport.ELM327_WIFI_DEFAULT_HOST
import car.doctor.transport.ELM327_WIFI_DEFAULT_PORT

const val WIFI_DEFAULT_HOST: String = ELM327_WIFI_DEFAULT_HOST
const val WIFI_DEFAULT_PORT: Int = ELM327_WIFI_DEFAULT_PORT

/** Which adapter the bench talks to. Plain data: safe to show in the UI and to persist. */
sealed class AdapterSource {
    abstract val labelHe: String

    data object Simulated : AdapterSource() {
        override val labelHe = "רכב מדומה"
    }

    data class Classic(val address: String, val name: String) : AdapterSource() {
        override val labelHe get() = "Bluetooth · $name"
    }

    data class Ble(val address: String, val name: String) : AdapterSource() {
        override val labelHe get() = "Bluetooth LE · $name"
    }

    data class Wifi(val host: String, val port: Int) : AdapterSource() {
        override val labelHe get() = "Wi-Fi · $host:$port"
    }

    val isLive: Boolean get() = this !is Simulated
}

/** A Bluetooth device the user can pick. */
data class FoundAdapter(
    val name: String,
    val address: String,
    val ble: Boolean,
    val paired: Boolean,
) {
    fun toSource(): AdapterSource = if (ble) AdapterSource.Ble(address, name) else AdapterSource.Classic(address, name)
}

/** Names cheap ELM327 clones and known brands advertise. Used only to sort the list. */
fun looksLikeObd(name: String): Boolean {
    val n = name.uppercase()
    return listOf("OBD", "ELM", "V-LINK", "VLINK", "VGATE", "ICAR", "VEEPEAK", "KONNWEI", "CAR", "SCAN", "OBDLINK", "LELINK")
        .any { n.contains(it) }
}
