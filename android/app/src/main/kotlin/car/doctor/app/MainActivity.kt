package car.doctor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import car.doctor.core.session.VehicleBench
import car.doctor.data.JsonFileGarageRepository
import car.doctor.simulation.MockObdAdapterFactory
import car.doctor.app.ui.DoctorApp
import car.doctor.app.ui.DoctorTheme
import java.io.File

/**
 * Composition root. This is the only place that chooses the mock adapter.
 * Screens talk to [DoctorViewModel], which talks to [VehicleBench].
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = ViewModelProvider(this, factory())[DoctorViewModel::class.java]
        setContent {
            DoctorTheme {
                DoctorApp(vm)
            }
        }
    }

    private fun factory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val repository = JsonFileGarageRepository(File(filesDir, "garage.json"))
            val bench = VehicleBench(
                repository = repository,
                adapters = MockObdAdapterFactory(),
            )
            val storageNotice = if (repository.recoveredFromCorrupt) {
                "קובץ השמירה היה פגום. המוסך אופס, ולא הוצגה סריקה תקינה."
            } else {
                null
            }
            DoctorViewModel(bench, storageNotice)
        }
    }
}
