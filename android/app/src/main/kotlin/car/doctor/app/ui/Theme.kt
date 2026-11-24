package car.doctor.app.ui

import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Design tokens: a garage diagnostic tool with the finish of a new car's instrument cluster.
 * Graphite in a few depth steps, ice-white type, and one burnt-copper accent used sparingly
 * (needle, red zone, primary action, active tab). Amber is reserved for warning lamps and codes.
 */
internal object Tone {
    val Base = Color(0xFF0C0D0F)       // page background (bottom of the gradient)
    val BaseTop = Color(0xFF191B1F)    // top of the page gradient, like cabin light from above
    val Raised = Color(0xFF131417)     // panels
    val Sunken = Color(0xFF0B0C0E)     // inputs and tracks inside a panel
    val High = Color(0xFF17191C)       // dialogs, inner blocks, nav bar
    val MetalTop = Color(0xFF2A2D32)   // brushed-metal controls
    val MetalBottom = Color(0xFF17191C)
    val Line = Color(0xFF2A2D33)       // resting borders, dial track
    val LineStrong = Color(0xFF444851) // borders around a filled value or an enabled control
    val Accent = Color(0xFFD9773A)     // burnt copper: warm, not neon
    val AccentDeep = Color(0xFFC0652C)
    val AccentInk = Color(0xFF170903)
    val AccentText = Color(0xFFE8955E) // copper text on graphite (contrast-safe)
    val AccentTint = Color(0x1FD9773A) // selected states, ~12% copper
    val Amber = Color(0xFFFFB020)      // warning lamp, fault codes
    val AmberTint = Color(0x1AFFB020)
    val Go = Color(0xFF3CCB8C)         // completed / connected lamps
    val Paper = Color(0xFFECEEF1)
    val Soft = Color(0xFFC4C8CE)
    val Muted = Color(0xFF8A9099)
    val Dim = Color(0xFF4B5058)        // a lamp that is off
    val Danger = Color(0xFFFF7A66)
    val DangerTint = Color(0x1FFF7A66)
}

/** Condensed face for numbers, codes and units, like a trip computer. */
internal val DigitFamily: FontFamily = FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))

private val Scheme = darkColorScheme(
    primary = Tone.Accent,
    onPrimary = Tone.AccentInk,
    primaryContainer = Tone.AccentTint,
    onPrimaryContainer = Tone.AccentText,
    background = Tone.Base,
    onBackground = Tone.Paper,
    surface = Tone.Raised,
    onSurface = Tone.Paper,
    surfaceVariant = Color(0xFF1E2024),
    onSurfaceVariant = Tone.Muted,
    surfaceContainerLowest = Tone.Sunken,
    surfaceContainerLow = Tone.Raised,
    surfaceContainer = Tone.Raised,
    surfaceContainerHigh = Tone.High,
    surfaceContainerHighest = Tone.High,
    error = Tone.Danger,
    onError = Tone.AccentInk,
    outline = Tone.LineStrong,
    outlineVariant = Tone.Line,
)

/*
 * Hebrew first. Every style sets an RTL paragraph direction, so a line that starts with a
 * Latin code ("P0301 · שמור") still reads right to left. Values that are Latin by nature
 * (VIN, address, version) opt into LTR explicitly where they are shown.
 */
private fun TextStyle.rtl() = copy(textDirection = TextDirection.Rtl)

private val M3 = Typography()

private val DoctorType = Typography(
    displayLarge = M3.displayLarge.rtl(),
    displayMedium = M3.displayMedium.rtl(),
    displaySmall = M3.displaySmall.rtl(),
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Light).rtl(),
    // Page titles.
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold).rtl(),
    headlineSmall = M3.headlineSmall.rtl(),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold).rtl(),
    // Card titles, vehicle names.
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold).rtl(),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold).rtl(),
    // Default text and text field values.
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp).rtl(),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp).rtl(),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp).rtl(),
    // Buttons.
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold).rtl(),
    // Section labels and field captions.
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp).rtl(),
    // Navigation labels.
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium).rtl(),
)

internal val ControlShape = RoundedCornerShape(12.dp)
internal val PanelShape = RoundedCornerShape(18.dp)
internal val ControlHeight = 50.dp

