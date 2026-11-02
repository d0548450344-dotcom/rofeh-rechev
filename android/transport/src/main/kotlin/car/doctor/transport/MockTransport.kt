package car.doctor.transport

private enum class IoScript { NONE, TRANSPORT, PROTOCOL }

private const val MAX_FRAME_BYTES = 256

/**
 * Byte reply hook. The pipe does not interpret the bytes.
 * A mock ECU can supply the next receive buffer from the request bytes.
 */
fun interface PipeResponder {
    fun onRequest(request: ByteArray): ByteArray?
}

/**
 * In-memory link. It accepts bytes and returns an ack, or a staged reply.
 * It never decodes those bytes.
 */
class MockTransport(
    private val log: TransportLog = TransportLog.NONE,
) : ObdTransport {
    override val transportType: String = "mock"

    var responder: PipeResponder? = null

    private val machine = TransportStateMachine()
    private val seen = mutableListOf<TransportEvent>()
    private var remainingFailures = 0
    private var timeoutArmed = false
    private var armedIo = IoScript.NONE
    private var inbox: ByteArray? = null

    val trace: List<TransportState> get() = machine.trace

    fun failNextConnections(count: Int) {
        require(count >= 0)
        remainingFailures = count
    }

    fun timeoutNextConnection() {
        timeoutArmed = true
    }

    fun failNextSend() {
        armedIo = IoScript.TRANSPORT
    }

    fun failNextProtocol() {
        armedIo = IoScript.PROTOCOL
    }

    /** Unexpected drop. This is not a requested disconnect. */
    fun dropLink(): TransportOutcome {
        inbox = null
        if (machine.state != TransportState.CONNECTED) return refuse(TransportState.DISCONNECTED)
        if (!machine.transition(TransportState.DISCONNECTED)) return refuse(TransportState.DISCONNECTED)
        seen += TransportEvent.Disconnected(clean = false)
        log.disconnect("lost")
        return TransportOutcome.Ok()
    }

    override fun connect(): TransportOutcome {
        if (machine.state == TransportState.CONNECTED) return TransportOutcome.Ok()
        if (!machine.transition(TransportState.CONNECTING)) return refuse(TransportState.CONNECTING)
        log.connect(TransportState.CONNECTING.name)
        if (remainingFailures > 0) {
            remainingFailures -= 1
            if (!machine.transition(TransportState.ERROR)) return refuse(TransportState.ERROR)
            seen += TransportEvent.ConnectionFailed("mock-connection-failed")
            log.error("connection_failed", "mock")
            return TransportOutcome.Failed(TransportFailure.ConnectionFailed("mock-connection-failed"))
        }
        if (timeoutArmed) {
            timeoutArmed = false
            if (!machine.transition(TransportState.ERROR)) return refuse(TransportState.ERROR)
            seen += TransportEvent.Timeout("connect")
            log.timeout("connect")
            return TransportOutcome.Failed(TransportFailure.Timeout("mock-timeout"))
        }
        if (!machine.transition(TransportState.CONNECTED)) return refuse(TransportState.CONNECTED)
        seen += TransportEvent.Connected
        return TransportOutcome.Ok()
    }

    override fun disconnect(): TransportOutcome {
        inbox = null
        when (machine.state) {
            TransportState.DISCONNECTED -> return TransportOutcome.Ok()
            TransportState.CONNECTED -> {
                if (!machine.transition(TransportState.DISCONNECTING)) return refuse(TransportState.DISCONNECTING)
                if (!machine.transition(TransportState.DISCONNECTED)) return refuse(TransportState.DISCONNECTED)
                seen += TransportEvent.Disconnected(clean = true)
                log.disconnect("clean")
                return TransportOutcome.Ok()
            }
            TransportState.ERROR -> {
                if (!machine.transition(TransportState.DISCONNECTED)) return refuse(TransportState.DISCONNECTED)
                seen += TransportEvent.Disconnected(clean = true)
                log.disconnect("clean")
                return TransportOutcome.Ok()
            }
            TransportState.CONNECTING, TransportState.DISCONNECTING -> return refuse(TransportState.DISCONNECTED)
        }
    }

    override fun isConnected(): Boolean = machine.state == TransportState.CONNECTED

    override fun send(payload: ByteArray): TransportOutcome {
        if (payload.size > MAX_FRAME_BYTES) {
            return TransportOutcome.Failed(TransportFailure.UnsupportedOperation("frame"))
        }
        if (machine.state != TransportState.CONNECTED) {
            return TransportOutcome.Failed(TransportFailure.Disconnected("send"))
        }
        when (armedIo) {
            IoScript.TRANSPORT -> {
                armedIo = IoScript.NONE
                inbox = null
                if (!machine.transition(TransportState.ERROR)) return refuse(TransportState.ERROR)
                seen += TransportEvent.TransportError("send")
                log.error("transport", "send")
                return TransportOutcome.Failed(TransportFailure.TransportError("send"))
            }
            IoScript.PROTOCOL -> {
                armedIo = IoScript.NONE
                inbox = null
                seen += TransportEvent.ProtocolError("exchange")
                log.error("protocol", "exchange")
                return TransportOutcome.Failed(TransportFailure.ProtocolError("exchange"))
            }
            IoScript.NONE -> {
                inbox = responder?.onRequest(payload)
                return TransportOutcome.Ok(byteArrayOf(0))
            }
        }
    }

    override fun receive(maxBytes: Int): TransportOutcome {
        if (maxBytes <= 0) {
            return TransportOutcome.Failed(TransportFailure.UnsupportedOperation("receive"))
        }
        if (machine.state != TransportState.CONNECTED) {
            return TransportOutcome.Failed(TransportFailure.Disconnected("receive"))
        }
        val staged = inbox
        inbox = null
        if (staged != null) {
            if (staged.size > maxBytes) {
                return TransportOutcome.Failed(TransportFailure.UnsupportedOperation("receive"))
            }
            return TransportOutcome.Ok(staged)
        }
        return TransportOutcome.Ok(byteArrayOf(0))
    }

    override fun connectionState(): TransportState = machine.state

    override fun events(): List<TransportEvent> = seen.toList()

    private fun refuse(attempted: TransportState): TransportOutcome {
        val error = TransportFailure.InvalidState(machine.state, attempted)
        log.error("invalid_state", "${machine.state.name}->${attempted.name}")
        return TransportOutcome.Failed(error)
    }
}
