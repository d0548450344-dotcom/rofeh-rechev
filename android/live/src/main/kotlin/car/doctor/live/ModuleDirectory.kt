package car.doctor.live

import car.doctor.core.catalog.describeDtc
import car.doctor.core.model.ModuleFault
import car.doctor.core.model.ModuleKind
import car.doctor.core.model.ModuleReport
import car.doctor.protocol.Elm327Protocol
import car.doctor.protocol.ModuleAddress
import car.doctor.protocol.ModuleCode
import car.doctor.protocol.ProtocolError
import car.doctor.protocol.ProtocolFailure
import car.doctor.protocol.ProtocolOutcome
import car.doctor.protocol.ProtocolResponse
import car.doctor.protocol.Uds

/**
 * Where the ABS and airbag modules answer on 11-bit CAN, per make family. These are the
 * commonly published diagnostic IDs; any given model may differ, so a module that does not
 * answer is reported as "not reached", never as "no faults".
 * Only makes listed here are probed: the same ID can be a different module on another make.
 */
object ModuleDirectory {
    private data class Family(val names: List<String>, val modules: List<Pair<ModuleKind, ModuleAddress>>)

    private val FAMILIES = listOf(
        Family(
            listOf("toyota", "טויוטה", "lexus", "לקסוס"),
            listOf(ModuleKind.ABS to ModuleAddress(0x7B0, 0x7B8), ModuleKind.SRS to ModuleAddress(0x780, 0x788)),
        ),
        Family(
            listOf("hyundai", "יונדאי", "kia", "קיה", "genesis", "ג׳נסיס", "ג'נסיס"),
            listOf(ModuleKind.ABS to ModuleAddress(0x7D1, 0x7D9), ModuleKind.SRS to ModuleAddress(0x7D2, 0x7DA)),
        ),
        Family(
            listOf("volkswagen", "vw", "פולקסווגן", "skoda", "סקודה", "seat", "סיאט", "cupra", "קופרה", "audi", "אאודי", "אודי"),
            listOf(ModuleKind.ABS to ModuleAddress(0x713, 0x77D), ModuleKind.SRS to ModuleAddress(0x715, 0x77F)),
        ),
        Family(
            listOf("ford", "פורד", "mazda", "מאזדה", "מזדה"),
            listOf(ModuleKind.ABS to ModuleAddress(0x760, 0x768), ModuleKind.SRS to ModuleAddress(0x737, 0x73F)),
        ),
    )

    fun forMake(make: String): List<Pair<ModuleKind, ModuleAddress>>? {
        val m = make.trim().lowercase()
        if (m.isEmpty()) return null
        return FAMILIES.firstOrNull { f -> f.names.any { m.contains(it) } }?.modules
    }

    val supportedMakesHe: String = "טויוטה/לקסוס, יונדאי/קיה, קבוצת פולקסווגן (סקודה, סיאט, אאודי), פורד/מאזדה"
}

/** Reads every known module of the car's make. Null when the link itself failed. */
internal fun readModules(protocol: Elm327Protocol, make: String): List<ModuleReport>? {
    val modules = ModuleDirectory.forMake(make) ?: return emptyList()
    val out = mutableListOf<ModuleReport>()
    for ((kind, address) in modules) {
        val label = "%03X → %03X".format(address.request, address.response)
        when (val read = readModule(protocol, address)) {
            is ModuleRead.Codes -> out += ModuleReport(kind, label, reached = true, faults = read.codes.map(::fault))
            is ModuleRead.NotReached -> out += ModuleReport(kind, label, reached = false, noteHe = read.noteHe)
            ModuleRead.NotCan -> return listOf(
                ModuleReport(kind, label, reached = false, noteHe = "החיבור ברכב הזה אינו CAN, ולכן אין גישה למודולים האלה דרך המתאם."),
            )
            ModuleRead.LinkLost -> return null
        }
    }
    return out
}

private sealed class ModuleRead {
    class Codes(val codes: List<ModuleCode>) : ModuleRead()
    class NotReached(val noteHe: String) : ModuleRead()
    object NotCan : ModuleRead()
    object LinkLost : ModuleRead()
}

private fun readModule(protocol: Elm327Protocol, address: ModuleAddress): ModuleRead {
    val uds = protocol.requestModule(address, Uds.readDtcs())
    val response = when (uds) {
        is ProtocolOutcome.Failed -> return failed(uds.failure)
        is ProtocolOutcome.Ok -> uds.value
    }
    Uds.negativeCode(response)?.let { nrc ->
        if (nrc != 0x11 && nrc != 0x12) return ModuleRead.NotReached("המודול סירב לבקשה (קוד %02X).".format(nrc))
        // Not UDS: older modules speak KWP2000 on CAN.
        val kwp = protocol.requestModule(address, Uds.readDtcsKwp())
        val answer = when (kwp) {
            is ProtocolOutcome.Failed -> return failed(kwp.failure)
            is ProtocolOutcome.Ok -> kwp.value
        }
        if (Uds.negativeCode(answer) != null) return ModuleRead.NotReached("המודול לא תומך בקריאת קודים מסוג שהאפליקציה מכירה.")
        return decoded(Uds.decodeDtcsKwp(answer))
    }
    return decoded(Uds.decodeDtcs(response))
}

private fun decoded(outcome: ProtocolOutcome<List<ModuleCode>>): ModuleRead = when (outcome) {
    is ProtocolOutcome.Ok -> ModuleRead.Codes(outcome.value)
    is ProtocolOutcome.Failed -> ModuleRead.NotReached("התשובה מהמודול לא הייתה בפורמט מוכר.")
}

private fun failed(failure: ProtocolFailure): ModuleRead {
    val error = (failure as? ProtocolFailure.FromProtocol)?.error ?: return ModuleRead.LinkLost
    return when {
        error is ProtocolError.Unsupported && error.detail == "not-can-11bit" -> ModuleRead.NotCan
        error is ProtocolError.Unsupported -> ModuleRead.NotReached("המתאם לא מאפשר לפנות למודול ישירות (פקודת ATSH).")
        else -> ModuleRead.NotReached("לא התקבלה תשובה בכתובת הזו. ייתכן שבדגם הזה המודול בכתובת אחרת.")
    }
}

private fun fault(code: ModuleCode): ModuleFault = ModuleFault(
    code = code.code,
    failureType = code.failureType,
    confirmed = code.confirmed,
    pending = code.pending,
    failingNow = code.failingNow,
    descriptionHe = describeDtc(code.code).descriptionHe,
)
