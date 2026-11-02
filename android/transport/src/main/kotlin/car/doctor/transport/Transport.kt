package car.doctor.transport

/**
 * Byte pipe under an OBD adapter.
 * This type does not know codes, measurements, or findings.
 * A future BLE or classic socket implements the same surface.
 */
interface ObdTransport {
    val transportType: String

    fun connect(): TransportOutcome
    fun disconnect(): TransportOutcome
    fun isConnected(): Boolean
    fun send(payload: ByteArray): TransportOutcome
    fun receive(maxBytes: Int = 32): TransportOutcome
    fun connectionState(): TransportState
    fun events(): List<TransportEvent>
}

enum class TransportState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR,
}

/** What happened on the link. These are not diagnostic claims and not interchangeable. */
sealed class TransportEvent {
    data object Connected : TransportEvent()
    data class Disconnected(val clean: Boolean) : TransportEvent()
    data class ConnectionFailed(val detail: String) : TransportEvent()
    data class Timeout(val detail: String) : TransportEvent()
    data class TransportError(val detail: String) : TransportEvent()
    data class ProtocolError(val detail: String) : TransportEvent()
}

/** Structured failure. Callers must branch on the type, not on a single string. */
sealed class TransportFailure {
    data class ConnectionFailed(val detail: String) : TransportFailure()
    data class Timeout(val detail: String) : TransportFailure()
    data class Disconnected(val detail: String) : TransportFailure()
    data class UnsupportedOperation(val operation: String) : TransportFailure()
    data class InvalidState(val from: TransportState, val attempted: TransportState) : TransportFailure()
    data class TransportError(val detail: String) : TransportFailure()
    data class ProtocolError(val detail: String) : TransportFailure()
}

sealed class TransportOutcome {
    class Ok(val payload: ByteArray = byteArrayOf()) : TransportOutcome()
    class Failed(val error: TransportFailure) : TransportOutcome()
}

class TransportStateMachine(initial: TransportState = TransportState.DISCONNECTED) {
    var state: TransportState = initial
        private set

    private val seen = mutableListOf(initial)
    val trace: List<TransportState> get() = seen.toList()

    fun canTransition(to: TransportState): Boolean = can(state, to)

    /** @return false when [to] is illegal. The current state is left unchanged. */
    fun transition(to: TransportState): Boolean {
        if (!canTransition(to)) return false
        state = to
        seen.add(to)
        return true
    }

    companion object {
        private val NEXT: Map<TransportState, Set<TransportState>> = mapOf(
            TransportState.DISCONNECTED to setOf(TransportState.CONNECTING),
            TransportState.CONNECTING to setOf(TransportState.CONNECTED, TransportState.ERROR),
            TransportState.CONNECTED to setOf(
                TransportState.DISCONNECTING,
                TransportState.DISCONNECTED,
                TransportState.ERROR,
            ),
            TransportState.DISCONNECTING to setOf(TransportState.DISCONNECTED),
            TransportState.ERROR to setOf(TransportState.CONNECTING, TransportState.DISCONNECTED),
        )

        fun can(from: TransportState, to: TransportState): Boolean = to in NEXT.getValue(from)
    }
}
