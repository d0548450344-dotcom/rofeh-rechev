package car.doctor.transport

/**
 * Finite recovery after a lost link.
 * [maxAttempts] is configurable from 1 to [MAX_ATTEMPTS]. There is no open-ended loop.
 *
 * Expected shape after a drop:
 * CONNECTED → DISCONNECTED → CONNECTING → CONNECTED.
 */
class TransportReconnector(
    private val transport: ObdTransport,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val log: TransportLog = TransportLog.NONE,
) {
    init {
        require(maxAttempts in 1..MAX_ATTEMPTS) {
            "retry limit must be between 1 and $MAX_ATTEMPTS"
        }
    }

    fun recover(): TransportOutcome {
        if (transport.isConnected()) return TransportOutcome.Ok()
        var last: TransportOutcome = TransportOutcome.Failed(TransportFailure.Disconnected("not-connected"))
        for (attempt in 1..maxAttempts) {
            log.retry(attempt, maxAttempts)
            last = transport.connect()
            if (last is TransportOutcome.Ok) return last
            if ((last as TransportOutcome.Failed).error is TransportFailure.InvalidState) return last
        }
        return last
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
        const val MAX_ATTEMPTS = 3
    }
}
