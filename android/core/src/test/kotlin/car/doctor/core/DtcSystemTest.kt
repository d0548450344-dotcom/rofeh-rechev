package car.doctor.core

import car.doctor.core.catalog.DtcSystem
import car.doctor.core.catalog.dtcSystem
import car.doctor.core.model.EcuId
import org.junit.Assert.assertEquals
import org.junit.Test

class DtcSystemTest {
    @Test
    fun powertrainCodesFallIntoTheirStandardFamilies() {
        assertEquals(DtcSystem.SENSORS, dtcSystem("P0101", EcuId.ENGINE)) // air flow
        assertEquals(DtcSystem.SENSORS, dtcSystem("P0118", EcuId.ENGINE)) // coolant temperature
        assertEquals(DtcSystem.SENSORS, dtcSystem("P0171", EcuId.ENGINE)) // fuel trim
        assertEquals(DtcSystem.ENGINE, dtcSystem("P0301", EcuId.ENGINE)) // misfire
        assertEquals(DtcSystem.ENGINE, dtcSystem("P0420", EcuId.ENGINE)) // catalyst
        assertEquals(DtcSystem.ENGINE, dtcSystem("P0505", EcuId.ENGINE)) // idle control
        assertEquals(DtcSystem.ELECTRICAL, dtcSystem("P0562", EcuId.ENGINE)) // system voltage low
        assertEquals(DtcSystem.ELECTRICAL, dtcSystem("P0606", EcuId.ENGINE)) // control module
        assertEquals(DtcSystem.TRANSMISSION, dtcSystem("P0700", EcuId.ENGINE))
        assertEquals(DtcSystem.TRANSMISSION, dtcSystem("P0841", EcuId.ENGINE))
        assertEquals(DtcSystem.TRANSMISSION, dtcSystem("P0900", EcuId.ENGINE))
        assertEquals(DtcSystem.ENGINE, dtcSystem("P1234", EcuId.ENGINE)) // maker-specific
    }

    @Test
    fun networkAndBodyCodesAreElectrical() {
        assertEquals(DtcSystem.ELECTRICAL, dtcSystem("U0100", EcuId.ENGINE))
        assertEquals(DtcSystem.ELECTRICAL, dtcSystem("B1000", EcuId.ENGINE))
        assertEquals(DtcSystem.ELECTRICAL, dtcSystem("B1318", EcuId.BCM))
    }

    @Test
    fun theReportingUnitWinsForAirbagsAndAbs() {
        assertEquals(DtcSystem.AIRBAGS, dtcSystem("B0001", EcuId.SRS))
        assertEquals(DtcSystem.ABS, dtcSystem("C0035", EcuId.ABS))
        assertEquals(DtcSystem.ABS, dtcSystem("U0121", EcuId.ABS))
    }

    @Test
    fun oddInputDoesNotCrash() {
        assertEquals(DtcSystem.ENGINE, dtcSystem("", EcuId.ENGINE))
        assertEquals(DtcSystem.SENSORS, dtcSystem(" p0101 ", EcuId.ENGINE))
    }
}
