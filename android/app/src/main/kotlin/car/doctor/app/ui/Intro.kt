package car.doctor.app.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * The launch animation, drawn from the app icon's own geometry (a 108-unit adaptive icon;
 * the visible part is 18..90). First the needle sweeps to the end of the scale and drops back,
 * like a tachometer waking up at ignition; then the ECG trace is written in from the side.
 * About 1.6 s. A tap skips it, and it is skipped when the system turns animations off.
 */

private const val ICON_MIN = 18f
private const val ICON_SPAN = 72f
private val DIAL_CENTER = Offset(54f, 58f)
private const val DIAL_RADIUS = 27f

/** The ECG trace in icon units: baseline, P wave, QRS spike, T wave, baseline. */
private val ECG = listOf(
    Offset(27f, 64f), Offset(38.5f, 64f), Offset(41.5f, 60.5f), Offset(44.5f, 64f), Offset(47.5f, 64f),
    Offset(51f, 49f), Offset(55.5f, 75f), Offset(59.5f, 58.5f), Offset(62f, 64f), Offset(81f, 64f),
)

private val IconCopper = Color(0xFFE67A2E)
private val IconInk = Color(0xFFECEEF1)

@Composable
fun LaunchIntro(onDone: () -> Unit) {
    val context = LocalContext.current
    val done by rememberUpdatedState(onDone)
    val motionOff = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val needle = remember { Animatable(0f) }
    val needleAlpha = remember { Animatable(1f) }
    val trace = remember { Animatable(0f) }
    val veil = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        if (motionOff) {
            done()
            return@LaunchedEffect
        }
        delay(120)
        needle.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
        needle.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
        launch { needleAlpha.animateTo(0f, tween(250)) }
        trace.animateTo(1f, tween(480, easing = LinearEasing))
        delay(220)
        veil.animateTo(0f, tween(260))
        done()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(veil.value)
            .background(Brush.verticalGradient(0f to Tone.BaseTop, 0.4f to Tone.Base, 1f to Color(0xFF0A0A0C)))
            .clearAndSetSemantics { }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { done() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            Modifier
                .size(132.dp)
                .clip(RoundedCornerShape(36.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF2A2D32), Color(0xFF17191C), Color(0xFF0E0F11)))),
        ) {
            val k = size.width / ICON_SPAN
            fun at(p: Offset) = Offset((p.x - ICON_MIN) * k, (p.y - ICON_MIN) * k)
            fun polar(deg: Float, r: Float): Offset {
                val rad = deg * PI.toFloat() / 180f
                return at(Offset(DIAL_CENTER.x + r * cos(rad), DIAL_CENTER.y + r * sin(rad)))
            }

            // Rim: the top half of the dial.
            val c = at(DIAL_CENTER)
            val r = DIAL_RADIUS * k
            drawArc(
                color = IconInk,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(c.x - r, c.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(1.6f * k, cap = StrokeCap.Round),
            )
            // Scale: 13 ticks, every third longer. Ticks the needle has passed light up.
            for (i in 0..12) {
                val deg = 180f + 15f * i
                val inner = if (i % 3 == 0) 19.5f else 21f
                val lit = needleAlpha.value > 0f && i / 12f <= needle.value
                drawLine(
                    color = if (lit) IconCopper else IconInk,
                    start = polar(deg, inner),
                    end = polar(deg, 23.5f),
                    strokeWidth = 1.1f * k,
                    cap = StrokeCap.Round,
                )
            }
            // Needle, only during the sweep.
            if (needleAlpha.value > 0f) {
                drawLine(
                    color = IconCopper.copy(alpha = needleAlpha.value),
                    start = c,
                    end = polar(180f + 180f * needle.value, 18f),
                    strokeWidth = 1.6f * k,
                    cap = StrokeCap.Round,
                )
                drawCircle(IconInk.copy(alpha = needleAlpha.value), radius = 1.8f * k, center = c)
            }
            // ECG trace, written from the left edge like a monitor sweep, with a bright head.
            if (trace.value > 0f) {
                val path = Path().apply {
                    moveTo(at(ECG.first()).x, at(ECG.first()).y)
                    ECG.drop(1).forEach { lineTo(at(it).x, at(it).y) }
                }
                val headX = ECG.first().x + (ECG.last().x - ECG.first().x) * trace.value
                clipRect(right = at(Offset(headX, 0f)).x + 1.8f * k) {
                    drawPath(path, IconCopper, style = Stroke(1.8f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                if (trace.value < 1f) {
                    val head = ecgPointAt(headX)
                    drawCircle(Color(0x55E67A2E), radius = 3.2f * k, center = at(head))
                    drawCircle(Color(0xFFFFC9A0), radius = 1.3f * k, center = at(head))
                }
            }
        }
    }
}

/** The point on the ECG trace at a given x (icon units), by straight-line interpolation. */
private fun ecgPointAt(x: Float): Offset {
    for (i in 1 until ECG.size) {
        val a = ECG[i - 1]
        val b = ECG[i]
        if (x <= b.x) {
            val t = if (b.x == a.x) 0f else (x - a.x) / (b.x - a.x)
            return Offset(x, a.y + (b.y - a.y) * t)
        }
    }
    return ECG.last()
}
