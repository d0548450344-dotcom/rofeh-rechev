package car.doctor.app.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/*
 * Launch animation, "carbon sport" (chosen by the owner from three styles).
 * On carbon fibre: the icon pops in, the tachometer needle jumps to the red zone lighting
 * every tick it passes, drops back to idle with a small overshoot, then the ECG trace is
 * written across with a bright head and the app name fades in. 2.2 s, then a short fade.
 * A tap skips it; it is skipped when the system turns animations off.
 * Geometry is in a 200-unit box, like the approved web prototype. The last frame is the
 * launcher icon (res/drawable/ic_launcher_foreground.xml), so opening the app continues it.
 */

private object Carbon {
    val Ground = Color(0xFF101012)
    val Weave = Color(0xFF202024)
    val FaceTop = Color(0xFF1C1C20)
    val FaceBottom = Color(0xFF09090B)
    val Rim = Color(0x24ECEEF1)
    val TickOff = Color(0xFF4A4A52)
    val RedOff = Color(0xFF8A3A2C)
    val TickOn = Color(0xFFECEEF1)
    val Red = Color(0xFFD04A35)
    val Needle = Color(0xFFD9553A)
    val Hub = Color(0xFF111113)
    val Ecg = Color(0xFFE06A3C)
    val Core = Color(0xFFFFFFFF)
}

// Timing ("normal"): entrance, sweep up, sweep down, ECG; total in ms.
private const val T_IN = 320f
private const val T_UP = 560f
private const val T_DOWN = 480f
private const val T_ECG = 840f
private const val T_TOTAL = 2200f

private const val TICKS = 25
private const val MIN_ANGLE = -130f
private const val MAX_ANGLE = 130f
private const val IDLE_ANGLE = -104f

private val ECG = listOf(
    Offset(32f, 132f), Offset(62f, 132f), Offset(71f, 122f), Offset(79f, 138f), Offset(89f, 102f),
    Offset(99f, 152f), Offset(109f, 116f), Offset(117f, 132f), Offset(168f, 132f),
)
private val ECG_LENGTH = ECG.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.sum()

private fun easeOutCubic(t: Float) = 1f - (1f - t).pow(3)
private fun easeInOutCubic(t: Float) = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f
private fun easeOutBack(t: Float): Float {
    val c1 = 1.4f
    val c3 = c1 + 1f
    return 1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
}

/** Everything the frame needs, derived from elapsed milliseconds. */
private class Frame(ms: Float) {
    val entrance = (ms / T_IN).coerceIn(0f, 1f)
    val needle: Float
    val trace: Float
    val title: Float

    init {
        val upEnd = T_IN + T_UP
        val downEnd = upEnd + T_DOWN
        needle = when {
            ms < T_IN -> MIN_ANGLE
            ms < upEnd -> MIN_ANGLE + easeOutCubic((ms - T_IN) / T_UP) * (MAX_ANGLE - MIN_ANGLE)
            ms < downEnd -> MAX_ANGLE - easeOutBack((ms - upEnd) / T_DOWN) * (MAX_ANGLE - IDLE_ANGLE)
            else -> IDLE_ANGLE
        }
        val t = ((ms - downEnd) / T_ECG).coerceIn(0f, 1f)
        trace = if (ms < downEnd) 0f else easeInOutCubic(t)
        title = ((t - 0.25f) / 0.55f).coerceIn(0f, 1f)
    }
}

@Composable
fun LaunchIntro(onDone: () -> Unit) {
    val context = LocalContext.current
    val done by rememberUpdatedState(onDone)
    val motionOff = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val clock = remember { Animatable(0f) }
    val veil = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        if (motionOff) {
            done()
            return@LaunchedEffect
        }
        clock.animateTo(T_TOTAL, tween(T_TOTAL.toInt(), easing = LinearEasing))
        delay(250)
        veil.animateTo(0f, tween(280))
        done()
    }

    val frame = Frame(clock.value)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(veil.value)
            .drawBehind { carbonWeave() }
            .clearAndSetSemantics { }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { done() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Canvas(
                Modifier
                    .size(168.dp)
                    .graphicsLayer {
                        val e = easeOutCubic(frame.entrance)
                        alpha = e
                        scaleX = 0.86f + 0.14f * e
                        scaleY = scaleX
                        translationY = (1f - e) * 8.dp.toPx()
                    },
            ) {
                val k = size.width / 200f
                scale(k, k, pivot = Offset.Zero) { tachIcon(frame) }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    alpha = frame.title
                    translationY = (1f - frame.title) * 6.dp.toPx()
                },
            ) {
                Text("רופא רכב", style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Tone.Paper))
                Text(
                    "OBD-II DIAGNOSTICS",
                    style = TextStyle(
                        fontFamily = DigitFamily,
                        fontSize = 12.sp,
                        letterSpacing = 2.4.sp,
                        color = Tone.Muted,
                        textDirection = TextDirection.Ltr,
                    ),
                )
            }
        }
    }
}

