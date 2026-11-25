package car.doctor.simulation

import car.doctor.core.model.FaultId
import car.doctor.core.model.Fuel
import car.doctor.core.model.IgnitionState
import car.doctor.core.model.NewVehicle
import car.doctor.core.persistence.SnapshotGarageRepository
import car.doctor.core.present.SessionPresenter
import car.doctor.core.session.BenchEffect
import car.doctor.core.session.VehicleBench
import car.doctor.transport.MockTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepScanSimTest {
    private fun bench(): VehicleBench {
        val b = VehicleBench(SnapshotGarageRepository(), MockObdAdapterFactory(MockTransport()))
        b.addVehicle(NewVehicle("טסט", "טויוטה", "קורולה", 2016, Fuel.GASOLINE, ""))
        b.setIgnition(IgnitionState.ACC)
        b.setIgnition(IgnitionState.ON)
        b.connect()
        return b
    }

    @Test
    fun deepScanWithAFaultHasAFreezeFrameAndAVin() {
        val b = bench()
        b.disconnect()
        b.toggleFault(FaultId.MISFIRE)
        b.connect()
        assertTrue(b.deepScan() is BenchEffect.Accepted)
        val s = b.sessions().first()
        assertEquals("deep", s.kind)
        assertEquals("JT2AE92E000000001", s.vinRead)
        val code = s.freezeFrame!!.code
        assertTrue(s.dtcs.any { it.code == code })
        assertTrue(s.freezeFrame!!.values.isNotEmpty())
        val view = SessionPresenter.present(s)
        assertTrue(view.deepNotesHe.isNotEmpty())
        assertTrue(view.freezeFrame.isNotEmpty())
    }

    @Test
    fun deepScanOfAHealthyCarHasNoFreezeFrame() {
        val b = bench()
        assertTrue(b.deepScan() is BenchEffect.Accepted)
        val s = b.sessions().first()
        assertNull(s.freezeFrame)
        assertTrue(SessionPresenter.present(s).deepNotesHe.any { it.contains("VIN") })
    }
}
