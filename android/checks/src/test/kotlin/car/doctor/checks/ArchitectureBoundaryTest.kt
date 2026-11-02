package car.doctor.checks

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureBoundaryTest {
    @Test
    fun domainDataAndSimulationDoNotImportAndroid() {
        for (module in listOf("core", "simulation", "live", "data", "transport", "protocol")) {
            val sources = sources(module)
            assertTrue("$module has sources", sources.isNotEmpty())
            for (file in sources) {
                val text = file.readText()
                assertFalse(file.path, text.contains("android.") || text.contains("androidx."))
                assertFalse(file.path, text.contains("android.bluetooth"))
                assertFalse(file.path, text.contains("BluetoothAdapter"))
            }
        }
    }

    @Test
    fun diagnosticCoreDoesNotKnowTheTransportOrProtocol() {
        for (file in sources("core")) {
            val text = file.readText()
            assertFalse(file.path, text.contains("car.doctor.transport"))
            assertFalse(file.path, text.contains("car.doctor.protocol"))
            assertFalse(file.path, text.contains("MockTransport"))
            assertFalse(file.path, text.contains("MockProtocol"))
        }
    }

    @Test
    fun transportMovesBytesOnly() {
        val sources = sources("transport")
        assertTrue(sources.isNotEmpty())
        for (file in sources) {
            val text = file.readText()
            assertFalse(file.path, text.contains("car.doctor.core"))
            assertFalse(file.path, text.contains("car.doctor.protocol"))
            assertFalse(file.path, text.contains("react"))
            assertFalse(file.path, text.contains("localStorage"))
            for (token in listOf("Dtc", "PidValue", "DiagnosticRules", "Finding")) {
                assertFalse(file.path + " " + token, text.contains(token))
            }
        }
    }

    /**
     * Phase 2: real adapters. Bluetooth APIs and permissions are allowed only in the
     * app's link package (and the manifest). Every other module stays Android-free.
     */
    @Test
    fun bluetoothStaysInTheAppLinkLayer() {
        val files = mutableListOf<File>()
        for (module in listOf("core", "transport", "protocol", "simulation", "live", "data", "checks")) {
            val src = moduleRoot().resolve("$module/src/main")
            if (src.isDirectory) files += src.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }
        }
        files += sources("app").filterNot { it.isLinkLayer() }
        assertTrue(files.isNotEmpty())
        for (file in files) {
            val text = file.readText()
            assertFalse(file.path, text.contains("BluetoothAdapter"))
            assertFalse(file.path, text.contains("android.bluetooth"))
            assertFalse(file.path, text.contains("android.permission.BLUETOOTH"))
        }
        assertTrue(sources("app").any { it.isLinkLayer() && it.readText().contains("android.bluetooth") })
    }

    /**
     * The only write any module may send is OBD mode 04 (clear codes), from exactly one
     * place, behind a consent object. Flashing, coding and security access do not exist.
     */
    @Test
    fun onlyClearCodesCanWriteAndNothingCanFlash() {
        val protocol = sources("protocol")
        val elm = protocol.single { it.name == "Elm327Protocol.kt" }.readText()
        assertTrue(elm.contains("fun clearCodes(consent: ClearCodesConsent)"))
        assertEquals(1, Regex("exchange\\(\"04\"").findAll(elm).count())
        assertFalse(elm.substringAfter("READ_ONLY_SERVICES: Set<Int> = setOf(").substringBefore(")").contains("0x04"))

        val obd2 = protocol.single { it.name == "Obd2.kt" }.readText()
        assertTrue(obd2.contains("val WRITE_AND_FLASH_SERVICES"))
        assertTrue(obd2.contains("consent: ClearCodesConsent?"))

        // No module builds a request for a write or flash service.
        val all = listOf("core", "transport", "protocol", "simulation", "live", "data", "app").flatMap(::sources)
        val flashRequest = Regex("ProtocolRequest\\(\\s*0x(10|11|14|27|28|2E|2F|31|34|35|36|37|3B|3D|85)\\b", RegexOption.IGNORE_CASE)
        for (file in all) {
            assertFalse(file.path, flashRequest.containsMatchIn(file.readText()))
        }
    }

    @Test
    fun composeDoesNotDecideTheDiagnosis() {
        val app = sources("app")
        assertTrue(app.isNotEmpty())
        val allowedAdapter = setOf("MainActivity.kt")
        for (file in app) {
            // The link package wires real transports to the bench. It never decodes or diagnoses.
            if (file.isLinkLayer()) {
                val text = file.readText()
                assertFalse(file.name, text.contains("DiagnosticRules"))
                assertFalse(file.name, text.contains("decodePid"))
                assertFalse(file.name, text.contains("readDtcs"))
                continue
            }
            val text = file.readText()
            assertFalse(file.name, text.contains("DiagnosticRules"))
            assertFalse(file.name, text.contains("car.doctor.core.rules"))
            assertFalse(file.name, text.contains("readDtcs"))
            assertFalse(file.name, text.contains("readFacts"))
            assertFalse(file.name, text.contains("FrameCodec"))
            assertFalse(file.name, text.contains("MockTransport"))
            assertFalse(file.name, text.contains("decodePid"))
            assertFalse(file.name, text.contains("car.doctor.protocol"))
            assertFalse(file.name, text.contains("car.doctor.transport"))
            if (file.name !in allowedAdapter) {
                assertFalse(file.name, text.contains("MockObdAdapter"))
                assertFalse(file.name, text.contains("car.doctor.simulation"))
                assertFalse(file.name, text.contains("ObdAdapter"))
            }
        }
        val activity = app.single { it.name == "MainActivity.kt" }.readText()
        assertTrue(activity.contains("MockObdAdapter"))
        assertTrue(activity.contains("VehicleBench"))
        assertTrue(activity.contains("JsonFileGarageRepository"))
    }

    @Test
    fun protocolDoesNotKnowTheCoreAndMockProtocolDoesNotDecodeObd() {
        val sources = sources("protocol")
        assertTrue(sources.isNotEmpty())
        for (file in sources) {
            val text = file.readText()
            assertFalse(file.path, text.contains("car.doctor.core"))
            assertFalse(file.path, text.contains("car.doctor.simulation"))
            assertFalse(file.path, text.contains("android.bluetooth"))
            assertFalse(file.path, text.contains("BluetoothAdapter"))
        }
        val mock = sources.single { it.name == "MockProtocol.kt" }.readText()
        for (token in listOf("ObdPid", "TroubleCode", "PidReading", "ReadinessReport", "DiagnosticRules")) {
            assertFalse(token, mock.contains(token))
        }
    }

    @Test
    fun adapterDoesNotReadTransportBytes() {
        val adapter = sources("simulation").single { it.name == "MockObdAdapter.kt" }.readText()
        assertFalse(adapter.contains(".send("))
        assertFalse(adapter.contains(".receive("))
        assertFalse(adapter.contains("FrameCodec"))
        assertFalse(adapter.contains("FaultId"))
        assertFalse(adapter.contains("sim.faults"))
        assertFalse(adapter.contains("sim.posture"))
        assertTrue(adapter.contains("protocol.connect()"))
        assertTrue(adapter.contains("protocol.request("))
        assertTrue(adapter.contains("protocol.disconnect()"))
        assertTrue(adapter.contains("Obd2.decodePid"))
        assertTrue(adapter.contains("Obd2.decodeStoredCodes"))
        assertTrue(adapter.contains("Obd2.decodeReadiness"))
    }
}

private fun sources(module: String): List<File> {
    val dir = moduleRoot().resolve("$module/src/main")
    if (!dir.isDirectory) return emptyList()
    return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
}

private fun File.isLinkLayer(): Boolean = invariantSeparatorsPath.contains("/car/doctor/app/link/")

private fun moduleRoot(): File {
    val start = File("").absoluteFile
    val found = generateSequence(start) { it.parentFile }.firstOrNull { candidate ->
        File(candidate, "settings.gradle.kts").isFile && File(candidate, "core").isDirectory
    }
    return found ?: start
}
