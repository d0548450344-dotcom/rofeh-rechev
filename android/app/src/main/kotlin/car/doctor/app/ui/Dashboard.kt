package car.doctor.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import car.doctor.core.catalog.EcuCatalog
import car.doctor.core.catalog.GAP_BADGE_HE
import car.doctor.core.catalog.ecuAccessLabel
import car.doctor.core.catalog.monitorLabel
import car.doctor.core.model.EcuAccess
import car.doctor.core.model.EcuId
import car.doctor.core.model.MonitorStatus
import car.doctor.core.present.MonitorLine
import car.doctor.core.present.SampleLine
import car.doctor.core.present.SessionView
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Instrument-cluster views over a finished session. They only show what the session already
 * holds (samples, codes, unit coverage, readiness). They never judge a value as good or bad:
 * a needle in the red zone is the dial's scale, not a finding.
 */

/** Sample names as the adapters report them (simulation and ELM327 use the same names). */
private object Signal {
    const val VOLTAGE = "מתח יחידת בקרה"
    const val COOLANT = "טמפרטורת קירור"
    const val RPM = "סל״ד"
    const val SPEED = "מהירות רכב"
    const val THROTTLE = "מצערת"
}

private fun SessionView?.sample(name: String): SampleLine? = this?.samples?.firstOrNull { it.nameHe == name }

/** The number behind a sample line, or null when it was not read. */
private fun SampleLine?.number(): Double? = this?.takeIf { it.unit.isNotEmpty() }?.valueHe?.toDoubleOrNull()

/**
 * Two dials (system voltage, coolant temperature) and a trip-computer row (rpm, speed,
 * throttle), from the latest scan. Before the first scan the dials rest at zero with dashes.
 */
