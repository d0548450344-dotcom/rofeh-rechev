package car.doctor.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuccessfulConnectionTest {
    @Test
    fun connectReachesConnectedAndExchangesBytes() {
        val transport = MockTransport()
        val connected = transport.connect()
        assertTrue(connected is TransportOutcome.Ok)
        assertTrue(transport.isConnected())
        assertEquals(TransportState.CONNECTED, transport.connectionState())
        assertEquals(
            listOf(
                TransportState.DISCONNECTED,
                TransportState.CONNECTING,
                TransportState.CONNECTED,
            ),
            transport.trace,
        )
        assertEquals(listOf(TransportEvent.Connected), transport.events())
        assertTrue(transport.send(byteArrayOf(1)) is TransportOutcome.Ok)
        val received = transport.receive()
        assertTrue(received is TransportOutcome.Ok)
        assertEquals(1, (received as TransportOutcome.Ok).payload.size)
    }
}

class FailedConnectionTest {
    @Test
    fun connectionFailureIsNotATimeout() {
        val log = MemoryTransportLog()
        val transport = MockTransport(log)
        transport.failNextConnections(1)
        val failed = transport.connect()
        assertTrue(failed is TransportOutcome.Failed)
        assertTrue((failed as TransportOutcome.Failed).error is TransportFailure.ConnectionFailed)
        assertEquals(TransportState.ERROR, transport.connectionState())
        assertFalse(transport.isConnected())
        assertEquals(listOf(TransportEvent.ConnectionFailed("mock-connection-failed")), transport.events())
        assertTrue(transport.events().none { it is TransportEvent.Timeout })
        assertTrue(transport.events().none { it is TransportEvent.Connected })
        val text = log.entries.joinToString(" ")
        assertTrue(text.contains("error:connection_failed:mock"))
        assertFalse(text.contains("timeout"))
    }
}

class TimeoutTest {
    @Test
    fun timeoutIsNotAConnectionFailure() {
        val log = MemoryTransportLog()
        val transport = MockTransport(log)
        transport.timeoutNextConnection()
        val failed = transport.connect()
        assertTrue((failed as TransportOutcome.Failed).error is TransportFailure.Timeout)
        assertEquals(TransportState.ERROR, transport.connectionState())
        assertEquals(listOf(TransportEvent.Timeout("connect")), transport.events())
        assertTrue(transport.events().none { it is TransportEvent.ConnectionFailed })
        val text = log.entries.joinToString(" ")
        assertTrue(text.contains("timeout:connect"))
        assertFalse(text.contains("connection_failed"))
    }
}

class DisconnectTest {
    @Test
    fun cleanDisconnectPassesThroughDisconnecting() {
        val log = MemoryTransportLog()
        val transport = MockTransport(log)
        transport.connect()
        val closed = transport.disconnect()
        assertTrue(closed is TransportOutcome.Ok)
        assertFalse(transport.isConnected())
        assertEquals(TransportState.DISCONNECTED, transport.connectionState())
        assertEquals(
            listOf(
                TransportState.DISCONNECTED,
                TransportState.CONNECTING,
                TransportState.CONNECTED,
                TransportState.DISCONNECTING,
                TransportState.DISCONNECTED,
            ),
            transport.trace,
        )
        assertEquals(TransportEvent.Disconnected(clean = true), transport.events().last())
        assertTrue(log.entries.contains("disconnect:clean"))
        assertTrue(transport.disconnect() is TransportOutcome.Ok)
    }
}

class InvalidTransitionTest {
    @Test
    fun rejectsSkipsAndLeavesTheStateUntouched() {
        val illegal = listOf(
            TransportState.DISCONNECTED to TransportState.CONNECTED,
            TransportState.DISCONNECTED to TransportState.DISCONNECTING,
            TransportState.DISCONNECTED to TransportState.ERROR,
            TransportState.CONNECTING to TransportState.DISCONNECTED,
            TransportState.CONNECTING to TransportState.DISCONNECTING,
            TransportState.DISCONNECTING to TransportState.CONNECTED,
            TransportState.DISCONNECTING to TransportState.CONNECTING,
            TransportState.ERROR to TransportState.CONNECTED,
            TransportState.CONNECTED to TransportState.CONNECTING,
        )
        for ((from, to) in illegal) {
            val machine = TransportStateMachine(from)
            assertFalse("$from -> $to", machine.transition(to))
            assertEquals(from, machine.state)
        }
        val legal = listOf(
            TransportState.DISCONNECTED to TransportState.CONNECTING,
            TransportState.CONNECTING to TransportState.CONNECTED,
            TransportState.CONNECTING to TransportState.ERROR,
            TransportState.CONNECTED to TransportState.DISCONNECTING,
            TransportState.DISCONNECTING to TransportState.DISCONNECTED,
            TransportState.ERROR to TransportState.CONNECTING,
            TransportState.CONNECTED to TransportState.DISCONNECTED,
        )
        for ((from, to) in legal) {
            val machine = TransportStateMachine(from)
            assertTrue("$from -> $to", machine.transition(to))
            assertEquals(to, machine.state)
        }
    }
}

