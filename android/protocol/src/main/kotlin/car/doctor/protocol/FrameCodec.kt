package car.doctor.protocol

/**
 * Local frame codec.
 *
 * Layout: SOF, body length, service, payload, XOR checksum.
 * Body length counts the service byte plus the payload.
 *
 * This is intentionally not ISO 15765-2. An oversized body is rejected.
 * It is not split into consecutive frames.
 */
object FrameCodec {
    const val SOF: Int = 0xC3
    const val MAX_BODY: Int = 32

    fun encode(service: Int, payload: List<Int>): List<Int> {
        require(service in 0..255) { "service out of range" }
        require(payload.all { it in 0..255 }) { "payload byte out of range" }
        val bodyLen = 1 + payload.size
        require(bodyLen in 1..MAX_BODY) { "frame is not fragmented; body $bodyLen exceeds $MAX_BODY" }
        val head = ArrayList<Int>(3 + payload.size)
        head += SOF
        head += bodyLen
        head += service
        head += payload
        head += checksum(head)
        return head
    }

    fun parse(raw: List<Int>): FrameParse {
        if (raw.isEmpty()) return FrameParse.Bad(ProtocolError.MissingPayload("empty"))
        if (raw.size < 4) return FrameParse.Bad(ProtocolError.UnexpectedLength("short"))
        if ((raw[0] and 0xFF) != SOF) return FrameParse.Bad(ProtocolError.MalformedFrame("sof"))
        val bodyLen = raw[1] and 0xFF
        if (bodyLen < 1 || bodyLen > MAX_BODY) {
            return FrameParse.Bad(ProtocolError.UnexpectedLength("declared"))
        }
        val expected = 2 + bodyLen + 1
        if (raw.size != expected) return FrameParse.Bad(ProtocolError.UnexpectedLength("size"))
        val sum = checksum(raw.dropLast(1))
        if ((raw.last() and 0xFF) != sum) return FrameParse.Bad(ProtocolError.MalformedFrame("checksum"))
        val service = raw[2] and 0xFF
        val payload = raw.subList(3, 3 + bodyLen - 1).map { it and 0xFF }
        return FrameParse.Ok(
            ProtocolFrame(
                service = service,
                payload = payload,
                raw = raw.map { it and 0xFF },
            ),
        )
    }

    fun checksum(bytes: List<Int>): Int {
        var value = 0
        for (byte in bytes) value = value xor (byte and 0xFF)
        return value and 0xFF
    }
}

sealed class FrameParse {
    data class Ok(val frame: ProtocolFrame) : FrameParse()
    data class Bad(val error: ProtocolError) : FrameParse()
}
