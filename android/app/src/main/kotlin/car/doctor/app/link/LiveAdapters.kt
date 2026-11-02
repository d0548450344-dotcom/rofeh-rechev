package car.doctor.app.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import car.doctor.core.adapter.ObdAdapter
import car.doctor.core.adapter.ObdAdapterFactory
import car.doctor.core.model.AdapterKind
import car.doctor.core.model.Vehicle
import car.doctor.live.Elm327AdapterFactory
import car.doctor.transport.ObdTransport
import car.doctor.transport.WifiTransport
import javax.net.SocketFactory

/**
 * The bench's adapter factory. Delegates to the simulator or to one live ELM327 link,
 * depending on the source the user picked. Switch only while disconnected.
 */
class SwitchingAdapterFactory(
    context: Context,
    private val simulated: ObdAdapterFactory,
) : ObdAdapterFactory {
    private val context = context.applicationContext
    private var live: ObdAdapterFactory? = null

    var source: AdapterSource = AdapterSource.Simulated
        private set

    fun use(next: AdapterSource) {
        if (next == source) return
        source = next
        live = when (next) {
            AdapterSource.Simulated -> null
            is AdapterSource.Classic -> Elm327AdapterFactory(AdapterKind.CLASSIC, BluetoothClassicTransport(context, next.address))
            is AdapterSource.Ble -> Elm327AdapterFactory(AdapterKind.BLE, BleTransport(context, next.address))
            is AdapterSource.Wifi -> Elm327AdapterFactory(AdapterKind.WIFI, wifiTransport(next))
        }
    }

    override fun open(vehicle: Vehicle): ObdAdapter = (live ?: simulated).open(vehicle)

    private fun wifiTransport(source: AdapterSource.Wifi): ObdTransport =
        WifiTransport(source.host, source.port, wifiSocketFactory())

    /**
     * The adapter's Wi-Fi has no internet, so Android may route new sockets over mobile
     * data instead. Binding to the Wi-Fi network's own factory keeps them on Wi-Fi.
     */
    private fun wifiSocketFactory(): SocketFactory {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return SocketFactory.getDefault()
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.firstOrNull { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        return wifi?.socketFactory ?: SocketFactory.getDefault()
    }
}

/** Remembers the last adapter so the user does not pick it again every time. */
class AdapterPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("adapter", Context.MODE_PRIVATE)

    fun load(): AdapterSource {
        val address = prefs.getString("address", null)
        val name = prefs.getString("name", null) ?: address
        return when (prefs.getString("kind", null)) {
            "classic" -> if (address != null) AdapterSource.Classic(address, name!!) else AdapterSource.Simulated
            "ble" -> if (address != null) AdapterSource.Ble(address, name!!) else AdapterSource.Simulated
            "wifi" -> AdapterSource.Wifi(
                prefs.getString("host", null) ?: car.doctor.transport.ELM327_WIFI_DEFAULT_HOST,
                prefs.getInt("port", car.doctor.transport.ELM327_WIFI_DEFAULT_PORT),
            )
            else -> AdapterSource.Simulated
        }
    }

    fun save(source: AdapterSource) {
        val edit = prefs.edit().clear()
        when (source) {
            AdapterSource.Simulated -> edit.putString("kind", "simulated")
            is AdapterSource.Classic -> edit.putString("kind", "classic").putString("address", source.address).putString("name", source.name)
            is AdapterSource.Ble -> edit.putString("kind", "ble").putString("address", source.address).putString("name", source.name)
            is AdapterSource.Wifi -> edit.putString("kind", "wifi").putString("host", source.host).putInt("port", source.port)
        }
        edit.apply()
    }
}
