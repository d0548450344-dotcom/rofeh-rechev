package car.doctor.protocol

/**
 * Protocol log. Methods take short tokens and service ids only.
 * Do not pass frame bytes, a VIN, or any value read from the car.
 */
interface ProtocolLog {
    fun init(state: String)
    fun request(service: Int)
    fun response(service: Int)
    fun close(kind: String)
    fun error(kind: String)

    companion object {
        val NONE: ProtocolLog = object : ProtocolLog {
            override fun init(state: String) = Unit
            override fun request(service: Int) = Unit
            override fun response(service: Int) = Unit
            override fun close(kind: String) = Unit
            override fun error(kind: String) = Unit
        }
    }
}

class MemoryProtocolLog : ProtocolLog {
    val entries: List<String> get() = lines.toList()
    private val lines = mutableListOf<String>()

    override fun init(state: String) {
        lines += "init:$state"
    }

    override fun request(service: Int) {
        lines += "request:$service"
    }

    override fun response(service: Int) {
        lines += "response:$service"
    }

    override fun close(kind: String) {
        lines += "close:$kind"
    }

    override fun error(kind: String) {
        lines += "error:$kind"
    }
}