@Composable
fun InstrumentCluster(latest: SessionView?) {
    ClusterPanel {
        Column(
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Gauge(
                    label = "מתח מערכת",
                    value = latest.sample(Signal.VOLTAGE).number(),
                    unit = "V",
                    min = 10.0,
                    max = 16.0,
                    redFrom = 15.0,
                    majorEvery = 1.0,
                    decimals = 1,
                    modifier = Modifier.weight(1f),
                )
                Gauge(
                    label = "טמפ׳ מנוע",
                    value = latest.sample(Signal.COOLANT).number(),
                    unit = "°C",
                    min = 40.0,
                    max = 120.0,
                    redFrom = 105.0,
                    majorEvery = 20.0,
                    decimals = 0,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider(thickness = 1.dp, color = Tone.Line, modifier = Modifier.padding(horizontal = 6.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                Readout("סל״ד", latest.sample(Signal.RPM), "rpm", Modifier.weight(1f))
                ReadoutDivider()
                Readout("מהירות", latest.sample(Signal.SPEED), "קמ״ש", Modifier.weight(1f))
                ReadoutDivider()
                Readout("מצערת", latest.sample(Signal.THROTTLE), "%", Modifier.weight(1f))
            }
            Text(
                if (latest == null) "השעונים יתמלאו אחרי הסריקה הראשונה" else "מהסריקה האחרונה · ${latest.finishedAt}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6E747D),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ReadoutDivider() {
    Box(Modifier.width(1.dp).height(40.dp).background(Tone.Line))
}

/** One trip-computer value: big condensed number, small unit, caption under it. */
@Composable
private fun Readout(label: String, sample: SampleLine?, fallbackUnit: String, modifier: Modifier) {
    val number = sample.number()
    val value = if (number == null) "—" else sample!!.valueHe
    val unit = sample?.unit?.takeIf { it.isNotEmpty() } ?: fallbackUnit
    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = "$label: $value $unit" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            buildAnnotatedString {
                append(value)
                withStyle(SpanStyle(fontSize = 12.sp, color = Tone.Muted)) { append(" $unit") }
            },
            style = TextStyle(
                fontFamily = DigitFamily,
                fontSize = 22.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Medium,
                textDirection = TextDirection.Content,
            ),
            color = if (number == null) Tone.Dim else Tone.Paper,
            maxLines = 1,
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = Tone.Muted, maxLines = 1)
    }
}

/**
 * An analog dial: 270° sweep, minor and major ticks, a red zone at the top of the scale,
 * a copper needle and the value in condensed digits under the hub.
 * Drawn left-to-right like a real dial; it does not mirror in RTL.
 */
@Composable
private fun Gauge(
    label: String,
    value: Double?,
    unit: String,
    min: Double,
    max: Double,
    redFrom: Double,
    majorEvery: Double,
    decimals: Int,
    modifier: Modifier = Modifier,
) {
    val text = value?.let { if (decimals == 0) Math.round(it).toString() else String.format(java.util.Locale.US, "%.${decimals}f", it) } ?: "—"
    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = "$label: $text $unit" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(width = 150.dp, height = 136.dp), contentAlignment = Alignment.TopCenter) {
            Canvas(Modifier.fillMaxSize()) {
                val track = 8.dp.toPx()
                val radius = size.minDimension / 2f - track
                val center = Offset(size.width / 2f, size.height / 2f)
                val arcSize = Size(radius * 2, radius * 2)
                val arcTopLeft = Offset(center.x - radius, center.y - radius)
                val span = max - min
                fun angleOf(v: Double): Float = (135.0 + 270.0 * ((v - min) / span).coerceIn(0.0, 1.0)).toFloat()
                fun point(deg: Float, r: Float): Offset {
                    val rad = deg * PI.toFloat() / 180f
                    return Offset(center.x + r * cos(rad), center.y + r * sin(rad))
                }

                drawArc(Tone.Line, 135f, 270f, false, arcTopLeft, arcSize, style = Stroke(track))
                val redStart = angleOf(redFrom)
                drawArc(Tone.Accent, redStart, 405f - redStart, false, arcTopLeft, arcSize, style = Stroke(track))

                val minorCount = ((span / majorEvery) * 5).toInt()
                for (i in 0..minorCount) {
                    val deg = 135f + 270f * i / minorCount
                    val major = i % 5 == 0
                    val inner = radius - track / 2f - (if (major) 10.dp.toPx() else 5.dp.toPx())
                    val outer = radius - track / 2f - 2.dp.toPx()
                    drawLine(
                        color = if (major) Tone.Paper else Color(0xFF5E646D),
                        start = point(deg, inner),
                        end = point(deg, outer),
                        strokeWidth = if (major) 2.dp.toPx() else 1.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }

                val needleAngle = if (value == null) 135f else angleOf(value)
                drawLine(
                    color = if (value == null) Tone.Dim else Tone.Accent,
                    start = center,
                    end = point(needleAngle, radius - 18.dp.toPx()),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawCircle(Color(0xFF3A3E45), radius = 7.dp.toPx(), center = center)
                drawCircle(Color(0xFF1E2024), radius = 5.5.dp.toPx(), center = center)
            }
            Text(
                buildAnnotatedString {
                    append(text)
                    withStyle(SpanStyle(fontSize = 14.sp, color = Tone.Muted)) { append(" $unit") }
                },
                style = TextStyle(
                    fontFamily = DigitFamily,
                    fontSize = 28.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.Medium,
                    textDirection = TextDirection.Ltr,
                ),
                color = if (value == null) Tone.Dim else Tone.Paper,
                modifier = Modifier.padding(top = 92.dp),
            )
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = Tone.Soft)
    }
}

private enum class Lamp { ON, CLEAR, OUT, ERROR }

private class LampState(val lamp: Lamp, val caption: String)

/**
 * Transmission codes by the SAE J2012 / ISO 15031-6 numbering: P07xx, P08xx and P09xx.
 * Over generic OBD-II they arrive through the powertrain (engine) unit, so they are split
 * out of the engine lamp by their number, not by which unit sent them.
 */
private fun isTransmissionCode(code: String): Boolean {
    val c = code.trim().uppercase()
    return c.length == 5 && c[0] == 'P' && c[1] == '0' && c[2] in '7'..'9'
}

private fun countLabel(codes: Int) = if (codes == 1) "קוד אחד" else "$codes קודים"

/** Lamp for a unit that answered or not, when it returned no codes of its own. */
private fun accessLamp(name: String, session: SessionView, clearCaption: String): LampState {
    val line = session.ecus.firstOrNull { it.nameHe == name } ?: return LampState(Lamp.OUT, GAP_BADGE_HE)
    return when (line.badgeHe) {
        ecuAccessLabel(EcuAccess.CHECKED) -> LampState(Lamp.CLEAR, clearCaption)
        ecuAccessLabel(EcuAccess.COMM_ERROR) -> LampState(Lamp.ERROR, line.badgeHe)
        else -> LampState(Lamp.OUT, line.badgeHe)
    }
}

/**
 * The lamp for one unit, taken only from what the session holds: lit when that unit
 * returned codes, dark when it was checked and returned none, dimmed when it was not read.
 * The engine lamp leaves out transmission-range codes; the gearbox lamp shows them.
 */
private fun lampFor(id: EcuId, session: SessionView): LampState {
    val name = EcuCatalog.get(id).nameHe
    val codes = session.codes.count { it.ecuHe == name && !(id == EcuId.ENGINE && isTransmissionCode(it.code)) }
    if (codes > 0) return LampState(Lamp.ON, countLabel(codes))
    return accessLamp(name, session, "ללא קודים")
}

/**
 * Gearbox lamp. Generic OBD-II reads only the transmission codes the powertrain reports
 * (P07xx–P09xx); the gearbox unit itself is not scanned. So "no codes" says so: OBD only.
 */
private fun gearboxLamp(session: SessionView): LampState {
    val codes = session.codes.count { isTransmissionCode(it.code) }
    if (codes > 0) return LampState(Lamp.ON, countLabel(codes))
    return accessLamp(EcuCatalog.get(EcuId.ENGINE).nameHe, session, "ללא קודים (OBD בלבד)")
}

/** A row of dashboard tell-tales: engine, gearbox, brakes (ABS), airbags. */
@Composable
fun WarningLamps(session: SessionView) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LampTile("מנוע", DoctorIcons.Engine, lampFor(EcuId.ENGINE, session), Modifier.weight(1f))
        LampTile("גיר", DoctorIcons.Gearbox, gearboxLamp(session), Modifier.weight(1f))
        LampTile("ABS", DoctorIcons.Brake, lampFor(EcuId.ABS, session), Modifier.weight(1f))
        LampTile("כריות", DoctorIcons.Airbag, lampFor(EcuId.SRS, session), Modifier.weight(1f))
    }
}

@Composable
private fun LampTile(name: String, icon: ImageVector, state: LampState, modifier: Modifier) {
    val tint = when (state.lamp) {
        Lamp.ON -> Tone.Amber
        Lamp.CLEAR -> Color(0xFF6E747D)
        Lamp.OUT -> Color(0xFF3A3E45)
        Lamp.ERROR -> Tone.Danger
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = "$name: ${state.caption}" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(shape)
                .background(if (state.lamp == Lamp.ON) Tone.AmberTint else Color(0xFF15171A))
                .border(
                    BorderStroke(1.dp, if (state.lamp == Lamp.ON) Tone.Amber.copy(alpha = 0.45f) else Tone.Line),
                    shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        }
        Text(
            name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (state.lamp == Lamp.ON) Color(0xFFFFC35C) else Tone.Soft,
            maxLines = 1,
        )
        Text(
            state.caption,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = if (state.lamp == Lamp.ON) Color(0xFFFFC35C) else Tone.Muted,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/** Readiness monitors as a list of lamps: green when complete, hollow when not yet, dim when unsupported. */
@Composable
fun ReadinessList(monitors: List<MonitorLine>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Tone.Sunken)
            .border(BorderStroke(1.dp, Tone.Line), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        monitors.forEachIndexed { index, monitor ->
            val complete = monitor.statusHe == monitorLabel(MonitorStatus.COMPLETE)
            val incomplete = monitor.statusHe == monitorLabel(MonitorStatus.INCOMPLETE)
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .then(
                            when {
                                complete -> Modifier.background(Tone.Go)
                                incomplete -> Modifier.border(BorderStroke(1.5.dp, Tone.Muted), CircleShape)
                                else -> Modifier.background(Tone.Line)
                            },
                        ),
                )
                Spacer(Modifier.width(12.dp))
                Text(monitor.nameHe, style = MaterialTheme.typography.bodyMedium, color = Tone.Paper, modifier = Modifier.weight(1f))
                Text(
                    monitor.statusHe,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        complete -> Color(0xFF7FE0B4)
                        incomplete -> Tone.Soft
                        else -> Tone.Muted
                    },
                )
            }
            if (index < monitors.lastIndex) HorizontalDivider(thickness = 1.dp, color = Color(0xFF1E2024))
        }
    }
}
