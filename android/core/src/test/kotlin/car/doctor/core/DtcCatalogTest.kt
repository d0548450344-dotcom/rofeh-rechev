package car.doctor.core

import car.doctor.core.catalog.describeDtc
import car.doctor.core.catalog.isKnownDtc
import car.doctor.core.model.Severity
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcCatalogTest {
    @Test
    fun everyCodeInTheSharedSourceIsKnown() {
        val source = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "shared/dtc/generic-dtc-he.json") }
            .firstOrNull { it.isFile }
            ?: return // running outside the repository checkout
        val codes = Regex("\"([PCBU][0-3][0-9A-F]{3})\": \\{").findAll(source.readText()).map { it.groupValues[1] }.toList()
        assertTrue(codes.size >= 300)
        for (code in codes) assertTrue(code, isKnownDtc(code))
    }

    @Test
    fun originalDescriptionsAreUnchanged() {
        assertEquals("החטאות בעירה ביותר מצילינדר אחד, או בלי שיוך לצילינדר.", describeDtc("P0300").descriptionHe)
        assertEquals(Severity.HIGH, describeDtc("P0300").severity)
        assertEquals("החטאת בעירה ששויכה לצילינדר 1.", describeDtc("P0301").descriptionHe)
        assertEquals(Severity.CRITICAL, describeDtc("B0001").severity)
    }

    @Test
    fun commonCodesAreDescribed() {
        assertTrue(describeDtc("P0304").descriptionHe.contains("צילינדר 4"))
        assertTrue(describeDtc("U0100").descriptionHe.contains("אבד הקשר"))
        assertEquals(describeDtc("P0420"), describeDtc("p0420"))
    }

    @Test
    fun unknownCodesAreDescribedByStructureOnly() {
        assertTrue(describeDtc("P1234").descriptionHe.contains("קוד יצרן"))
        assertTrue(describeDtc("P1234").descriptionHe.contains("לא אומתה"))
        assertTrue(describeDtc("P0399").descriptionHe.contains("הצתה"))
        assertTrue(describeDtc("C1A00").descriptionHe.contains("קוד יצרן של השלדה"))
        assertTrue(describeDtc("U3000").descriptionHe.contains("טווח שמור"))
        assertTrue(describeDtc("XYZ").descriptionHe.contains("אין תיאור מאומת"))
        assertEquals(Severity.MEDIUM, describeDtc("P1234").severity)
        assertFalse(isKnownDtc("P1234"))
    }
}
