package car.doctor.protocol

import car.doctor.transport.ObdTransport
import car.doctor.transport.TransportFailure
import car.doctor.transport.TransportOutcome

/** What the adapter told us about itself during initialisation. */
data class ElmIdentity(
    /** Banner from ATZ / ATI, e.g. "ELM327 v1.5". Clones often lie about the version. */
    val banner: String?,
    /** Bus protocol number from ATDPN: 1-5 legacy (J1850 / ISO 9141 / KWP), 6-9 and A-C CAN. */
    val protocolNumber: Char?,
    /** Mode 01 PIDs 01-20 the engine ECU reported as supported (from 0100). */
    val supportedPids: Set<Int>,
)

fun interface ElmClock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = ElmClock { System.nanoTime() / 1_000_000 }
    }
}

fun interface ElmSleeper {
    fun sleep(ms: Long)

    companion object {
        val SYSTEM = ElmSleeper { ms -> if (ms > 0) Thread.sleep(ms) }
    }
}

/**
 * Real OBD-II over an ELM327 (or compatible) adapter, on any [ObdTransport].
 *
 * Reads through [request], which accepts only [READ_ONLY_SERVICES]. The single write is
 * mode 04 (clear codes) through [clearCodes], which needs a [ClearCodesConsent].
 * Flashing, coding, security access and every other service in
 * [WRITE_AND_FLASH_SERVICES] cannot be sent at all.
 *
 * All calls block. Run them off the UI thread.
 */
