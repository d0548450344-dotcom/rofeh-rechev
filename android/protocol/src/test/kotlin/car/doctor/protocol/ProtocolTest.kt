package car.doctor.protocol

import car.doctor.transport.MockTransport
import car.doctor.transport.TransportFailure
import car.doctor.transport.TransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolConnectTest {
    @Test
    fun connectInitializesAndASecondConnectDoesNotRestart() {
        val protocol = MockProtocol(MockTransport(), Obd2Profile.foundation)
        val connected = protocol.connect()
        assertTrue(connected is ProtocolOutcome.Ok)
        assertEquals(ProtocolState.READY, protocol.state())
        assertEquals(
            listOf(
                ProtocolState.DISCONNECTED,
                ProtocolState.INITIALIZING,
                ProtocolState.READY,
            ),
            protocol.trace,
        )
        assertEquals(listOf(ProtocolEvent.Ready), protocol.events())
        assertTrue(protocol.connect() is ProtocolOutcome.Ok)
        assertEquals(3, protocol.trace.size)
        val closed = protocol.disconnect()
        assertTrue(closed is ProtocolOutcome.Ok)
        assertEquals(ProtocolState.DISCONNECTED, protocol.state())
        assertTrue(protocol.disconnect() is ProtocolOutcome.Ok)
    }
}

class ProtocolRequestResponseTest {
    @Test
    fun requestReturnsAParsedFrameAndNotTheTransportAck() {
        val transport = MockTransport()
        val protocol = MockProtocol(transport, Obd2Profile.foundation)
        protocol.connect()
        val request = Obd2.requestPid(ObdPid.ENGINE_RPM, protocol.capabilities) as ProtocolOutcome.Ok
        val outcome = protocol.request(request.value)
        assertTrue(outcome is ProtocolOutcome.Ok)
        val response = (outcome as ProtocolOutcome.Ok).value
        assertEquals(0x41, response.service)
        assertEquals(emptyList<Int>(), response.payload)
        assertEquals(ProtocolState.READY, protocol.state())
        assertTrue(transport.isConnected())
        assertTrue(protocol.events().any { it is ProtocolEvent.RequestStarted })
        assertTrue(protocol.events().any { it is ProtocolEvent.ResponseReceived })
    }

    @Test
    fun scriptedBytesAreParsedAsTheResponse() {
        val protocol = connected()
        val raw = FrameCodec.encode(0x41, listOf(0x0C, 0x0C, 0x80))
        protocol.respondWith(raw)
        val request = (Obd2.requestPid(ObdPid.ENGINE_RPM, protocol.capabilities) as ProtocolOutcome.Ok).value
        val response = (protocol.request(request) as ProtocolOutcome.Ok).value
        val decoded = Obd2.decodePid(response) as ProtocolOutcome.Ok
        assertEquals(800.0, decoded.value.value, 0.001)
        assertEquals("rpm", decoded.value.unit)
        assertEquals(listOf(0x0C, 0x80), decoded.value.raw)
        assertEquals(ObdPid.ENGINE_RPM, decoded.value.pid)
    }
}

class FrameParsingTest {
    @Test
    fun validFrameRoundTrips() {
        val raw = FrameCodec.encode(0x01, listOf(0x0C))
        val parsed = FrameCodec.parse(raw) as FrameParse.Ok
        assertEquals(0x01, parsed.frame.service)
        assertEquals(listOf(0x0C), parsed.frame.payload)
        assertEquals(raw, parsed.frame.raw)
    }

    @Test
    fun malformedSofAndChecksumAreStructuredErrors() {
        val sof = FrameCodec.parse(listOf(0x00, 0x01, 0x01, 0x00)) as FrameParse.Bad
        assertTrue(sof.error is ProtocolError.MalformedFrame)
        val valid = FrameCodec.encode(0x01, listOf(0x0D)).toMutableList()
        valid[valid.lastIndex] = valid.last() xor 0xFF
        val sum = FrameCodec.parse(valid) as FrameParse.Bad
        assertTrue(sum.error is ProtocolError.MalformedFrame)
        assertEquals("checksum", (sum.error as ProtocolError.MalformedFrame).detail)
    }

