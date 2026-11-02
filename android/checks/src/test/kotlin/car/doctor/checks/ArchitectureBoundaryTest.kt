package car.doctor.checks

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchitectureBoundaryTest {
    @Test
    fun domainDataAndSimulationDoNotImportAndroid() {
        for (module in listOf("core", "simulation", "data", "transport", "protocol")) {
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

    @Test
    fun noBluetoothApiIsReferenced() {
        val files = mutableListOf<File>()
        for (module in listOf("core", "transport", "protocol", "simulation", "data", "app", "checks")) {
            val src = moduleRoot().resolve("$module/src/main")
            if (src.isDirectory) files += src.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }
        }
        assertTrue(files.isNotEmpty())
        for (file in files) {
            val text = file.readText()
            assertFalse(file.path, text.contains("BluetoothAdapter"))
            assertFalse(file.path, text.contains("android.bluetooth"))
            assertFalse(file.path, text.contains("android.permission.BLUETOOTH"))
        }
    }

    @Test
    fun composeDoesNotDecideTheDiagnosis() {
        val app = sources("app")
        assertTrue(app.isNotEmpty())
        val allowedAdapter = setOf("MainActivity.kt")
        for (file in app) {
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

private fun moduleRoot(): File {
    val start = File("").absoluteFile
    val found = generateSequence(start) { it.parentFile }.firstOrNull { candidate ->
        File(candidate, "settings.gradle.kts").isFile && File(candidate, "core").isDirectory
    }
    return found ?: start
}
