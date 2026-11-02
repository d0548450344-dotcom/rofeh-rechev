package car.doctor.simulation

import car.doctor.protocol.ProtocolError
import car.doctor.protocol.ProtocolFailure
import car.doctor.protocol.ProtocolOutcome
import car.doctor.protocol.ProtocolRequest
import car.doctor.protocol.ProtocolResponse
import kotlin.math.roundToInt

/**
 * Framed manufacturer channel used by the mock ABS/BCM.
 * This is not UDS and not a standard OBD PID. The numbers still move as bytes.
 */
object SimChannel {
    const val SERVICE = 0x21
    private const val POSITIVE = 0x61

    const val WHEEL_FL = 0x01
    const val WHEEL_FR = 0x02
    const val WHEEL_RL = 0x03
    const val WHEEL_RR = 0x04
    const val STEER = 0x05
    const val BRAKE = 0x06
    const val BCM_VOLTAGE = 0x07
    const val SRS_CODES = 0x08

    fun request(id: Int): ProtocolRequest = ProtocolRequest(SERVICE, listOf(id))

    fun tenthsFrame(id: Int, value: Double): ByteArray {
        val tenths = (value * 10.0).roundToInt().coerceIn(0, 65535)
        return ObdFixtures.frame(POSITIVE, listOf(id, (tenths shr 8) and 0xFF, tenths and 0xFF))
    }

    fun brakeFrame(on: Boolean): ByteArray =
        ObdFixtures.frame(POSITIVE, listOf(BRAKE, if (on) 1 else 0))

    fun voltageFrame(id: Int, volts: Double): ByteArray {
        val millivolts = (volts * 1000.0).roundToInt().coerceIn(0, 65535)
        return ObdFixtures.frame(POSITIVE, listOf(id, (millivolts shr 8) and 0xFF, millivolts and 0xFF))
    }

    fun decode(response: ProtocolResponse): ProtocolOutcome<SimSample> {
        if (response.service == 0x7F) return fail(ProtocolError.UnexpectedResponse("negative"))
        if (response.service != POSITIVE) return fail(ProtocolError.UnexpectedResponse("service"))
        if (response.payload.isEmpty()) return fail(ProtocolError.MissingPayload("sim"))
        val id = response.payload[0]
        val data = response.payload.drop(1)
        val value = when (id) {
            BRAKE -> {
                if (data.isEmpty()) return fail(ProtocolError.MissingPayload("brake"))
                data[0].toDouble()
            }
            BCM_VOLTAGE -> {
                if (data.size < 2) return fail(ProtocolError.MissingPayload("voltage"))
                ((data[0] shl 8) + data[1]) / 1000.0
            }
            WHEEL_FL, WHEEL_FR, WHEEL_RL, WHEEL_RR, STEER -> {
                if (data.size < 2) return fail(ProtocolError.MissingPayload("tenths"))
                ((data[0] shl 8) + data[1]) / 10.0
            }
            SRS_CODES -> {
                if (data.isEmpty()) return fail(ProtocolError.MissingPayload("srs"))
                data[0].toDouble()
            }
            else -> return fail(ProtocolError.Undecodable("sim"))
        }
        return ProtocolOutcome.Ok(SimSample(id = id, raw = data, value = value))
    }

    private fun fail(error: ProtocolError): ProtocolOutcome<SimSample> =
        ProtocolOutcome.Failed(ProtocolFailure.FromProtocol(error))
}

data class SimSample(
    val id: Int,
    val raw: List<Int>,
    val value: Double,
)