/** Carbon fibre: a dark ground, a soft light from above and an offset dot weave. */
private fun DrawScope.carbonWeave() {
    drawRect(Carbon.Ground)
    val step = 10.dp.toPx()
    val r = step * 0.18f
    var y = 0f
    var row = 0
    while (y < size.height + step) {
        var x = if (row % 2 == 0) 0f else step / 2f
        while (x < size.width + step) {
            drawCircle(Carbon.Weave, radius = r, center = Offset(x, y))
            x += step
        }
        y += step / 2f
        row++
    }
    drawRect(
        Brush.radialGradient(
            listOf(Color(0x0DFFFFFF), Color.Transparent),
            center = Offset(size.width / 2f, size.height * 0.2f),
            radius = size.width * 0.9f,
        ),
    )
}

/** Point on a dial whose 0° points up and grows clockwise (the prototype's convention). */
private fun dial(angle: Float, r: Float): Offset {
    val rad = (angle - 90f) * PI.toFloat() / 180f
    return Offset(100f + r * cos(rad), 100f + r * sin(rad))
}

/** The ECG polyline cut at a fraction of its length; also returns the head point. */
private fun ecgUpTo(fraction: Float): Pair<Path, Offset> {
    val target = ECG_LENGTH * fraction
    val path = Path().apply { moveTo(ECG[0].x, ECG[0].y) }
    var walked = 0f
    var head = ECG[0]
    for (i in 1 until ECG.size) {
        val a = ECG[i - 1]
        val b = ECG[i]
        val seg = hypot(b.x - a.x, b.y - a.y)
        if (walked + seg >= target) {
            val t = if (seg == 0f) 0f else (target - walked) / seg
            head = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            path.lineTo(head.x, head.y)
            return path to head
        }
        path.lineTo(b.x, b.y)
        walked += seg
        head = b
    }
    return path to head
}

/** The icon in 200-unit coordinates. */
private fun DrawScope.tachIcon(f: Frame) {
    // Squircle face with a faint rim.
    drawRoundRect(
        brush = Brush.radialGradient(
            listOf(Carbon.FaceTop, Carbon.FaceBottom),
            center = Offset(100f, 76f),
            radius = 124f,
        ),
        topLeft = Offset(10f, 10f),
        size = Size(180f, 180f),
        cornerRadius = CornerRadius(44f, 44f),
    )
    drawRoundRect(
        color = Carbon.Rim,
        topLeft = Offset(10f, 10f),
        size = Size(180f, 180f),
        cornerRadius = CornerRadius(44f, 44f),
        style = Stroke(2f),
    )

    // Scale: 25 ticks over 260°, every fourth longer; the top 22% is the red zone.
    for (i in 0 until TICKS) {
        val r = i.toFloat() / (TICKS - 1)
        val a = MIN_ANGLE + r * (MAX_ANGLE - MIN_ANGLE)
        val major = i % 4 == 0
        val red = r >= 0.78f
        val on = f.needle - a >= -3f
        drawLine(
            color = when {
                on && red -> Carbon.Red
                on -> Carbon.TickOn
                red -> Carbon.RedOff
                else -> Carbon.TickOff
            },
            start = dial(a, if (major) 68f else 73f),
            end = dial(a, 82f),
            strokeWidth = if (major) 3f else 1.8f,
            cap = StrokeCap.Round,
        )
    }
    // Faint red-zone arc outside the scale.
    drawArc(
        color = Carbon.Red.copy(alpha = 0.3f),
        startAngle = -45f,
        sweepAngle = 84.8f,
        useCenter = false,
        topLeft = Offset(19f, 19f),
        size = Size(162f, 162f),
        style = Stroke(2f, cap = StrokeCap.Round),
    )

    // ECG: a ghost of the full trace, then the written part with a soft glow and a bright head.
    val (full, _) = ecgUpTo(1f)
    drawPath(full, Color(0x0FFFFFFF), style = Stroke(2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    if (f.trace > 0f) {
        val (written, head) = ecgUpTo(f.trace)
        drawPath(written, Carbon.Ecg.copy(alpha = 0.28f), style = Stroke(7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(written, Carbon.Ecg, style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        val dotAlpha = if (f.trace > 0.96f) (1f - (f.trace - 0.96f) * 15f).coerceIn(0.35f, 1f) else 1f
        drawCircle(Carbon.Ecg.copy(alpha = 0.4f * dotAlpha), radius = 7f, center = head)
        drawCircle(Carbon.Core.copy(alpha = dotAlpha), radius = 3f, center = head)
    }

    // Needle with a soft glow, then the hub.
    rotate(f.needle, pivot = Offset(100f, 100f)) {
        val blade = Path().apply {
            moveTo(97.6f, 104f)
            lineTo(100f, 28f)
            lineTo(102.4f, 104f)
            close()
        }
        val tail = Path().apply {
            moveTo(97f, 100f)
            lineTo(100f, 115f)
            lineTo(103f, 100f)
            close()
        }
        drawPath(blade, Carbon.Needle.copy(alpha = 0.3f), style = Stroke(5f, join = StrokeJoin.Round))
        drawPath(blade, Carbon.Needle)
        drawPath(tail, Carbon.Needle.copy(alpha = 0.8f))
    }
    translate(0f, 0f) {
        drawCircle(Carbon.Hub, radius = 10f, center = Offset(100f, 100f))
        drawCircle(Carbon.TickOn, radius = 10f, center = Offset(100f, 100f), style = Stroke(2.2f))
        drawCircle(Carbon.Core, radius = 3.2f, center = Offset(100f, 100f))
    }
}
