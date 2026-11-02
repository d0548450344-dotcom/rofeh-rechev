package car.doctor.transport

/**
 * Link log. Methods take short tokens only.
 * Do not pass frame bytes, a VIN, or any value read from the car.
 */
interface TransportLog {
    fun connect(state: String)
    fun disconnect(kind: String)
    fun retry(attempt: Int, maxAttempts: Int)
    fun timeout(where: String)
    fun error(kind: String, detail: String)

    companion object {
        val NONE: TransportLog = object : TransportLog {
            override fun connect(state: String) = Unit
            override fun disconnect(kind: String) = Unit
            override fun retry(attempt: Int, maxAttempts: Int) = Unit
            override fun timeout(where: String) = Unit
            override fun error(kind: String, detail: String) = Unit
        }
    }
}

class MemoryTransportLog : TransportLog {
    val entries: List<String> get() = lines.toList()
    private val lines = mutableListOf<String>()

    override fun connect(state: String) {
        lines += "connect:$state"
    }

    override fun disconnect(kind: String) {
        lines += "disconnect:$kind"
    }

    override fun retry(attempt: Int, maxAttempts: Int) {
        lines += "retry:$attempt/$maxAttempts"
    }

    override fun timeout(where: String) {
        lines += "timeout:$where"
    }

    override fun error(kind: String, detail: String) {
        lines += "error:$kind:$detail"
    }
}
