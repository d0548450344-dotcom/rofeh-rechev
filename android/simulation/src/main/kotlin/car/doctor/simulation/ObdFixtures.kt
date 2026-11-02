package car.doctor.simulation

import car.doctor.protocol.FrameCodec
import kotlin.math.roundToInt

/**
 * OBD-II answers as local frames.
 * Callers decode these bytes. They are not pre-decoded samples.
 */
object ObdFixtures {
    private const val POSITIVE = 0x40

    fun rpm(value: Double): ByteArray = mode01(0x0C, rpmBytes(value))

    fun speed(kmh: Double): ByteArray = mode01(0x0D, listOf(kmh.roundToInt().coerceIn(0, 255)))

    fun coolant(celsius: Double): ByteArray =
        mode01(0x05, listOf((celsius + 40.0).roundToInt().coerceIn(0, 255)))

    fun throttleRaw(byteA: Int): ByteArray = mode01(0x11, listOf(byteA and 0xFF))

    fun throttlePercent(percent: Double): ByteArray {
        val scaled = (percent * 255.0 / 100.0).roundToInt().coerceIn(0, 255)
        return throttleRaw(scaled)
    }

    fun voltage(volts: Double): ByteArray {
        val millivolts = (volts * 1000.0).roundToInt().coerceIn(0, 65535)
        return mode01(0x42, listOf((millivolts shr 8) and 0xFF, millivolts and 0xFF))
    }

    fun readiness(storedCount: Int, misfireComplete: Boolean, ice: Boolean): ByteArray {
        val a = storedCount and 0x7F
        val b = if (ice) 0x07 else 0x00
        val d = if (ice && !misfireComplete) 0x01 else 0x00
        return mode01(0x01, listOf(a, b, 0x00, d))
    }

    fun stored(codes: List<String>): ByteArray = codesFrame(0x03, codes)

    fun pending(codes: List<String>): ByteArray = codesFrame(0x07, codes)

    fun negative(service: Int, code: Int = 0x12): ByteArray =
        frame(0x7F, listOf(service and 0xFF, code and 0xFF))

    fun malformed(): ByteArray = byteArrayOf(0x11, 0x01, 0x01, 0x00)

    fun rpmBytes(value: Double): List<Int> {
        val scaled = (value * 4.0).roundToInt().coerceIn(0, 65535)
        return listOf((scaled shr 8) and 0xFF, scaled and 0xFF)
    }

    fun codeBytes(code: String): List<Int> {
        require(code.length == 5) { code }
        val type = when (code[0]) {
            'P' -> 0
            'C' -> 1
            'B' -> 2
            'U' -> 3
            else -> throw IllegalArgumentException(code)
        }
        val d1 = code[1].digitToInt()
        val d2 = code[2].digitToInt(16)
        val d3 = code[3].digitToInt(16)
        val d4 = code[4].digitToInt(16)
        return listOf((type shl 6) or (d1 shl 4) or d2, (d3 shl 4) or d4)
    }

    fun frame(service: Int, payload: List<Int>): ByteArray {
        val raw = FrameCodec.encode(service, payload)
        return ByteArray(raw.size) { raw[it].toByte() }
    }

    private fun mode01(pid: Int, data: List<Int>): ByteArray = frame(0x01 + POSITIVE, listOf(pid) + data)

    private fun codesFrame(service: Int, codes: List<String>): ByteArray =
        frame(service + POSITIVE, codes.flatMap { codeBytes(it) })
}