internal val MetalBrush = Brush.verticalGradient(listOf(Tone.MetalTop, Tone.MetalBottom))
internal val PanelBrush = Brush.linearGradient(listOf(Color(0xFF1E2024), Tone.Raised, Color(0xFF111214)))
internal val AccentBrush = Brush.verticalGradient(listOf(Color(0xFFE0844A), Tone.AccentDeep))

@Composable
fun DoctorTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = Scheme, typography = DoctorType, content = content)
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = PanelShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, Tone.Line),
        content = content,
    )
}

/** A panel with the brushed, lit-from-above finish of an instrument cluster. */
@Composable
fun ClusterPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(PanelBrush)
            .border(BorderStroke(1.dp, Color(0xFF2C2F35)), PanelShape),
    ) { content() }
}

/** Page title, with an optional small line above it and one paragraph under it. */
@Composable
fun PageTitle(title: String, intro: String? = null, eyebrow: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
        eyebrow?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Tone.Muted) }
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Tone.Paper)
        intro?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Tone.Muted, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Heading of a panel: a quiet, tracked caption, like the legend on a dial. */
@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Tone.Muted,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

/** Caption above a group of controls inside a form (for example, fuel type). */
@Composable
fun FieldCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Tone.Soft,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
}

/** A small status lamp with a short label (e.g. connection state). */
@Composable
fun StatusLamp(text: String, on: Boolean) {
    Row(
        modifier = Modifier
            .widthIn(max = 180.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(BorderStroke(1.dp, if (on) Tone.Go.copy(alpha = 0.4f) else Tone.Line), RoundedCornerShape(8.dp))
            .background(if (on) Tone.Go.copy(alpha = 0.08f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) Tone.Go else Tone.Dim))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (on) Color(0xFF7FE0B4) else Tone.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The main action of a screen or panel: copper. */
@Composable
fun PrimaryAction(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = ControlShape,
        contentPadding = ButtonDefaults.ContentPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = Tone.Accent,
            contentColor = Tone.AccentInk,
            disabledContainerColor = Color(0xFF1E2024),
            disabledContentColor = Tone.Muted.copy(alpha = 0.7f),
        ),
    ) {
        ButtonLabel(label)
    }
}

/**
 * A secondary action, or one option of a set. Not selected: a quiet graphite button.
 * Selected: copper tint, copper border and a check mark.
 */
@Composable
fun QuietAction(
    label: String,
    enabled: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ControlHeight)
            .semantics { this.selected = selected },
        shape = ControlShape,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = when {
                selected -> Tone.Accent
                enabled -> Tone.Line
                else -> Tone.Line.copy(alpha = 0.6f)
            },
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Tone.AccentTint else Tone.Raised,
            contentColor = if (selected) Tone.AccentText else Tone.Soft,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Tone.Muted.copy(alpha = 0.55f),
        ),
    ) {
        if (selected) {
            Icon(DoctorIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        ButtonLabel(label)
    }
}

/** An action that deserves a second look before it is used (clearing codes). Amber outline. */
@Composable
fun CautionAction(label: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = ControlHeight),
        shape = ControlShape,
        border = BorderStroke(1.dp, Tone.Amber.copy(alpha = 0.4f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Tone.AmberTint,
            contentColor = Color(0xFFFFC35C),
        ),
    ) {
        ButtonLabel(label)
    }
}

/**
 * The scan button, after a push-button start: a brushed-metal key with a lit power ring.
 * Disabled, the ring goes dark like an unlit button.
 */
@Composable
fun IgnitionAction(
    label: String,
    detail: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Color(0xFF3A3E45)),
    ) {
        Row(
            modifier = Modifier
                .background(MetalBrush)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ring = if (enabled) Tone.Accent else Tone.Dim
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .border(BorderStroke(1.5.dp, ring), CircleShape)
                    .background(if (enabled) Tone.AccentTint else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    DoctorIcons.Power,
                    contentDescription = null,
                    tint = if (enabled) Tone.AccentText else Tone.Dim,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) Tone.Paper else Tone.Muted,
                )
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
            }
        }
    }
}

@Composable
private fun ButtonLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * One choice among a few short options in one row, like a drive-mode selector
 * (radio semantics). The selected segment is raised metal with a copper mark.
 */
