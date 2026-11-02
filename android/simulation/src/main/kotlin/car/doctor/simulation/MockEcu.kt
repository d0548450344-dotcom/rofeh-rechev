package car.doctor.simulation

import car.doctor.core.model.FaultId
import car.doctor.core.model.Fuel
import car.doctor.core.model.IgnitionState
import car.doctor.core.model.Posture
import car.doctor.core.model.Vehicle
import car.doctor.protocol.FrameCodec
import car.doctor.protocol.FrameParse
import car.doctor.protocol.ObdPid
import kotlin.math.max
import kotlin.math.round
import kotlin.math.sin

/**
 * Scripted controller behind the byte pipe.
 * It answers frames. It does not build findings or core samples.
 */
object MockEcu {
    fun reply(vehicle: Vehicle, request: ByteArray): ByteArray? {
        val raw = request.map { it.toInt() and 0xFF }
        val parsed = FrameCodec.parse(raw) as? FrameParse.Ok ?: return null
        val frame = parsed.frame
        val scene = scene(vehicle)
        val head = frame.payload.firstOrNull()
        return when (frame.service) {
            0x01 -> currentData(scene, head)
            0x03 -> ObdFixtures.stored(scene.stored)
            0x07 -> ObdFixtures.pending(scene.pending)
            SimChannel.SERVICE -> channel(scene, head)
            else -> ObdFixtures.negative(frame.service)
        }
    }

    private fun currentData(scene: Scene, pid: Int?): ByteArray {
        if (pid == null) return ObdFixtures.negative(0x01)
        val unsupported = !scene.ice && pid in setOf(
            ObdPid.ENGINE_RPM.code,
            ObdPid.COOLANT_TEMP.code,
            ObdPid.THROTTLE_POSITION.code,
        )
        if (unsupported) return ObdFixtures.negative(0x01)
        if (scene.intermittent && pid == ObdPid.COOLANT_TEMP.code) return ObdFixtures.negative(0x01)
        return when (pid) {
            ObdPid.ENGINE_RPM.code -> ObdFixtures.rpm(scene.rpm)
            ObdPid.VEHICLE_SPEED.code -> ObdFixtures.speed(scene.speed)
            ObdPid.COOLANT_TEMP.code -> ObdFixtures.coolant(scene.coolant)
            ObdPid.THROTTLE_POSITION.code -> ObdFixtures.throttlePercent(scene.throttle)
            ObdPid.CONTROL_MODULE_VOLTAGE.code -> ObdFixtures.voltage(scene.voltage)
            ObdPid.MONITOR_STATUS.code -> ObdFixtures.readiness(
                storedCount = scene.stored.size,
                misfireComplete = FaultId.MISFIRE !in scene.faults,
                ice = scene.ice,
            )
            else -> ObdFixtures.negative(0x01)
        }
    }

    private fun channel(scene: Scene, id: Int?): ByteArray {
        if (id == null) return ObdFixtures.negative(SimChannel.SERVICE)
        return when (id) {
            SimChannel.WHEEL_FL -> SimChannel.tenthsFrame(id, scene.fl)
            SimChannel.WHEEL_FR -> SimChannel.tenthsFrame(id, scene.fr)
            SimChannel.WHEEL_RL -> SimChannel.tenthsFrame(id, scene.rl)
            SimChannel.WHEEL_RR -> SimChannel.tenthsFrame(id, scene.rr)
            SimChannel.STEER -> SimChannel.tenthsFrame(id, scene.steer)
            SimChannel.BRAKE -> SimChannel.brakeFrame(scene.brake)
            SimChannel.BCM_VOLTAGE -> SimChannel.voltageFrame(id, scene.voltage)
            SimChannel.SRS_CODES -> ObdFixtures.frame(0x61, listOf(id, scene.stored.count { it.startsWith("B") }))
            else -> ObdFixtures.negative(SimChannel.SERVICE)
        }
    }

