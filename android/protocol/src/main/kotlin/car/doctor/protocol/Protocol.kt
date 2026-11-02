package car.doctor.protocol

/**
 * Diagnostic protocol above a byte pipe.
 * This layer does not know whether the pipe is mock, BLE, classic, or Wi-Fi.
 * It does not produce findings and it does not clear codes.
 *
 * A future ISO 15765 / UDS stack can implement [DiagnosticProtocol] without
 * changing the transport contract. This phase is a single local frame, not ISO-TP.
 */
interface DiagnosticProtocol {
    val capabilities: ProtocolCapabilities

    fun state(): ProtocolState
    fun connect(): ProtocolOutcome<Unit>
    fun disconnect(): ProtocolOutcome<Unit>
    fun request(request: ProtocolRequest): ProtocolOutcome<ProtocolResponse>
    fun events(): List<ProtocolEvent>
}

enum class ProtocolState {
    DISCONNECTED,
    INITIALIZING,
    READY,
    EXCHANGING,
    ERROR,
}

/**
 * What this protocol profile can actually interpret.
 * An adapter that has a link does not automatically get every mode or PID.
 */
data class ProtocolCapabilities(
    val supportsObd2: Boolean,
    val supportedModes: Set<ObdMode>,
    val supportedPids: Set<ObdPid>,
    val supportsDtc: Boolean,
    val supportsReadiness: Boolean,
) {
    companion object {
        val NONE = ProtocolCapabilities(
            supportsObd2 = false,
            supportedModes = emptySet(),
            supportedPids = emptySet(),
            supportsDtc = false,
            supportsReadiness = false,
        )
    }
}

/** One diagnostic exchange, before it is turned into bytes. */
data class ProtocolRequest(
    val service: Int,
    val payload: List<Int> = emptyList(),
)

/** One decoded exchange. [frame] is the parsed wire image, not a finding. */
data class ProtocolResponse(
    val service: Int,
    val payload: List<Int>,
    val frame: ProtocolFrame,
)

/** A single local frame. This is not an ISO-TP multi-frame and not a CAN frame. */
data class ProtocolFrame(
    val service: Int,
    val payload: List<Int>,
    val raw: List<Int>,
)

sealed class ProtocolEvent {
    data object Ready : ProtocolEvent()
    data object Closed : ProtocolEvent()
    data class RequestStarted(val service: Int) : ProtocolEvent()
    data class ResponseReceived(val service: Int) : ProtocolEvent()
    data class Failed(val kind: String) : ProtocolEvent()
}

/**
 * Failure while interpreting a frame.
 * These are not transport failures and must not be relabeled as one.
 */
sealed class ProtocolError {
    data class MalformedFrame(val detail: String) : ProtocolError()
    data class UnexpectedLength(val detail: String) : ProtocolError()
    data class UnexpectedResponse(val detail: String) : ProtocolError()
    data class MissingPayload(val detail: String) : ProtocolError()
    data class Undecodable(val detail: String) : ProtocolError()
    data class Timeout(val detail: String) : ProtocolError()
    data class Unsupported(val detail: String) : ProtocolError()
    data class InvalidState(val from: ProtocolState, val attempted: ProtocolState) : ProtocolError()
}

/**
 * Layered failure. A transport failure stays [FromTransport].
 * A frame failure stays [FromProtocol]. Callers branch on the wrapper first.
 */
sealed class ProtocolFailure {
    data class FromTransport(val error: car.doctor.transport.TransportFailure) : ProtocolFailure()
    data class FromProtocol(val error: ProtocolError) : ProtocolFailure()
}

sealed class ProtocolOutcome<out T> {
    data class Ok<T>(val value: T) : ProtocolOutcome<T>()
    data class Failed(val failure: ProtocolFailure) : ProtocolOutcome<Nothing>()
}

class ProtocolStateMachine(initial: ProtocolState = ProtocolState.DISCONNECTED) {
    var state: ProtocolState = initial
        private set

    private val seen = mutableListOf(initial)
    val trace: List<ProtocolState> get() = seen.toList()

    fun canTransition(to: ProtocolState): Boolean = can(state, to)

    /** @return false when [to] is illegal. The current state is left unchanged. */
    fun transition(to: ProtocolState): Boolean {
        if (!canTransition(to)) return false
        state = to
        seen.add(to)
        return true
    }

    companion object {
        private val NEXT: Map<ProtocolState, Set<ProtocolState>> = mapOf(
            ProtocolState.DISCONNECTED to setOf(ProtocolState.INITIALIZING),
            ProtocolState.INITIALIZING to setOf(ProtocolState.READY, ProtocolState.ERROR),
            ProtocolState.READY to setOf(ProtocolState.EXCHANGING, ProtocolState.DISCONNECTED),
            ProtocolState.EXCHANGING to setOf(
                ProtocolState.READY,
                ProtocolState.ERROR,
                ProtocolState.DISCONNECTED,
            ),
            ProtocolState.ERROR to setOf(ProtocolState.INITIALIZING, ProtocolState.DISCONNECTED),
        )

        fun can(from: ProtocolState, to: ProtocolState): Boolean = to in NEXT.getValue(from)
    }
}
