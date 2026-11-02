package car.doctor.protocol

import car.doctor.transport.ObdTransport
import car.doctor.transport.TransportEvent
import car.doctor.transport.TransportFailure
import car.doctor.transport.TransportOutcome
import car.doctor.transport.TransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scripted ELM327: answers each command line with canned text and a prompt. */
private class FakeElm(
    private val replies: MutableMap<String, String> = mutableMapOf(),
    private val echo: Boolean = false,
) : ObdTransport {
    override val transportType = "fake"
    val sent = mutableListOf<String>()
    private var connected = false
    private val outbox = StringBuilder()
    private val line = StringBuilder()
    var dropOn: String? = null

    fun reply(command: String, text: String) {
        replies[command] = text
    }

    override fun connect(): TransportOutcome {
        connected = true
        outbox.append("\r\rELM327 v1.5\r\r>")
        return TransportOutcome.Ok()
    }

    override fun disconnect(): TransportOutcome {
        connected = false
        return TransportOutcome.Ok()
    }

    override fun isConnected() = connected

    override fun send(payload: ByteArray): TransportOutcome {
        if (!connected) return TransportOutcome.Failed(TransportFailure.Disconnected("send"))
        for (b in payload) {
            val c = b.toInt().toChar()
            if (c == '\r') {
                val cmd = line.toString()
                line.clear()
                sent += cmd
                if (cmd == dropOn) {
                    connected = false
                    return TransportOutcome.Ok()
                }
                if (echo) outbox.append(cmd).append('\r')
                val body = replies[cmd] ?: when {
                    cmd == "ATZ" -> "\r\rELM327 v1.5"
                    cmd.startsWith("AT") -> "OK"
                    else -> "?"
                }
                outbox.append(body).append("\r\r>")
            } else {
                line.append(c)
            }
        }
        return TransportOutcome.Ok()
    }

    override fun receive(maxBytes: Int): TransportOutcome {
        if (!connected) return TransportOutcome.Failed(TransportFailure.Disconnected("receive"))
        val n = minOf(maxBytes, outbox.length)
        val chunk = outbox.substring(0, n)
        outbox.delete(0, n)
        return TransportOutcome.Ok(chunk.toByteArray(Charsets.US_ASCII))
    }

    override fun connectionState() = if (connected) TransportState.CONNECTED else TransportState.DISCONNECTED
    override fun events(): List<TransportEvent> = emptyList()
}

/** Each call advances time so timeouts trip quickly in tests. */
private class TickClock : ElmClock {
    var now = 0L
    override fun nowMs(): Long {
        now += 50
        return now
    }
}

private fun protocolOver(elm: FakeElm) =
    Elm327Protocol(elm, Obd2Profile.foundation, clock = TickClock(), sleeper = ElmSleeper { })

private fun FakeElm.withCar(): FakeElm = apply {
    reply("0100", "SEARCHING...\r41 00 BE 3F A8 13")
    reply("ATDPN", "A6")
}

private fun <T> ProtocolOutcome<T>.value(): T = (this as ProtocolOutcome.Ok<T>).value

class Elm327ProtocolTest {

    @Test
    fun `connect initialises the adapter and records what it found`() {
        val elm = FakeElm().withCar()
        val protocol = protocolOver(elm)

        assertTrue(protocol.connect() is ProtocolOutcome.Ok)
        assertEquals(ProtocolState.READY, protocol.state())
        val id = protocol.identity!!
        assertEquals("ELM327 V1.5", id.banner)
        assertEquals('6', id.protocolNumber)
        assertTrue(0x0C in id.supportedPids)
        assertTrue(0x05 in id.supportedPids)
        assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATS1", "ATH0", "ATAT1", "ATSP0", "0100", "ATDPN"), elm.sent)
    }

    @Test
    fun `no car on the bus fails connect with a clear reason and closes the link`() {
        val elm = FakeElm().apply { reply("0100", "SEARCHING...\rUNABLE TO CONNECT") }
        val protocol = protocolOver(elm)

        val outcome = protocol.connect()
        assertTrue(outcome is ProtocolOutcome.Failed)
        assertEquals(ElmErrorKind.NO_VEHICLE, protocol.lastAdapterError)
        assertFalse(elm.isConnected())
        assertEquals(ProtocolState.DISCONNECTED, protocol.state())
    }

    @Test
    fun `rpm comes back decoded through the standard formula`() {
        val elm = FakeElm().withCar().apply { reply("010C", "41 0C 1A F8") }
        val protocol = protocolOver(elm)
        protocol.connect()

        val request = Obd2.requestPid(ObdPid.ENGINE_RPM, protocol.capabilities).value()
        val reading = Obd2.decodePid(protocol.request(request).value()).value()
        assertEquals(1726.0, reading.value, 0.001)
    }

    @Test
    fun `adapter echo left on is tolerated`() {
        val elm = FakeElm(echo = true).withCar().apply { reply("010D", "41 0D 32") }
        val protocol = protocolOver(elm)
        assertTrue(protocol.connect() is ProtocolOutcome.Ok)

        val request = Obd2.requestPid(ObdPid.VEHICLE_SPEED, protocol.capabilities).value()
        val reading = Obd2.decodePid(protocol.request(request).value()).value()
        assertEquals(50.0, reading.value, 0.001)
    }