    @Test
    fun unexpectedLengthIsNotAMalformedChecksum() {
        val short = FrameCodec.parse(listOf(0xC3, 0x01)) as FrameParse.Bad
        assertTrue(short.error is ProtocolError.UnexpectedLength)
        val declared = FrameCodec.encode(0x01, listOf(0x0C)).dropLast(1)
        val size = FrameCodec.parse(declared) as FrameParse.Bad
        assertTrue(size.error is ProtocolError.UnexpectedLength)
    }

    @Test
    fun emptyBytesAreAMissingPayload() {
        val parsed = FrameCodec.parse(emptyList()) as FrameParse.Bad
        assertTrue(parsed.error is ProtocolError.MissingPayload)
    }

    @Test
    fun oversizedBodyIsRejectedInsteadOfFragmented() {
        var threw = false
        try {
            FrameCodec.encode(0x01, List(40) { 0x11 })
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}

class EmptyAndMalformedResponseTest {
    @Test
    fun emptyResponseIsAProtocolErrorAndTheLinkStaysUp() {
        val transport = MockTransport()
        val protocol = MockProtocol(transport, Obd2Profile.foundation)
        protocol.connect()
        protocol.emptyNextResponse()
        val failed = protocol.request(ProtocolRequest(0x01, listOf(0x0C)))
        assertTrue(failed is ProtocolOutcome.Failed)
        val failure = (failed as ProtocolOutcome.Failed).failure
        assertTrue(failure is ProtocolFailure.FromProtocol)
        assertTrue((failure as ProtocolFailure.FromProtocol).error is ProtocolError.MissingPayload)
        assertEquals(ProtocolState.READY, protocol.state())
        assertTrue(transport.isConnected())
    }

    @Test
    fun malformedResponseIsNotATransportError() {
        val protocol = connected()
        protocol.malformNextResponse()
        val failed = protocol.request(ProtocolRequest(0x01, listOf(0x0C))) as ProtocolOutcome.Failed
        val failure = failed.failure as ProtocolFailure.FromProtocol
        assertTrue(failure.error is ProtocolError.MalformedFrame)
        assertEquals(ProtocolState.READY, protocol.state())
    }
}

class ProtocolTimeoutTest {
    @Test
    fun protocolTimeoutIsNotATransportTimeout() {
        val transport = MockTransport()
        val protocol = MockProtocol(transport, Obd2Profile.foundation)
        protocol.connect()
        protocol.timeoutNextRequest()
        val failed = protocol.request(ProtocolRequest(0x03)) as ProtocolOutcome.Failed
        val failure = failed.failure as ProtocolFailure.FromProtocol
        assertTrue(failure.error is ProtocolError.Timeout)
        assertFalse(failed.failure is ProtocolFailure.FromTransport)
        assertEquals(TransportState.CONNECTED, transport.connectionState())
        assertEquals(ProtocolState.READY, protocol.state())
    }
}

class TransportFailurePropagationTest {
    @Test
    fun transportErrorIsNotRelabeledAsProtocolError() {
        val transport = MockTransport()
        val protocol = MockProtocol(transport, Obd2Profile.foundation)
        protocol.connect()
        transport.failNextSend()
        val failed = protocol.request(ProtocolRequest(0x01, listOf(0x0C))) as ProtocolOutcome.Failed
        val failure = failed.failure as ProtocolFailure.FromTransport
        assertTrue(failure.error is TransportFailure.TransportError)
        assertFalse(failed.failure is ProtocolFailure.FromProtocol)
        assertEquals(TransportState.ERROR, transport.connectionState())
        assertEquals(ProtocolState.ERROR, protocol.state())
    }

    @Test
    fun transportTimeoutStaysATransportTimeout() {
        val protocol = connected()
        protocol.transportTimeoutNextRequest()
        val failed = protocol.request(ProtocolRequest(0x01, listOf(0x0C))) as ProtocolOutcome.Failed
        val failure = failed.failure as ProtocolFailure.FromTransport
        assertTrue(failure.error is TransportFailure.Timeout)
        assertFalse(failure.error is TransportFailure.ProtocolError)
        assertFalse(failed.failure is ProtocolFailure.FromProtocol)
    }

    @Test
    fun aTransportProtocolSignalIsNotRewritten() {
        val transport = MockTransport()
        val protocol = MockProtocol(transport, Obd2Profile.foundation)
        protocol.connect()
        transport.failNextProtocol()
        val failed = protocol.request(ProtocolRequest(0x01, listOf(0x0C))) as ProtocolOutcome.Failed
        val failure = failed.failure as ProtocolFailure.FromTransport
        assertTrue(failure.error is TransportFailure.ProtocolError)
        assertFalse(failure.error is TransportFailure.TransportError)
        assertEquals(TransportState.CONNECTED, transport.connectionState())
        assertEquals(ProtocolState.READY, protocol.state())
    }

    @Test
    fun requestBeforeConnectIsADisconnectNotAnEmptyResponse() {
        val protocol = MockProtocol(MockTransport(), Obd2Profile.foundation)
        val failed = protocol.request(ProtocolRequest(0x03)) as ProtocolOutcome.Failed
        val failure = failed.failure as ProtocolFailure.FromTransport
        assertTrue(failure.error is TransportFailure.Disconnected)
        assertEquals(ProtocolState.DISCONNECTED, protocol.state())
    }
}

class DisconnectMidRequestTest {
    @Test
    fun lostLinkMidRequestIsATransportDisconnect() {
        val transport = MockTransport()
        val protocol = MockProtocol(transport, Obd2Profile.foundation)
        protocol.connect()
        protocol.disconnectDuringNextRequest()
        val failed = protocol.request(ProtocolRequest(0x03)) as ProtocolOutcome.Failed
        val failure = failed.failure as ProtocolFailure.FromTransport
        assertTrue(failure.error is TransportFailure.Disconnected)
        assertEquals("mid-request", (failure.error as TransportFailure.Disconnected).detail)
        assertFalse(transport.isConnected())
        assertEquals(TransportState.DISCONNECTED, transport.connectionState())
        assertEquals(ProtocolState.DISCONNECTED, protocol.state())
        assertTrue(protocol.events().none { it is ProtocolEvent.ResponseReceived })
    }
}

class InvalidProtocolTransitionTest {
    @Test
    fun skipsAreRejectedAndTheStateStays() {
        val machine = ProtocolStateMachine()
        assertFalse(machine.transition(ProtocolState.READY))
        assertFalse(machine.transition(ProtocolState.EXCHANGING))
        assertEquals(ProtocolState.DISCONNECTED, machine.state)
        assertEquals(listOf(ProtocolState.DISCONNECTED), machine.trace)
        assertTrue(machine.transition(ProtocolState.INITIALIZING))
        assertFalse(machine.transition(ProtocolState.DISCONNECTED))
        assertEquals(ProtocolState.INITIALIZING, machine.state)
    }
}

class ProtocolCapabilitiesTest {
    @Test
    fun foundationDeclaresOnlyWhatThisPhaseDecodes() {
        val caps = Obd2Profile.foundation
        assertTrue(caps.supportsObd2)
        assertTrue(caps.supportsDtc)
        assertTrue(caps.supportsReadiness)
        assertEquals(
            setOf(ObdMode.CURRENT_DATA, ObdMode.STORED_CODES, ObdMode.PENDING_CODES),
            caps.supportedModes,
        )
        assertFalse(caps.supportedModes.contains(ObdMode.CLEAR_CODES))
        assertFalse(caps.supportedModes.contains(ObdMode.FREEZE_FRAME))
        assertFalse(caps.supportedModes.any { it.service == 0x27 })
        assertTrue(ObdPid.ENGINE_RPM in caps.supportedPids)
        assertTrue(ObdPid.VEHICLE_SPEED in caps.supportedPids)
        assertTrue(ObdPid.COOLANT_TEMP in caps.supportedPids)
        assertTrue(ObdPid.CONTROL_MODULE_VOLTAGE in caps.supportedPids)
        assertTrue(ObdPid.THROTTLE_POSITION in caps.supportedPids)
        assertTrue(ObdPid.MONITOR_STATUS in caps.supportedPids)
        assertFalse(ProtocolCapabilities.NONE.supportsObd2)
        assertFalse(ProtocolCapabilities.NONE.supportsDtc)
    }
}

class ObdRequestFoundationTest {
    @Test
    fun buildersFollowTheProfileAndClearCodesAreNeverSent() {
        val caps = Obd2Profile.foundation
        val rpm = Obd2.requestPid(ObdPid.ENGINE_RPM, caps) as ProtocolOutcome.Ok
        assertEquals(ProtocolRequest(0x01, listOf(0x0C)), rpm.value)
        val stored = Obd2.requestStoredCodes(caps) as ProtocolOutcome.Ok
        assertEquals(ProtocolRequest(0x03), stored.value)
        val pending = Obd2.requestPendingCodes(caps) as ProtocolOutcome.Ok
        assertEquals(ProtocolRequest(0x07), pending.value)
        val ready = Obd2.requestReadiness(caps) as ProtocolOutcome.Ok
        assertEquals(ProtocolRequest(0x01, listOf(0x01)), ready.value)

        val blocked = Obd2.requestPid(ObdPid.ENGINE_RPM, ProtocolCapabilities.NONE) as ProtocolOutcome.Failed
        assertTrue((blocked.failure as ProtocolFailure.FromProtocol).error is ProtocolError.Unsupported)

        val transport = MockTransport()
        val before = transport.events().size
        val cleared = Obd2.clearCodes(caps) as ProtocolOutcome.Failed
        assertTrue((cleared.failure as ProtocolFailure.FromProtocol).error is ProtocolError.Unsupported)
        val listed = caps.copy(supportedModes = caps.supportedModes + ObdMode.CLEAR_CODES)
        val still = Obd2.clearCodes(listed) as ProtocolOutcome.Failed
        assertTrue((still.failure as ProtocolFailure.FromProtocol).error is ProtocolError.Unsupported)
        assertEquals(before, transport.events().size)
        assertFalse(transport.isConnected())
    }
}

class PidDecodingTest {
    @Test
    fun standardPidsDecodeWithUnitAndRawBytes() {
        assertPid(ObdPid.ENGINE_RPM, listOf(0x0C, 0x80), 800.0, "rpm")
        assertPid(ObdPid.VEHICLE_SPEED, listOf(0x3E), 62.0, "km/h")
        assertPid(ObdPid.COOLANT_TEMP, listOf(0x82), 90.0, "°C")
        assertPid(ObdPid.CONTROL_MODULE_VOLTAGE, listOf(0x36, 0xB0), 14.0, "V")
        assertPid(ObdPid.THROTTLE_POSITION, listOf(255), 100.0, "%")
    }

    @Test
    fun shortUnknownAndNegativeResponsesDoNotInventAValue() {
        val short = response(0x41, listOf(0x0C, 0x10))
        val missing = Obd2.decodePid(short) as ProtocolOutcome.Failed
        assertTrue((missing.failure as ProtocolFailure.FromProtocol).error is ProtocolError.MissingPayload)

        val unknown = response(0x41, listOf(0xFF, 0x01))
        val undecodable = Obd2.decodePid(unknown) as ProtocolOutcome.Failed
        assertTrue((undecodable.failure as ProtocolFailure.FromProtocol).error is ProtocolError.Undecodable)

        val negative = response(0x7F, listOf(0x01, 0x11))
        val rejected = Obd2.decodePid(negative) as ProtocolOutcome.Failed
        val error = (rejected.failure as ProtocolFailure.FromProtocol).error
        assertTrue(error is ProtocolError.UnexpectedResponse)
        assertFalse(rejected.failure is ProtocolFailure.FromTransport)
    }
}

class DtcResponseFoundationTest {
    @Test
    fun storedCodesDecodeAndPaddingIsNotACode() {
        val response = response(0x43, listOf(0x03, 0x01, 0x00, 0x00, 0x03, 0x00))
        val decoded = Obd2.decodeStoredCodes(response) as ProtocolOutcome.Ok
        assertEquals(listOf("P0301", "P0300"), decoded.value.map { it.code })
        assertEquals(0x0301, decoded.value[0].raw)
    }

    @Test
    fun oddLengthAndNegativeAreErrorsNotAnEmptyCodeList() {
        val odd = Obd2.decodeStoredCodes(response(0x43, listOf(0x03))) as ProtocolOutcome.Failed
        assertTrue((odd.failure as ProtocolFailure.FromProtocol).error is ProtocolError.UnexpectedLength)
        val negative = Obd2.decodeStoredCodes(response(0x7F, listOf(0x03, 0x78))) as ProtocolOutcome.Failed
        assertTrue((negative.failure as ProtocolFailure.FromProtocol).error is ProtocolError.UnexpectedResponse)
        val none = Obd2.decodeStoredCodes(response(0x43, emptyList())) as ProtocolOutcome.Ok
        assertTrue(none.value.isEmpty())
    }
}

class ReadinessFoundationTest {
    @Test
    fun monitorBitmapIsStructuredAndNotAHealthClaim() {
        val response = response(0x41, listOf(0x01, 0x00, 0x07, 0x00, 0x00))
        val decoded = Obd2.decodeReadiness(response) as ProtocolOutcome.Ok
        val report = decoded.value
        assertFalse(report.milOn)
        assertEquals(0, report.emissionCodeCount)
        assertTrue(report.sparkIgnition)
        assertTrue(report.misfireSupported)
        assertTrue(report.misfireComplete)
        assertTrue(report.fuelSupported)
        assertTrue(report.fuelComplete)
        assertTrue(report.componentsSupported)
        assertTrue(report.componentsComplete)
        assertEquals(listOf(0x00, 0x07, 0x00, 0x00), report.raw)

        val incomplete = response(0x41, listOf(0x01, 0x82, 0x07, 0x00, 0x01))
        val flagged = (Obd2.decodeReadiness(incomplete) as ProtocolOutcome.Ok).value
        assertTrue(flagged.milOn)
        assertEquals(2, flagged.emissionCodeCount)
        assertTrue(flagged.misfireSupported)
        assertFalse(flagged.misfireComplete)
        assertTrue(flagged.fuelComplete)
    }

    @Test
    fun aPidDecoderWillNotTreatReadinessAsANumber() {
        val response = response(0x41, listOf(0x01, 0x00, 0x07, 0x00, 0x00))
        val decoded = Obd2.decodePid(response) as ProtocolOutcome.Failed
        assertTrue((decoded.failure as ProtocolFailure.FromProtocol).error is ProtocolError.UnexpectedResponse)
    }
}

class ProtocolLogTest {
    @Test
    fun logKeepsServiceTokensAndDropsFrameBytes() {
        val log = MemoryProtocolLog()
        val protocol = MockProtocol(MockTransport(), Obd2Profile.foundation, log)
        protocol.connect()
        protocol.respondWith(FrameCodec.encode(0x41, listOf(0x0C, 0xAB, 0xCD)))
        protocol.request(ProtocolRequest(0x01, listOf(0x0C)))
        protocol.disconnect()
        val text = log.entries.joinToString(" ")
        assertTrue(text.contains("init:INITIALIZING"))
        assertTrue(text.contains("request:1"))
        assertTrue(text.contains("response:65"))
        assertTrue(text.contains("close:clean"))
        assertFalse(text.contains("ab", ignoreCase = true))
        assertFalse(text.contains("cd", ignoreCase = true))
        assertFalse(text.contains("171"))
        assertFalse(text.contains("205"))
        assertFalse(text.contains("payload"))
        assertFalse(text.contains("VIN"))
    }
}

private fun connected(): MockProtocol {
    val protocol = MockProtocol(MockTransport(), Obd2Profile.foundation)
    check(protocol.connect() is ProtocolOutcome.Ok)
    return protocol
}

private fun assertPid(pid: ObdPid, data: List<Int>, value: Double, unit: String) {
    val decoded = Obd2.decodePid(response(0x41, listOf(pid.code) + data)) as ProtocolOutcome.Ok
    assertEquals(pid, decoded.value.pid)
    assertEquals(value, decoded.value.value, 0.001)
    assertEquals(unit, decoded.value.unit)
    assertEquals(data, decoded.value.raw)
}

private fun response(service: Int, payload: List<Int>): ProtocolResponse {
    val raw = FrameCodec.encode(service, payload)
    val frame = (FrameCodec.parse(raw) as FrameParse.Ok).frame
    return ProtocolResponse(service = frame.service, payload = frame.payload, frame = frame)
}