@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(Tone.Sunken)
            .border(BorderStroke(1.dp, Tone.Line), ControlShape)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            val shape = RoundedCornerShape(9.dp)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .clip(shape)
                    .then(if (on) Modifier.background(MetalBrush, shape) else Modifier)
                    .selectable(selected = on, role = Role.RadioButton) { onSelect(option) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) Tone.Paper else Tone.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (on) {
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.size(width = 14.dp, height = 2.dp).clip(RoundedCornerShape(1.dp)).background(Tone.Accent))
                }
            }
        }
    }
}

/**
 * A tappable card with a title and a detail line (a vehicle in the garage).
 * A short bar on the start edge marks the selected one; the chevron points forward in RTL.
 */
@Composable
fun SelectableCard(
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(ControlShape)
            .background(if (selected) Tone.AccentTint else Tone.Raised)
            .border(
                BorderStroke(1.dp, if (selected) Tone.Accent else Tone.Line),
                ControlShape,
            )
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 28.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (selected) Tone.Accent else Color(0xFF33363C)),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Tone.Paper)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
        }
        Spacer(Modifier.width(8.dp))
        if (selected) {
            Text("נבחר", style = MaterialTheme.typography.labelLarge, color = Tone.AccentText)
        } else {
            Icon(DoctorIcons.Chevron, contentDescription = null, tint = Tone.Muted, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Text field. [ltr] is for values that are Latin by nature (VIN, address, port);
 * [mono] uses the condensed digit face so identifiers read like a data plate.
 */
@Composable
fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    number: Boolean = false,
    ltr: Boolean = false,
    mono: Boolean = false,
    supporting: String? = null,
    capitalizeAll: Boolean = false,
    onChange: (String) -> Unit,
) {
    val base = MaterialTheme.typography.bodyLarge
    val textStyle = when {
        mono -> base.copy(textDirection = TextDirection.Ltr, fontFamily = DigitFamily, fontSize = 17.sp, letterSpacing = 1.sp)
        ltr -> base.copy(textDirection = TextDirection.Ltr, fontFamily = DigitFamily, fontSize = 17.sp)
        else -> base
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingText = if (supporting != null) {
            { Text(supporting) }
        } else {
            null
        },
        singleLine = true,
        textStyle = textStyle,
        shape = ControlShape,
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Number else KeyboardType.Text,
            capitalization = if (capitalizeAll) KeyboardCapitalization.Characters else KeyboardCapitalization.None,
            autoCorrectEnabled = if (capitalizeAll) false else null,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Tone.Paper,
            unfocusedTextColor = Tone.Paper,
            focusedContainerColor = Tone.Sunken,
            unfocusedContainerColor = Tone.Sunken,
            cursorColor = Tone.Accent,
            focusedBorderColor = Tone.Accent,
            // A field that holds a value gets a slightly stronger border than an empty one.
            unfocusedBorderColor = if (value.isEmpty()) Tone.Line else Tone.LineStrong,
            focusedLabelColor = Tone.AccentText,
            unfocusedLabelColor = Tone.Muted,
            focusedSupportingTextColor = Tone.Muted,
            unfocusedSupportingTextColor = Tone.Muted,
        ),
    )
}

@Composable
fun PairRow(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.weight(1f)) { left() }
        Row(Modifier.weight(1f)) { right() }
    }
}

/** A label on the start side and its value on the end side. */
@Composable
fun StateLine(label: String, value: String, ltrValue: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Tone.Muted)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.let {
                if (ltrValue) it.copy(textDirection = TextDirection.Ltr, fontFamily = DigitFamily, fontSize = 15.sp) else it
            },
            fontWeight = FontWeight.SemiBold,
            color = Tone.Paper,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** A message the user must notice (notices and errors). */
@Composable
fun Banner(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(Tone.DangerTint)
            .border(BorderStroke(1.dp, Tone.Danger.copy(alpha = 0.45f)), ControlShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(Tone.Danger),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Tone.Paper)
    }
}

/** A fault code shown like a digital readout: amber, condensed, on black. */
@Composable
fun CodeChip(code: String) {
    Text(
        code,
        style = TextStyle(
            fontFamily = DigitFamily,
            fontSize = 20.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
            textDirection = TextDirection.Ltr,
        ),
        color = Tone.Amber,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0A0A0B))
            .border(BorderStroke(1.dp, Tone.Amber.copy(alpha = 0.35f)), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
