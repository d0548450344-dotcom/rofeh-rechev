package car.doctor.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's line icons, drawn on a 24 grid with one stroke weight, so they read as one set.
 * Kept in code to avoid an extra icon library. Only the chevron mirrors in RTL;
 * a clock or a gauge must not flip.
 */
internal object DoctorIcons {
    val Home = line(
        "home",
        "M4 10.2 12 4l8 6.2V19a1 1 0 0 1-1 1h-4.5v-5.5h-5V20H5a1 1 0 0 1-1-1z",
    )

    /** Scan bench: a gauge. */
    val Gauge = line(
        "gauge",
        "M4.6 17.5a8.5 8.5 0 1 1 14.8 0",
        "M12 14.5l3.8-4.3",
        "M12.9 14.5a0.9 0.9 0 1 1-1.8 0a0.9 0.9 0 1 1 1.8 0z",
        "M12 6.4v1.4",
        "M6.9 8.6l1 1",
        "M17.1 8.6l-1 1",
    )

    /** Scan result: a clipboard with a check. */
    val Report = line(
        "report",
        "M9 3.8h6v3H9z",
        "M15 5.3h2.5A1.5 1.5 0 0 1 19 6.8v12.7a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19.5V6.8a1.5 1.5 0 0 1 1.5-1.5H9",
        "M9 13.5l2 2 4-4",
    )

    /** History: a clock. */
    val History = line(
        "history",
        "M12 4a8 8 0 1 1 0 16a8 8 0 1 1 0-16z",
        "M12 8v4.5l3 2",
    )

    /** Settings: two sliders. */
    val Settings = line(
        "settings",
        "M4 7h8",
        "M16 7h4",
        "M14 5a2 2 0 1 1 0 4a2 2 0 1 1 0-4z",
        "M4 17h4",
        "M12 17h8",
        "M10 15a2 2 0 1 1 0 4a2 2 0 1 1 0-4z",
    )

    val Check = line("check", "M5 12.5l4.5 4.5L19 7.5", width = 2.2f)

    /** Points right in LTR; mirrored to point left (forward) in RTL. */
    val Chevron = line("chevron", "M9.5 6l6 6-6 6", autoMirror = true, width = 2f)

    private fun line(
        name: String,
        vararg paths: String,
        autoMirror: Boolean = false,
        width: Float = 1.8f,
    ): ImageVector {
        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = autoMirror,
        )
        for (d in paths) {
            builder.addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}