    @Test
    fun `stored codes on CAN skip the count byte`() {
        val elm = FakeElm().withCar().apply { reply("03", "43 02 01 33 03 00") }
        val protocol = protocolOver(elm)
        protocol.connect()

        val codes = Obd2.decodeStoredCodes(protocol.request(ProtocolRequest(0x03)).value()).value()
        assertEquals(listOf("P0133", "P0300"), codes.map { it.code })
    }

    @Test
    fun `stored codes across a multi-frame CAN reply`() {
        val elm = FakeElm().withCar().apply {
            reply("03", "00A\r0: 43 04 01 33 03 00\r1: 01 71 C1 23 00 00")
        }
        val protocol = protocolOver(elm)
        protocol.connect()

        val codes = Obd2.decodeStoredCodes(protocol.request(ProtocolRequest(0x03)).value()).value()
        assertEquals(listOf("P0133", "P0300", "P0171", "U0123"), codes.map { it.code })
    }

    @Test
    fun `stored codes from two ECUs are merged`() {
        val elm = FakeElm().withCar().apply { reply("03", "43 01 03 00\r43 01 07 00") }
        val protocol = protocolOver(elm)
        protocol.connect()

        val codes = Obd2.decodeStoredCodes(protocol.request(ProtocolRequest(0x03)).value()).value()
        assertEquals(listOf("P0300", "P0700"), codes.map { it.code })
    }

    @Test
    fun `stored codes on K-line have no count byte`() {
        val elm = FakeElm().apply {
            reply("0100", "SEARCHING...\r41 00 BE 3F A8 13")
            reply("ATDPN", "A3")
            reply("03", "43 01 33 03 00 00 00")
        }
        val protocol = protocolOver(elm)
        protocol.connect()

        val codes = Obd2.decodeStoredCodes(protocol.request(ProtocolRequest(0x03)).value()).value()
        assertEquals(listOf("P0133", "P0300"), codes.map { it.code })
    }

    @Test
    fun `NO DATA is a missing payload, not a crash and not a code`() {
        val elm = FakeElm().withCar().apply { reply("0111", "NO DATA") }
        val protocol = protocolOver(elm)
        protocol.connect()

        val outcome = protocol.request(ProtocolRequest(0x01, listOf(0x11)))
        val error = ((outcome as ProtocolOutcome.Failed).failure as ProtocolFailure.FromProtocol).error
        assertTrue(error is ProtocolError.MissingPayload)
        assertEquals(ProtocolState.READY, protocol.state())
    }

    @Test
    fun `clear codes is refused and never reaches the adapter`() {
        val elm = FakeElm().withCar()
        val protocol = protocolOver(elm)
        protocol.connect()
        val before = elm.sent.size

        val outcome = protocol.request(ProtocolRequest(0x04))
        assertTrue(outcome is ProtocolOutcome.Failed)
        assertEquals(before, elm.sent.size)
        assertFalse(elm.sent.any { it == "04" })
    }

    @Test
    fun `an empty line is never sent`() {
        val elm = FakeElm().withCar().apply { reply("03", "43 00") }
        val protocol = protocolOver(elm)
        protocol.connect()
        protocol.request(ProtocolRequest(0x03))
        protocol.disconnect()

        assertFalse(elm.sent.any { it.isEmpty() })
    }

    @Test
    fun `a command the adapter rejects keeps the session usable`() {
        val elm = FakeElm().withCar()
        val protocol = protocolOver(elm)
        protocol.connect()
        elm.dropOn = null
        // Unknown command to the fake yields "?" -> adapter rejected, still READY.
        val rejected = protocol.request(ProtocolRequest(0x09, listOf(0x02)))
        assertTrue(rejected is ProtocolOutcome.Failed)
        assertEquals(ProtocolState.READY, protocol.state())
    }

    @Test
    fun `link lost mid request is reported as a transport failure`() {
        val elm = FakeElm().withCar().apply { dropOn = "03" }
        val protocol = protocolOver(elm)
        protocol.connect()

        val outcome = protocol.request(ProtocolRequest(0x03))
        val failure = (outcome as ProtocolOutcome.Failed).failure
        assertTrue(failure is ProtocolFailure.FromTransport)
        assertEquals(ProtocolState.DISCONNECTED, protocol.state())
    }

    @Test
    fun `parser handles status words`() {
        assertEquals(ElmResponseParser.Result.NoData, ElmResponseParser.parse("NO DATA\r\r>"))
        val busy = ElmResponseParser.parse("BUS INIT: ...ERROR\r>")
        assertEquals(ElmErrorKind.NO_VEHICLE, (busy as ElmResponseParser.Result.AdapterError).kind)
        val ok = ElmResponseParser.parse("BUS INIT: ...OK\r41 0C 0F A0\r>")
        assertEquals(listOf(listOf(0x41, 0x0C, 0x0F, 0xA0)), (ok as ElmResponseParser.Result.Messages).messages)
        assertNull(ElmResponseParser.classify("41 0C 0F A0"))
    }
}