class Elm327Protocol(
    private val transport: ObdTransport,
    override val capabilities: ProtocolCapabilities = Obd2Profile.foundation,
    private val log: ProtocolLog = ProtocolLog.NONE,
    private val clock: ElmClock = ElmClock.SYSTEM,
    private val sleeper: ElmSleeper = ElmSleeper.SYSTEM,
    private val requestTimeoutMs: Long = 6_000,
) : DiagnosticProtocol {
    private val machine = ProtocolStateMachine()
    private val seen = mutableListOf<ProtocolEvent>()

    var identity: ElmIdentity? = null
        private set

    /** Set when connect() fails because the adapter answered but the car did not. */
    var lastAdapterError: ElmErrorKind? = null
        private set

    val trace: List<ProtocolState> get() = machine.trace

    override fun state(): ProtocolState = machine.state

    @Synchronized
    override fun connect(): ProtocolOutcome<Unit> {
        if (machine.state == ProtocolState.READY && transport.isConnected()) return ProtocolOutcome.Ok(Unit)
        lastAdapterError = null
        if (!machine.transition(ProtocolState.INITIALIZING)) return invalid(ProtocolState.INITIALIZING)
        log.init(ProtocolState.INITIALIZING.name)

        when (val linked = transport.connect()) {
            is TransportOutcome.Failed -> {
                machine.transition(ProtocolState.ERROR)
                return failTransport(linked.error)
            }
            is TransportOutcome.Ok -> Unit
        }

        // Drop anything the adapter printed on power-up. Never send a bare carriage
        // return here: an ELM327 repeats its previous command on an empty line, and
        // that command may have come from another app.
        drain(300)?.let { return abortInit(it) }

        var banner: String? = null
        for (command in INIT_COMMANDS) {
            val timeout = if (command == "ATZ") 4_000L else 2_000L
            val text = when (val reply = exchange(command, timeout)) {
                is Exchange.Failed -> return abortInit(reply.failure)
                is Exchange.Text -> reply.text
            }
            val lines = ElmResponseParser.lines(text, command)
            if (command in RESET_COMMANDS) {
                banner = lines.firstOrNull { it.contains("ELM") || it.contains("OBD") }
                sleeper.sleep(300)
            }
            val ok = lines.any { it == "OK" } || command == "ATZ"
            if (!ok && command in REQUIRED_COMMANDS) {
                return abortInit(ProtocolFailure.FromProtocol(ProtocolError.Unsupported("init:$command")))
            }
        }

        // First real request: the adapter searches for the bus protocol here.
        val search = when (val reply = exchange("0100", 15_000)) {
            is Exchange.Failed -> return abortInit(reply.failure)
            is Exchange.Text -> ElmResponseParser.parse(reply.text, "0100")
        }
        val supported = when (search) {
            is ElmResponseParser.Result.AdapterError -> {
                lastAdapterError = search.kind
                return abortInit(ProtocolFailure.FromProtocol(ProtocolError.Unsupported("bus:${search.word}")))
            }
            ElmResponseParser.Result.NoData -> {
                lastAdapterError = ElmErrorKind.NO_VEHICLE
                return abortInit(ProtocolFailure.FromProtocol(ProtocolError.MissingPayload("no-vehicle")))
            }
            is ElmResponseParser.Result.Messages -> supportedPids(search.messages)
        }

        val protocolNumber = when (val reply = exchange("ATDPN", 2_000)) {
            is Exchange.Failed -> return abortInit(reply.failure)
            is Exchange.Text -> ElmResponseParser.lines(reply.text, "ATDPN")
                .firstOrNull()
                ?.removePrefix("A")
                ?.firstOrNull { it.isLetterOrDigit() }
        }

        identity = ElmIdentity(banner = banner, protocolNumber = protocolNumber, supportedPids = supported)
        check(machine.transition(ProtocolState.READY))
        seen += ProtocolEvent.Ready
        return ProtocolOutcome.Ok(Unit)
    }

    @Synchronized
    override fun disconnect(): ProtocolOutcome<Unit> {
        if (machine.state == ProtocolState.DISCONNECTED) {
            transport.disconnect()
            return ProtocolOutcome.Ok(Unit)
        }
        if (machine.state == ProtocolState.READY) {
            // Return the adapter to low power politely; ignore the answer.
            exchange("ATPC", 500)
        }
        if (machine.state != ProtocolState.READY && machine.state != ProtocolState.ERROR) {
            return invalid(ProtocolState.DISCONNECTED)
        }
        machine.transition(ProtocolState.DISCONNECTED)
        return when (val closed = transport.disconnect()) {
            is TransportOutcome.Failed -> failTransport(closed.error)
            is TransportOutcome.Ok -> {
                seen += ProtocolEvent.Closed
                log.close("clean")
                ProtocolOutcome.Ok(Unit)
            }
        }
    }

    @Synchronized
    override fun request(request: ProtocolRequest): ProtocolOutcome<ProtocolResponse> {
        if (request.service !in READ_ONLY_SERVICES) {
            return failProtocol(ProtocolError.Unsupported("service-${request.service}"))
        }
        if (machine.state == ProtocolState.READY && !transport.isConnected()) {
            machine.transition(ProtocolState.DISCONNECTED)
        }
        if (machine.state != ProtocolState.READY) {
            return failTransport(TransportFailure.Disconnected("not-connected"))
        }
        check(machine.transition(ProtocolState.EXCHANGING))
        seen += ProtocolEvent.RequestStarted(request.service)
        log.request(request.service)

        val command = buildString {
            append(hex(request.service))
            request.payload.forEach { append(hex(it)) }
        }
        val text = when (val reply = exchange(command, requestTimeoutMs)) {
            is Exchange.Failed -> return finish(reply.failure)
            is Exchange.Text -> reply.text
        }
        val positive = (request.service + POSITIVE_OFFSET) and 0xFF
        return when (val parsed = ElmResponseParser.parse(text, command)) {
            ElmResponseParser.Result.NoData -> finishProtocol(ProtocolError.MissingPayload("no-data"))
            is ElmResponseParser.Result.AdapterError -> when (parsed.kind) {
                ElmErrorKind.NO_VEHICLE -> finishProtocol(ProtocolError.MissingPayload("no-vehicle"))
                ElmErrorKind.STOPPED -> finishProtocol(ProtocolError.Timeout("stopped"))
                ElmErrorKind.UNKNOWN_COMMAND -> finishProtocol(ProtocolError.Unsupported("adapter-rejected"))
                else -> finishProtocol(ProtocolError.MalformedFrame(parsed.word))
            }
            is ElmResponseParser.Result.Messages -> respond(request, positive, parsed.messages)
        }
    }

    override fun events(): List<ProtocolEvent> = seen.toList()

    /**
     * Mode 04. Clears stored and pending codes, freeze frame and readiness monitors on every
     * emissions ECU that answers. It cannot clear permanent codes and it fixes nothing.
     * Most ECUs refuse it while the engine runs (negative answer 0x22).
     */
    @Synchronized
    fun clearCodes(consent: ClearCodesConsent): ProtocolOutcome<Unit> {
        val built = Obd2.clearCodes(capabilities + ObdMode.CLEAR_CODES, consent)
        if (built is ProtocolOutcome.Failed) return built
        if (machine.state == ProtocolState.READY && !transport.isConnected()) {
            machine.transition(ProtocolState.DISCONNECTED)
        }
        if (machine.state != ProtocolState.READY) {
            return failTransport(TransportFailure.Disconnected("not-connected"))
        }
        check(machine.transition(ProtocolState.EXCHANGING))
        seen += ProtocolEvent.RequestStarted(ObdMode.CLEAR_CODES.service)
        log.request(ObdMode.CLEAR_CODES.service)
        val text = when (val reply = exchange("04", CLEAR_TIMEOUT_MS)) {
            is Exchange.Failed -> return when (val f = finish(reply.failure)) {
                is ProtocolOutcome.Failed -> f
                is ProtocolOutcome.Ok -> ProtocolOutcome.Ok(Unit)
            }
            is Exchange.Text -> reply.text
        }
        val response = when (val parsed = ElmResponseParser.parse(text, "04")) {
            ElmResponseParser.Result.NoData -> return finishProtocol(ProtocolError.MissingPayload("no-clear-confirmation")).unit()
            is ElmResponseParser.Result.AdapterError -> return finishProtocol(ProtocolError.MalformedFrame(parsed.word)).unit()
            is ElmResponseParser.Result.Messages -> {
                val positive = parsed.messages.firstOrNull { it.firstOrNull() == 0x44 }
                val negative = parsed.messages.firstOrNull { it.size >= 2 && it[0] == NEGATIVE && it[1] == 0x04 }
                when {
                    positive != null -> ok(0x44, emptyList())
                    negative != null -> ok(NEGATIVE, negative.drop(1))
                    else -> return finishProtocol(ProtocolError.UnexpectedResponse("service")).unit()
                }
            }
        }
        return when (response) {
            is ProtocolOutcome.Failed -> response
            is ProtocolOutcome.Ok -> Obd2.decodeClear(response.value)
        }
    }

    private fun ProtocolOutcome<ProtocolResponse>.unit(): ProtocolOutcome<Unit> = when (this) {
        is ProtocolOutcome.Failed -> this
        is ProtocolOutcome.Ok -> ProtocolOutcome.Ok(Unit)
    }

    private operator fun ProtocolCapabilities.plus(mode: ObdMode): ProtocolCapabilities =
        copy(supportedModes = supportedModes + mode)

    private fun respond(
        request: ProtocolRequest,
        positive: Int,
        messages: List<List<Int>>,
    ): ProtocolOutcome<ProtocolResponse> {
        val mine = messages.filter { it.isNotEmpty() && it[0] == positive }
        if (mine.isEmpty()) {
            val negative = messages.firstOrNull { it.size >= 2 && it[0] == NEGATIVE && it[1] == request.service }
            if (negative != null) return ok(NEGATIVE, negative.drop(1))
            return finishProtocol(ProtocolError.UnexpectedResponse("service"))
        }
        val payload = when (request.service) {
            ObdMode.STORED_CODES.service,
            ObdMode.PENDING_CODES.service,
            ObdMode.PERMANENT_CODES.service,
            -> ElmResponseParser.codePairs(mine, positive)
            else -> {
                // Mode 01 / 02 / 09: the first ECU that echoes the requested PID wins.
                val wanted = request.payload.firstOrNull()
                val match = mine.firstOrNull { wanted == null || (it.size > 1 && it[1] == wanted) }
                    ?: return finishProtocol(ProtocolError.UnexpectedResponse("pid"))
                match.drop(1)
            }
        }
        return ok(positive, payload)
    }

    private fun ok(service: Int, payload: List<Int>): ProtocolOutcome<ProtocolResponse> {
        check(machine.transition(ProtocolState.READY))
        seen += ProtocolEvent.ResponseReceived(service)
        log.response(service)
        val raw = listOf(service) + payload
        return ProtocolOutcome.Ok(
            ProtocolResponse(
                service = service,
                payload = payload,
                frame = ProtocolFrame(service = service, payload = payload, raw = raw),
            ),
        )
    }

    private fun drain(windowMs: Long): ProtocolFailure? {
        val until = clock.nowMs() + windowMs
        while (clock.nowMs() < until) {
            val got = transport.receive(READ_CHUNK)
            if (got is TransportOutcome.Failed) return ProtocolFailure.FromTransport(got.error)
        }
        return null
    }

    /** Sends one command line and collects text up to the '>' prompt. */
    private fun exchange(command: String, timeoutMs: Long): Exchange {
        require(command.isNotEmpty()) { "an empty line makes the ELM327 repeat its last command" }
        val sent = transport.send("$command\r".toByteArray(Charsets.US_ASCII))
        if (sent is TransportOutcome.Failed) return Exchange.Failed(ProtocolFailure.FromTransport(sent.error))
        val text = StringBuilder()
        val deadline = clock.nowMs() + timeoutMs
        while (true) {
            when (val got = transport.receive(READ_CHUNK)) {
                is TransportOutcome.Failed -> return Exchange.Failed(ProtocolFailure.FromTransport(got.error))
                is TransportOutcome.Ok -> {
                    for (byte in got.payload) {
                        val c = (byte.toInt() and 0xFF).toChar()
                        if (c != '\u0000') text.append(c)
                    }
                }
            }
            if (text.contains('>')) return Exchange.Text(text.toString())
            if (text.length > MAX_REPLY_CHARS) {
                return Exchange.Failed(ProtocolFailure.FromProtocol(ProtocolError.UnexpectedLength("reply")))
            }
            if (clock.nowMs() >= deadline) {
                return Exchange.Failed(ProtocolFailure.FromProtocol(ProtocolError.Timeout(command)))
            }
        }
    }

    private fun supportedPids(messages: List<List<Int>>): Set<Int> {
        val reply = messages.firstOrNull { it.size >= 6 && it[0] == 0x41 && it[1] == 0x00 } ?: return emptySet()
        val bits = reply.subList(2, 6)
        val out = mutableSetOf<Int>()
        for (byteIndex in 0 until 4) {
            for (bit in 0 until 8) {
                if ((bits[byteIndex] shr (7 - bit)) and 1 == 1) out += byteIndex * 8 + bit + 1
            }
        }
        return out
    }

    private fun abortInit(failure: ProtocolFailure): ProtocolOutcome<Unit> {
        machine.transition(ProtocolState.ERROR)
        transport.disconnect()
        machine.transition(ProtocolState.DISCONNECTED)
        log.error(if (failure is ProtocolFailure.FromTransport) "transport" else "protocol")
        seen += ProtocolEvent.Failed("init")
        return ProtocolOutcome.Failed(failure)
    }

    private fun finish(failure: ProtocolFailure): ProtocolOutcome<ProtocolResponse> = when (failure) {
        is ProtocolFailure.FromProtocol -> finishProtocol(failure.error)
        is ProtocolFailure.FromTransport -> {
            val next = if (failure.error is TransportFailure.Disconnected) ProtocolState.DISCONNECTED else ProtocolState.ERROR
            if (machine.state == ProtocolState.EXCHANGING) machine.transition(next)
            failTransport(failure.error)
        }
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

    private fun invalid(attempted: ProtocolState): ProtocolOutcome<Nothing> =
        failProtocol(ProtocolError.InvalidState(machine.state, attempted))

    private sealed class Exchange {
        class Text(val text: String) : Exchange()
        class Failed(val failure: ProtocolFailure) : Exchange()
    }

    companion object {
        private const val POSITIVE_OFFSET = 0x40
        private const val NEGATIVE = 0x7F
        private const val READ_CHUNK = 512
        private const val MAX_REPLY_CHARS = 16_384
        private const val CLEAR_TIMEOUT_MS = 10_000L

        /**
         * Setup sequence: reset, echo off, linefeeds off, spaces on, headers off,
         * adaptive timing, automatic protocol search. Nothing here writes to the car.
         */
        val INIT_COMMANDS: List<String> = listOf("ATZ", "ATE0", "ATL0", "ATS1", "ATH0", "ATAT1", "ATSP0")
        private val REQUIRED_COMMANDS = setOf("ATE0", "ATH0", "ATSP0")
        private val RESET_COMMANDS = setOf("ATZ")

        /** Services [request] will put on the bus. 0x04 (clear) only goes through [clearCodes]. */
        val READ_ONLY_SERVICES: Set<Int> = setOf(0x01, 0x02, 0x03, 0x07, 0x09, 0x0A)

        init {
            check(READ_ONLY_SERVICES.none { it in WRITE_AND_FLASH_SERVICES || it == 0x04 })
        }

        private fun hex(value: Int): String = "%02X".format(value and 0xFF)
    }
}