    private fun scene(vehicle: Vehicle): Scene {
        val faults = vehicle.sim.faults
        val ice = vehicle.fuel != Fuel.ELECTRIC
        val dyn = dynamics(vehicle.sim.posture, vehicle.sim.ignition, ice)
        val voltage = if (FaultId.LOW_BATTERY in faults) 11.2 else dyn.voltage
        val wheel = wheels(
            speed = dyn.speed,
            steerDeg = dyn.steer,
            brake = dyn.brake,
            mismatch = FaultId.WHEEL_SPEED_MISMATCH in faults,
        )
        val stored = mutableListOf<String>()
        val pending = mutableListOf<String>()
        if (FaultId.MISFIRE in faults) {
            stored += "P0301"
            pending += "P0300"
        }
        if (FaultId.LOW_BATTERY in faults) pending += "P0562"
        return Scene(
            ice = ice,
            faults = faults,
            intermittent = ice && FaultId.INTERMITTENT_SENSOR in faults,
            rpm = dyn.rpm,
            speed = dyn.speed,
            coolant = dyn.coolant,
            throttle = dyn.throttle,
            voltage = voltage,
            steer = dyn.steer,
            brake = dyn.brake,
            fl = wheel.fl,
            fr = wheel.fr,
            rl = wheel.rl,
            rr = wheel.rr,
            stored = stored,
            pending = pending,
        )
    }
}

private data class Scene(
    val ice: Boolean,
    val faults: List<FaultId>,
    val intermittent: Boolean,
    val rpm: Double,
    val speed: Double,
    val coolant: Double,
    val throttle: Double,
    val voltage: Double,
    val steer: Double,
    val brake: Boolean,
    val fl: Double,
    val fr: Double,
    val rl: Double,
    val rr: Double,
    val stored: List<String>,
    val pending: List<String>,
)

private data class Dynamics(
    val speed: Double,
    val rpm: Double,
    val steer: Double,
    val brake: Boolean,
    val throttle: Double,
    val coolant: Double,
    val voltage: Double,
)

private data class Wheels(val fl: Double, val fr: Double, val rl: Double, val rr: Double)

private fun dynamics(posture: Posture, ignition: IgnitionState, ice: Boolean): Dynamics {
    if (ignition != IgnitionState.RUNNING) {
        return Dynamics(
            speed = 0.0,
            rpm = if (ignition == IgnitionState.STARTING) 220.0 else 0.0,
            steer = 0.0,
            brake = false,
            throttle = if (ignition == IgnitionState.STARTING) 8.0 else 0.0,
            coolant = 46.0,
            voltage = 12.5,
        )
    }
    val row = when (posture) {
        Posture.IDLE -> Dynamics(0.0, 780.0, 0.0, false, 2.4, 90.0, 14.2)
        Posture.STRAIGHT -> Dynamics(62.0, 2140.0, 0.6, false, 21.0, 93.0, 14.1)
        Posture.TURN -> Dynamics(34.0, 1760.0, 22.0, false, 16.0, 92.0, 14.1)
        Posture.BRAKE -> Dynamics(48.0, 1380.0, 1.2, true, 0.0, 94.0, 14.0)
    }
    if (!ice) return row.copy(rpm = 0.0, coolant = 46.0, throttle = 0.0)
    return row
}

private fun wheels(speed: Double, steerDeg: Double, brake: Boolean, mismatch: Boolean): Wheels {
    val rad = steerDeg * Math.PI / 180.0
    val delta = speed * sin(rad) * 0.55
    val left = delta * 0.5
    val right = -delta * 0.5
    var fl = speed + left
    var fr = speed + right
    var rl = speed + left * 0.7
    var rr = speed + right * 0.7
    if (brake) {
        fl -= 1.1
        fr -= 0.6
        rl -= 1.4
        rr -= 0.8
    }
    if (mismatch) fl -= max(7.0, speed * 0.11)
    fun clip(value: Double) = round1(max(0.0, value))
    return Wheels(clip(fl), clip(fr), clip(rl), clip(rr))
}

private fun round1(value: Double): Double = round(value * 10.0) / 10.0
