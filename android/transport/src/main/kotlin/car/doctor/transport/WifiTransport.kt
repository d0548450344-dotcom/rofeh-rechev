package car.doctor.transport

import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/** Most ELM327 Wi-Fi adapters create their own access point and listen here. */
const val ELM327_WIFI_DEFAULT_HOST = "192.168.0.10"
const val ELM327_WIFI_DEFAULT_PORT = 35000

/**
 * TCP link to an ELM327 Wi-Fi adapter.
 * On Android, pass the Wi-Fi network's [SocketFactory]; otherwise the OS may route
 * the socket over mobile data, because the adapter's network has no internet.
 */
class WifiTransport(
    host: String = ELM327_WIFI_DEFAULT_HOST,
    port: Int = ELM327_WIFI_DEFAULT_PORT,
    socketFactory: SocketFactory = SocketFactory.getDefault(),
    connectTimeoutMs: Int = 5_000,
    log: TransportLog = TransportLog.NONE,
) : StreamTransport(
    transportType = "wifi",
    opener = StreamLinkOpener {
        val socket: Socket = socketFactory.createSocket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
        StreamLink(socket.getInputStream(), socket.getOutputStream()) { socket.close() }
    },
    log = log,
)
