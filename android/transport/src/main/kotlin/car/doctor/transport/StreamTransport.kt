package car.doctor.transport

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * An open byte link: one input stream, one output stream, one way to close both.
 * Bluetooth Classic sockets and TCP sockets both fit this shape.
 */
class StreamLink(
    val input: InputStream,
    val output: OutputStream,
    private val closer: () -> Unit,
) {
    fun close() {
        runCatching { closer() }
    }
}

/** Opens a [StreamLink]. Throws on failure; [StreamTransport] turns that into a structured outcome. */
fun interface StreamLinkOpener {
    @Throws(IOException::class)
    fun open(): StreamLink
}

/**
 * Real byte pipe over a stream link.
 * [receive] never blocks for long: it returns whatever arrived within [pollWindowMs],
 * possibly nothing. The protocol layer owns the overall deadline.
 */
open class StreamTransport(
    override val transportType: String,
    private val opener: StreamLinkOpener,
    private val log: TransportLog = TransportLog.NONE,
    private val pollWindowMs: Long = 40,
) : ObdTransport {
    private val machine = TransportStateMachine()
    private val seen = mutableListOf<TransportEvent>()
    private var link: StreamLink? = null

    val trace: List<TransportState> get() = machine.trace

    @Synchronized
    override fun connect(): TransportOutcome {
        if (machine.state == TransportState.CONNECTED) return TransportOutcome.Ok()
        if (!machine.transition(TransportState.CONNECTING)) return refuse(TransportState.CONNECTING)
        log.connect(TransportState.CONNECTING.name)
        val opened = try {
            opener.open()
        } catch (e: SecurityException) {
            return failConnect("permission: ${e.message.orEmpty()}")
        } catch (e: IOException) {
            return failConnect(e.message ?: e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            return failConnect(e.message ?: e.javaClass.simpleName)
        }
        link = opened
        check(machine.transition(TransportState.CONNECTED))
        seen += TransportEvent.Connected
        return TransportOutcome.Ok()
    }

    @Synchronized
    override fun disconnect(): TransportOutcome {
        val current = link
        link = null
        current?.close()
        return when (machine.state) {
            TransportState.DISCONNECTED -> TransportOutcome.Ok()
            TransportState.CONNECTED -> {
                machine.transition(TransportState.DISCONNECTING)
                machine.transition(TransportState.DISCONNECTED)
                seen += TransportEvent.Disconnected(clean = true)
                log.disconnect("clean")
                TransportOutcome.Ok()
            }
            TransportState.ERROR -> {
                machine.transition(TransportState.DISCONNECTED)
                seen += TransportEvent.Disconnected(clean = true)
                log.disconnect("clean")
                TransportOutcome.Ok()
            }
            TransportState.CONNECTING, TransportState.DISCONNECTING -> refuse(TransportState.DISCONNECTED)
        }
    }

    override fun isConnected(): Boolean = machine.state == TransportState.CONNECTED

    @Synchronized
    override fun send(payload: ByteArray): TransportOutcome {
        val current = link
        if (machine.state != TransportState.CONNECTED || current == null) {
            return TransportOutcome.Failed(TransportFailure.Disconnected("send"))
        }
        return try {
            current.output.write(payload)
            current.output.flush()
            TransportOutcome.Ok()
        } catch (e: IOException) {
            lose("send", e)
        }
    }

    @Synchronized
    override fun receive(maxBytes: Int): TransportOutcome {
        if (maxBytes <= 0) return TransportOutcome.Failed(TransportFailure.UnsupportedOperation("receive"))
        val current = link
        if (machine.state != TransportState.CONNECTED || current == null) {
            return TransportOutcome.Failed(TransportFailure.Disconnected("receive"))
        }
        return try {
            val deadline = System.nanoTime() + pollWindowMs * 1_000_000
            var ready = current.input.available()
            while (ready <= 0 && System.nanoTime() < deadline) {
                Thread.sleep(5)
                ready = current.input.available()
            }
            if (ready <= 0) return TransportOutcome.Ok(byteArrayOf())
            val buffer = ByteArray(minOf(ready, maxBytes))
            val read = current.input.read(buffer)
            if (read < 0) return lose("receive", IOException("end of stream"))
            TransportOutcome.Ok(buffer.copyOf(read))
        } catch (e: IOException) {
            lose("receive", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            TransportOutcome.Failed(TransportFailure.Timeout("interrupted"))
        }
    }

    override fun connectionState(): TransportState = machine.state

    override fun events(): List<TransportEvent> = seen.toList()

    private fun failConnect(detail: String): TransportOutcome {
        machine.transition(TransportState.ERROR)
        seen += TransportEvent.ConnectionFailed(detail)
        log.error("connection_failed", transportType)
        return TransportOutcome.Failed(TransportFailure.ConnectionFailed(detail))
    }

    /** The link dropped underneath us. This is not a requested disconnect. */
    private fun lose(where: String, e: IOException): TransportOutcome {
        link?.close()
        link = null
        machine.transition(TransportState.ERROR)
        seen += TransportEvent.Disconnected(clean = false)
        log.error("transport", where)
        return TransportOutcome.Failed(TransportFailure.Disconnected("$where: ${e.message.orEmpty()}"))
    }

    private fun refuse(attempted: TransportState): TransportOutcome {
        log.error("invalid_state", "${machine.state.name}->${attempted.name}")
        return TransportOutcome.Failed(TransportFailure.InvalidState(machine.state, attempted))
    }
}
