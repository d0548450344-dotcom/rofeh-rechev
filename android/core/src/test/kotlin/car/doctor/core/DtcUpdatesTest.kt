package car.doctor.core

import car.doctor.core.catalog.DtcSource
import car.doctor.core.catalog.builtInDtcCount
import car.doctor.core.catalog.describeDtc
import car.doctor.core.catalog.installDtcOverlay
import car.doctor.core.catalog.knownDtcCount
import car.doctor.core.model.Severity
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcUpdatesTest {
    @After
    fun reset() = installDtcOverlay(emptyMap())

    private fun sourceFile(): File? = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "shared/dtc/generic-dtc-he.json") }
        .firstOrNull { it.isFile }

    @Test
    fun theRepositoryFileParses() {
        val text = sourceFile()?.readText() ?: return
        val parsed = DtcSource.parse(text)
        assertNotNull(parsed)
        assertEquals(builtInDtcCount(), parsed!!.size)
    }

    @Test
    fun anUpdateAddsAndReplacesButNeverRemoves() {
        val text = sourceFile()?.readText() ?: return
        val extra = text.replaceFirst(
            "\"codes\": {",
            "\"codes\": {\n  \"P0A80\": {\"he\": \"החלפת סוללת ההנעה ההיברידית מומלצת.\", \"severity\": \"high\"},",
        )
        installDtcOverlay(DtcSource.parse(extra)!!)
        assertEquals("החלפת סוללת ההנעה ההיברידית מומלצת.", describeDtc("P0A80").descriptionHe)
        assertEquals(Severity.HIGH, describeDtc("P0A80").severity)
        assertEquals(builtInDtcCount() + 1, knownDtcCount())
        assertTrue(describeDtc("P0300").descriptionHe.contains("החטאות"))
    }

    @Test
    fun badDownloadsAreRejected() {
        assertNull(DtcSource.parse("not json"))
        assertNull(DtcSource.parse("{}"))
        assertNull(DtcSource.parse("{\"codes\": {\"P0300\": {\"he\": \"קצר\", \"severity\": \"high\"}}}")) // too few codes
        val text = sourceFile()?.readText() ?: return
        assertNull(DtcSource.parse(text.replaceFirst("\"severity\": \"medium\"", "\"severity\": \"panic\"")))
        assertNull(DtcSource.parse(text.replaceFirst("\"P0001\"", "\"X0001\"")))
    }
}
