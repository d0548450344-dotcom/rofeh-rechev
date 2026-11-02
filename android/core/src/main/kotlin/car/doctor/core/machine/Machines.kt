package car.doctor.core.machine

import car.doctor.core.model.IgnitionState
import car.doctor.core.model.ObdSessionState

data class IgnitionMove(val to: IgnitionState, val labelHe: String)

class IgnitionStateMachine(initial: IgnitionState = IgnitionState.OFF) {
    var state: IgnitionState = initial
        private set

    fun canTransition(to: IgnitionState): Boolean = can(state, to)

    /** @return false when [to] is illegal. The current state is left unchanged. */
    fun transition(to: IgnitionState): Boolean {
        if (!canTransition(to)) return false
        state = to
        return true
    }

    companion object {
        private val NEXT: Map<IgnitionState, Set<IgnitionState>> = mapOf(
            IgnitionState.OFF to setOf(IgnitionState.ACC),
            IgnitionState.ACC to setOf(IgnitionState.OFF, IgnitionState.ON),
            IgnitionState.ON to setOf(IgnitionState.OFF, IgnitionState.ACC, IgnitionState.STARTING),
            IgnitionState.STARTING to setOf(IgnitionState.RUNNING, IgnitionState.ON, IgnitionState.OFF),
            IgnitionState.RUNNING to setOf(IgnitionState.OFF, IgnitionState.ON),
        )

        fun can(from: IgnitionState, to: IgnitionState): Boolean = to in NEXT.getValue(from)

        fun allowsObd(state: IgnitionState): Boolean = state == IgnitionState.ON ||
            state == IgnitionState.STARTING ||
            state == IgnitionState.RUNNING
    }
}

fun ignitionMoves(from: IgnitionState): List<IgnitionMove> = when (from) {
    IgnitionState.OFF -> listOf(IgnitionMove(IgnitionState.ACC, "אביזרים"))
    IgnitionState.ACC -> listOf(
        IgnitionMove(IgnitionState.ON, "הצתה"),
        IgnitionMove(IgnitionState.OFF, "כיבוי"),
    )
    IgnitionState.ON -> listOf(
        IgnitionMove(IgnitionState.STARTING, "התנעה"),
        IgnitionMove(IgnitionState.ACC, "חזרה לאביזרים"),
        IgnitionMove(IgnitionState.OFF, "כיבוי"),
    )
    IgnitionState.STARTING -> listOf(
        IgnitionMove(IgnitionState.RUNNING, "המנוע נתפס"),
        IgnitionMove(IgnitionState.ON, "התנעה נכשלה"),
        IgnitionMove(IgnitionState.OFF, "ביטול"),
    )
    IgnitionState.RUNNING -> listOf(
        IgnitionMove(IgnitionState.OFF, "כיבוי"),
        IgnitionMove(IgnitionState.ON, "המנוע כבה, ההצתה דולקת"),
    )
}

class ObdSessionStateMachine(initial: ObdSessionState = ObdSessionState.DISCONNECTED) {
    var state: ObdSessionState = initial
        private set

    fun canTransition(to: ObdSessionState): Boolean = can(state, to)

    /** @return false when [to] is illegal. The current state is left unchanged. */
    fun transition(to: ObdSessionState): Boolean {
        if (!canTransition(to)) return false
        state = to
        return true
    }

    fun disconnectPath(): List<ObdSessionState>? = disconnectPath(state)

    companion object {
        private val NEXT: Map<ObdSessionState, Set<ObdSessionState>> = mapOf(
            ObdSessionState.DISCONNECTED to setOf(ObdSessionState.CONNECTING),
            ObdSessionState.CONNECTING to setOf(ObdSessionState.CONNECTED, ObdSessionState.ERROR),
            ObdSessionState.CONNECTED to setOf(
                ObdSessionState.SCANNING,
                ObdSessionState.DISCONNECTING,
                ObdSessionState.ERROR,
            ),
            ObdSessionState.SCANNING to setOf(
                ObdSessionState.COMPLETE,
                ObdSessionState.ERROR,
                ObdSessionState.DISCONNECTING,
            ),
            ObdSessionState.COMPLETE to setOf(ObdSessionState.SCANNING, ObdSessionState.DISCONNECTING),
            ObdSessionState.DISCONNECTING to setOf(ObdSessionState.DISCONNECTED),
            ObdSessionState.ERROR to setOf(ObdSessionState.DISCONNECTED, ObdSessionState.CONNECTING),
        )

        fun can(from: ObdSessionState, to: ObdSessionState): Boolean = to in NEXT.getValue(from)

        /**
         * Legal steps to drop the link.
         * CONNECTED → DISCONNECTED is not an edge.
         * The path is CONNECTED → DISCONNECTING → DISCONNECTED.
         * CONNECTING has no disconnect edge, so the caller must wait or fail.
         */
        fun disconnectPath(from: ObdSessionState): List<ObdSessionState>? = when (from) {
            ObdSessionState.DISCONNECTED -> emptyList()
            ObdSessionState.DISCONNECTING -> listOf(ObdSessionState.DISCONNECTED)
            ObdSessionState.ERROR ->
                if (can(from, ObdSessionState.DISCONNECTED)) listOf(ObdSessionState.DISCONNECTED) else null
            else ->
                if (can(from, ObdSessionState.DISCONNECTING)) {
                    listOf(ObdSessionState.DISCONNECTING, ObdSessionState.DISCONNECTED)
                } else {
                    null
                }
        }
    }
}

/** A scan is saved only if this attempt is still the active one and still scanning. */
fun shouldCommitScan(epochAtStart: Long, epochNow: Long, session: ObdSessionState): Boolean =
    epochNow == epochAtStart && session == ObdSessionState.SCANNING
