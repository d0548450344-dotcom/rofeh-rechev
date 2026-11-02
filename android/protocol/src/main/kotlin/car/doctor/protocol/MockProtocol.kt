package car.doctor.protocol

import car.doctor.transport.MockTransport
import car.doctor.transport.ObdTransport
import car.doctor.transport.TransportFailure
import car.doctor.transport.TransportOutcome
import car.doctor.transport.TransportState

private enum class Script {
    NONE,
    EMPTY,
    MALFORMED,
    PROTOCOL_TIMEOUT,
    TRANSPORT_TIMEOUT,
    PROTOCOL_FAULT,
    DISCONNECT,
}

/**
 * Scripted protocol over any [ObdTransport].
 * It moves frames. It does not interpret PIDs or trouble codes.
 * Response bytes are produced here and then parsed, so the codec path is real.
 * The transport ack is only proof that the pipe accepted the request.
 */
class MockProtocol(
    private val transport: ObdTransport,
    override val capabilities: ProtocolCapabilities = ProtocolCapabilities.NONE,
    private val log: ProtocolLog = ProtocolLog.NONE,
) : DiagnosticProtocol {
    private val machine = ProtocolStateMachine()
    private val seen = mutableListOf<ProtocolEvent>()
    private var script = Script.NONE
    private var pending: List<Int>? = null

    val trace: List<ProtocolState> get() = machine.trace

    fun respondWith(raw: List<Int>) {
        pending = raw
    }

    fun emptyNextResponse() {
        script = Script.EMPTY
    }

    fun malformNextResponse() {
        script = Script.MALFORMED
    }

    fun timeoutNextRequest() {
        script = Script.PROTOCOL_TIMEOUT
    }

    fun transportTimeoutNextRequest() {
        script = Script.TRANSPORT_TIMEOUT
    }

    fun protocolFaultNextRequest() {
        script = Script.PROTOCOL_FAULT
    }

    fun disconnectDuringNextRequest() {
        script = Script.DISCONNECT
    }

    override fun state(): ProtocolState = machine.state

    override fun connect(): ProtocolOutcome<Unit> {
        if (machine.state == ProtocolState.READY && transport.isConnected()) {
            return ProtocolOutcome.Ok(Unit)
        }
        if (!machine.transition(ProtocolState.INITIALIZING)) {
            return invalid(ProtocolState.INITIALIZING)
        }
        log.init(ProtocolState.INITIALIZING.name)
        when (val linked = transport.connect()) {
            is TransportOutcome.Failed -> {
                machine.transition(ProtocolState.ERROR)
                return failTransport(linked.error)
            }
            is TransportOutcome.Ok -> Unit
        }
        check(machine.transition(ProtocolState.READY))
        seen += ProtocolEvent.Ready
        return ProtocolOutcome.Ok(Unit)
    }

    override fun disconnect(): ProtocolOutcome<Unit> {
        if (machine.state == ProtocolState.DISCONNECTED) {
            transport.disconnect()
            return ProtocolOutcome.Ok(Unit)
        }
        if (machine.state != ProtocolState.READY && machine.state != ProtocolState.ERROR) {
            return invalid(ProtocolState.DISCONNECTED)
        }
        check(machine.transition(ProtocolState.DISCONNECTED))
        return when (val closed = transport.disconnect()) {
            is TransportOutcome.Failed -> failTransport(closed.error)
            is TransportOutcome.Ok -> {
                seen += ProtocolEvent.Closed
                log.close("clean")
                ProtocolOutcome.Ok(Unit)
            }
        }
    }

    override fun request(request: ProtocolRequest): ProtocolOutcome<ProtocolResponse> {
        if (machine.state == ProtocolState.READY && !transport.isConnected()) {
            machine.transition(ProtocolState.DISCONNECTED)
        }
        if (machine.state != ProtocolState.READY) {
            val error = if (transport.connectionState() == TransportState.ERROR) {
                TransportFailure.TransportError("link-down")
            } else {
                TransportFailure.Disconnected("not-connected")
            }
            return failTransport(error)
        }
        check(machine.transition(ProtocolState.EXCHANGING))
        seen += ProtocolEvent.RequestStarted(request.service)
        log.request(request.service)

        val frame = FrameCodec.encode(request.service, request.payload)
        when (val sent = transport.send(frame.toByteArray())) {
            is TransportOutcome.Failed -> return finishTransport(sent.error)
            is TransportOutcome.Ok -> Unit
        }

        when (takeScript()) {
            Script.DISCONNECT -> {
                if (transport is MockTransport) {
                    transport.dropLink()
                } else {
                    transport.disconnect()
                }
                return finishTransport(TransportFailure.Disconnected("mid-request"))
            }
            Script.TRANSPORT_TIMEOUT -> return finishTransport(TransportFailure.Timeout("request"))
            Script.PROTOCOL_TIMEOUT -> return finishProtocol(ProtocolError.Timeout("request"))
            Script.PROTOCOL_FAULT -> return finishProtocol(ProtocolError.Undecodable("scripted"))
            Script.EMPTY -> {
                val received = transport.receive(MAX_READ)
                if (received is TransportOutcome.Failed) return finishTransport(received.error)
                return finishProtocol(ProtocolError.MissingPayload("empty"))
            }
            Script.MALFORMED -> {
                val received = transport.receive(MAX_READ)
                if (received is TransportOutcome.Failed) return finishTransport(received.error)
                return finishParse(listOf(0x11, 0x01, 0x01, 0x00))
            }
            Script.NONE -> {
                val received = transport.receive(MAX_READ)
                if (received is TransportOutcome.Failed) return finishTransport(received.error)
                val piped = (received as TransportOutcome.Ok).payload.toUnsignedInts()
                val raw = pending ?: piped.takeIf { isLocalFrame(it) } ?: defaultResponse(request)
                pending = null
                return finishParse(raw)
            }
        }
    }

    override fun events(): List<ProtocolEvent> = seen.toList()

    private fun defaultResponse(request: ProtocolRequest): List<Int> {
        val service = (request.service + POSITIVE_OFFSET) and 0xFF
        return FrameCodec.encode(service, emptyList())
    }

    private fun finishParse(raw: List<Int>): ProtocolOutcome<ProtocolResponse> {
        return when (val parsed = FrameCodec.parse(raw)) {
            is FrameParse.Bad -> finishProtocol(parsed.error)
            is FrameParse.Ok -> {
                check(machine.transition(ProtocolState.READY))
                seen += ProtocolEvent.ResponseReceived(parsed.frame.service)
                log.response(parsed.frame.service)
                ProtocolOutcome.Ok(
                    ProtocolResponse(
                        service = parsed.frame.service,
                        payload = parsed.frame.payload,
                        frame = parsed.frame,
                    ),
                )
            }
        }
    }

    private fun finishTransport(error: TransportFailure): ProtocolOutcome<ProtocolResponse> {
        when (error) {
            is TransportFailure.ProtocolError -> {
                if (machine.state == ProtocolState.EXCHANGING) machine.transition(ProtocolState.READY)
            }
            is TransportFailure.Disconnected -> {
                if (machine.state == ProtocolState.EXCHANGING) machine.transition(ProtocolState.DISCONNECTED)
            }
            else -> {
                if (machine.state == ProtocolState.EXCHANGING) machine.transition(ProtocolState.ERROR)
            }
        }
        return failTransport(error)
    }

    private fun finishProtocol(error: ProtocolError): ProtocolOutcome<ProtocolResponse> {
        if (machine.state == ProtocolState.EXCHANGING) machine.transition(ProtocolState.READY)
        return failProtocol(error)
    }

    private fun failTransport(error: TransportFailure): ProtocolOutcome<Nothing> {
        log.error("transport")
        seen += ProtocolEvent.Failed("transport")
        return ProtocolOutcome.Failed(ProtocolFailure.FromTransport(error))
    }

    private fun failProtocol(error: ProtocolError): ProtocolOutcome<Nothing> {
        log.error("protocol")
        seen += ProtocolEvent.Failed("protocol")
        return ProtocolOutcome.Failed(ProtocolFailure.FromProtocol(error))
    }

    private fun invalid(attempted: ProtocolState): ProtocolOutcome<Nothing> {
        val error = ProtocolError.InvalidState(machine.state, attempted)
        return failProtocol(error)
    }

    private fun takeScript(): Script {
        val current = script
        script = Script.NONE
        return current
    }

    companion object {
        private const val POSITIVE_OFFSET = 0x40
        private const val MAX_READ = 64
    }
}

private fun List<Int>.toByteArray(): ByteArray = ByteArray(size) { this[it].toByte() }

private fun ByteArray.toUnsignedInts(): List<Int> = map { it.toInt() and 0xFF }

private fun isLocalFrame(raw: List<Int>): Boolean =
    raw.size >= 4 && (raw[0] and 0xFF) == FrameCodec.SOF
