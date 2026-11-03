package car.doctor.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
 * Design tokens. One dark base in four depth steps, one brass accent, quiet lines.
 * Every screen takes its colors, type and shapes from here.
 */
internal object Tone {
    val Base = Color(0xFF0F110F)       // window and page background
    val Raised = Color(0xFF181B17)     // panels
    val Sunken = Color(0xFF121411)     // inputs and choice tiles inside a panel
    val High = Color(0xFF22261F)       // dialogs, inner blocks, nav bar
    val Line = Color(0xFF2C3229)       // resting borders
    val LineStrong = Color(0xFF454D41) // borders around a filled value or an enabled control
    val Brass = Color(0xFFE6B15A)
    val BrassInk = Color(0xFF1C1408)
    val BrassTint = Color(0x24E6B15A)  // selected states, ~14% brass
    val Paper = Color(0xFFF1EEE6)
    val Muted = Color(0xFFA9A598)
    val Soft = Color(0xFFD3CEC2)       // section labels, a step below Paper
    val Danger = Color(0xFFE88A70)
    val DangerTint = Color(0x1FE88A70)
}

private val Scheme = darkColorScheme(
    primary = Tone.Brass,
    onPrimary = Tone.BrassInk,
    primaryContainer = Tone.BrassTint,
    onPrimaryContainer = Tone.Brass,
    background = Tone.Base,
    onBackground = Tone.Paper,
    surface = Tone.Raised,
    onSurface = Tone.Paper,
    surfaceVariant = Color(0xFF262B23),
    onSurfaceVariant = Tone.Muted,
    surfaceContainerLowest = Tone.Sunken,
    surfaceContainerLow = Tone.Raised,
    surfaceContainer = Tone.Raised,
    surfaceContainerHigh = Tone.High,
    surfaceContainerHighest = Tone.High,
    error = Tone.Danger,
    onError = Tone.BrassInk,
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
    headlineLarge = M3.headlineLarge.rtl(),
    // Page titles.
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold).rtl(),
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
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold).rtl(),
    // Navigation labels.
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium).rtl(),
)

internal val ControlShape = RoundedCornerShape(12.dp)
internal val PanelShape = RoundedCornerShape(16.dp)
internal val ControlHeight = 50.dp

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

/** Page title with an optional one-paragraph explanation under it. */
@Composable
fun PageTitle(title: String, intro: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = Tone.Paper)
        intro?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Tone.Muted)
        }
    }
}

/** Heading of a panel: a short brass mark and a quiet caption. */
@Composable
fun SectionLabel(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Tone.Brass),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = Tone.Soft)
    }
}

/** Caption above a group of controls inside a form (for example, fuel type). */
@Composable
fun FieldCaption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = Tone.Muted,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
}

/** The main action of a screen or panel: filled brass. */
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
        modifier = modifier.fillMaxWidth().heightIn(min = ControlHeight),
        shape = ControlShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Tone.Brass,
            contentColor = Tone.BrassInk,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContentColor = Tone.Muted.copy(alpha = 0.7f),
        ),
    ) {
        ButtonLabel(label)
    }
}

/**
 * A secondary action, or one option of a set. Not selected: a quiet outlined button.
 * Selected: brass tint, brass border and a check mark, so the choice is clear without
 * competing with the primary action.
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
                selected -> Tone.Brass
                enabled -> Tone.LineStrong
                else -> Tone.Line
            },
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) Tone.BrassTint else Color.Transparent,
            contentColor = if (selected) Tone.Brass else Tone.Paper,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = Tone.Muted.copy(alpha = 0.6f),
        ),
    ) {
        if (selected) {
            Icon(DoctorIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        ButtonLabel(label)
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
 * A single choice among a few short options, laid out as a grid of tiles
 * (radio semantics). Used for fuel type.
 */
@Composable
fun <T> ChoiceGrid(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    columns: Int = 2,
    onSelect: (T) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().selectableGroup(),
    ) {
        options.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { option ->
                    ChoiceTile(
                        label = label(option),
                        selected = option == selected,
                        modifier = Modifier.weight(1f),
                    ) { onSelect(option) }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ChoiceTile(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(ControlShape)
            .background(if (selected) Tone.BrassTint else Tone.Sunken)
            .border(
                BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Tone.Brass else Tone.Line),
                ControlShape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected) {
            Icon(DoctorIcons.Check, contentDescription = null, tint = Tone.Brass, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (selected) Tone.Brass else Tone.Paper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A tappable card with a title and a detail line (a vehicle in the garage).
 * The chevron points forward in RTL, because tapping moves on to the bench.
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
            .background(if (selected) Tone.BrassTint else Tone.Sunken)
            .border(
                BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Tone.Brass else Tone.Line),
                ControlShape,
            )
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) Tone.Brass else Tone.Paper,
            )
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            DoctorIcons.Chevron,
            contentDescription = null,
            tint = if (selected) Tone.Brass else Tone.Muted,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * Text field. [ltr] is for values that are Latin by nature (VIN, address, port);
 * [mono] adds a fixed-width face so long identifiers are easy to check.
 */
@Composable
fun Field(
    label: String,
    value: String,
    number: Boolean = false,
    ltr: Boolean = false,
    mono: Boolean = false,
    supporting: String? = null,
    capitalizeAll: Boolean = false,
    onChange: (String) -> Unit,
) {
    val base = MaterialTheme.typography.bodyLarge
    val textStyle = when {
        mono -> base.copy(textDirection = TextDirection.Ltr, fontFamily = FontFamily.Monospace, letterSpacing = 0.5.sp)
        ltr -> base.copy(textDirection = TextDirection.Ltr)
        else -> base
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = if (supporting != null) {
            { Text(supporting) }
        } else {
            null
        },
        singleLine = true,
        textStyle = textStyle,
        shape = ControlShape,
        modifier = Modifier.fillMaxWidth(),
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
            cursorColor = Tone.Brass,
            focusedBorderColor = Tone.Brass,
            // A field that holds a value gets a slightly stronger border than an empty one.
            unfocusedBorderColor = if (value.isEmpty()) Tone.Line else Tone.LineStrong,
            focusedLabelColor = Tone.Brass,
            unfocusedLabelColor = Tone.Muted,
            focusedSupportingTextColor = Tone.Muted,
            unfocusedSupportingTextColor = Tone.Muted,
        ),
    )
}

@Composable
fun PairRow(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
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
                if (ltrValue) it.copy(textDirection = TextDirection.Ltr) else it
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
                .clip(RoundedCornerShape(3.dp))
                .background(Tone.Danger),
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Tone.Paper)
    }
}

/** A fault code as a compact LTR chip, e.g. P0301. */
@Composable
fun CodeChip(code: String) {
    Text(
        code,
        style = MaterialTheme.typography.labelLarge.copy(
            textDirection = TextDirection.Ltr,
            fontFamily = FontFamily.Monospace,
        ),
        color = Tone.Brass,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Tone.BrassTint)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