class ReconnectTest {
    @Test
    fun lostLinkReturnsThroughDisconnectedThenConnected() {
        val log = MemoryTransportLog()
        val transport = MockTransport(log)
        transport.connect()
        val dropped = transport.dropLink()
        assertTrue(dropped is TransportOutcome.Ok)
        assertEquals(TransportState.DISCONNECTED, transport.connectionState())
        assertEquals(TransportEvent.Disconnected(clean = false), transport.events().last())
        assertTrue(log.entries.contains("disconnect:lost"))
        assertFalse(transport.trace.contains(TransportState.DISCONNECTING))

        val recovered = TransportReconnector(transport, maxAttempts = 3, log = log).recover()
        assertTrue(recovered is TransportOutcome.Ok)
        assertTrue(transport.isConnected())
        assertEquals(TransportState.CONNECTED, transport.connectionState())
        assertEquals(TransportEvent.Connected, transport.events().last())
    }
}

class RetryLimitTest {
    @Test
    fun stopsAtThreeAndDoesNotLoop() {
        val log = MemoryTransportLog()
        val transport = MockTransport(log)
        transport.failNextConnections(10)
        val failed = TransportReconnector(transport, maxAttempts = 3, log = log).recover()
        assertTrue(failed is TransportOutcome.Failed)
        assertTrue((failed as TransportOutcome.Failed).error is TransportFailure.ConnectionFailed)
        assertEquals(TransportState.ERROR, transport.connectionState())
        assertEquals(3, transport.events().count { it is TransportEvent.ConnectionFailed })
        assertEquals(listOf("retry:1/3", "retry:2/3", "retry:3/3"), log.entries.filter { it.startsWith("retry:") })
        assertTrue(log.entries.none { it.startsWith("retry:4") })
    }

    @Test
    fun rejectsAnUnboundedRetrySetting() {
        val transport = MockTransport()
        assertThrowsIllegal { TransportReconnector(transport, maxAttempts = 0) }
        assertThrowsIllegal { TransportReconnector(transport, maxAttempts = 4) }
    }

    @Test
    fun thirdAttemptCanStillConnect() {
        val transport = MockTransport()
        transport.failNextConnections(2)
        val recovered = TransportReconnector(transport, maxAttempts = 3).recover()
        assertTrue(recovered is TransportOutcome.Ok)
        assertTrue(transport.isConnected())
        assertEquals(2, transport.events().count { it is TransportEvent.ConnectionFailed })
        assertEquals(TransportEvent.Connected, transport.events().last())
    }
}

class TransportErrorTest {
    @Test
    fun transportErrorDropsTheLinkAndProtocolErrorDoesNot() {
        val broken = MockTransport()
        broken.connect()
        broken.failNextSend()
        val send = broken.send(byteArrayOf(1))
        assertTrue((send as TransportOutcome.Failed).error is TransportFailure.TransportError)
        assertEquals(TransportState.ERROR, broken.connectionState())
        assertTrue(broken.events().none { it is TransportEvent.ProtocolError })
        assertTrue(broken.events().none { it is TransportEvent.ConnectionFailed })

        val nack = MockTransport()
        nack.connect()
        nack.failNextProtocol()
        val rejected = nack.send(byteArrayOf(1))
        assertTrue((rejected as TransportOutcome.Failed).error is TransportFailure.ProtocolError)
        assertEquals(TransportState.CONNECTED, nack.connectionState())
        assertTrue(nack.isConnected())
        assertTrue(nack.events().none { it is TransportEvent.TransportError })
        assertTrue(nack.events().none { it is TransportEvent.Timeout })
    }

    @Test
    fun sendWhileDownIsADisconnectNotAFakePayload() {
        val transport = MockTransport()
        val send = transport.send(byteArrayOf(1))
        assertTrue((send as TransportOutcome.Failed).error is TransportFailure.Disconnected)
        assertTrue(transport.events().isEmpty())
        assertEquals(TransportState.DISCONNECTED, transport.connectionState())
    }
}

class TransportLogTest {
    @Test
    fun logKeepsTokensAndDropsTheFrame() {
        val log = MemoryTransportLog()
        val transport = MockTransport(log)
        transport.connect()
        transport.send(byteArrayOf(0xA5.toByte(), 0x5A))
        transport.disconnect()
        val text = log.entries.joinToString(" ")
        assertTrue(text.contains("connect:CONNECTING"))
        assertTrue(text.contains("disconnect:clean"))
        assertFalse(text.contains("a5", ignoreCase = true))
        assertFalse(text.contains("5a", ignoreCase = true))
        assertFalse(text.contains("VIN"))
        assertFalse(text.contains("payload"))
    }
}

private fun assertThrowsIllegal(body: () -> Unit) {
    try {
        body()
    } catch (_: IllegalArgumentException) {
        return
    }
    throw AssertionError("expected IllegalArgumentException")
}
