package car.doctor.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import car.doctor.app.link.AdapterDirectory
import car.doctor.app.link.AdapterPrefs
import car.doctor.app.link.SwitchingAdapterFactory
import car.doctor.core.session.VehicleBench
import car.doctor.data.JsonFileGarageRepository
import car.doctor.simulation.MockObdAdapterFactory
import car.doctor.app.ui.DoctorApp
import car.doctor.app.ui.DoctorTheme
import java.io.File

/**
 * Composition root. The bench gets one adapter factory that switches between the
 * simulator (MockObdAdapterFactory) and a real ELM327 link the user picked.
 * Screens talk to [DoctorViewModel], which talks to [VehicleBench].
 */
class MainActivity : ComponentActivity() {
    private var pendingResult: ((Boolean) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val callback = pendingResult
        pendingResult = null
        callback?.invoke(results.isNotEmpty() && results.values.all { it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vm = ViewModelProvider(this, factory())[DoctorViewModel::class.java]
        vm.permissionAsker = PermissionAsker { permissions, result ->
            pendingResult = result
            permissionLauncher.launch(permissions.toTypedArray())
        }
        setContent {
            DoctorTheme {
                DoctorApp(vm)
            }
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) pendingResult = null
        super.onDestroy()
    }

    private fun factory(): ViewModelProvider.Factory = viewModelFactory {
        initializer {
            val repository = JsonFileGarageRepository(File(filesDir, "garage.json"))
            val links = SwitchingAdapterFactory(applicationContext, MockObdAdapterFactory())
            val bench = VehicleBench(
                repository = repository,
                adapters = links,
            )
            val storageNotice = if (repository.recoveredFromCorrupt) {
                "קובץ השמירה היה פגום. המוסך אופס, ולא הוצגה סריקה תקינה."
            } else {
                null
            }
            DoctorViewModel(
                bench = bench,
                links = links,
                directory = AdapterDirectory(applicationContext),
                prefs = AdapterPrefs(applicationContext),
                storageNoticeHe = storageNotice,
            )
        }
    }
}
