package car.doctor.app.ui

import car.doctor.app.report.SaleReportPdf
import car.doctor.app.report.Receipts
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.core.content.FileProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import car.doctor.core.care.ServiceKind
import car.doctor.core.care.IntervalUnit
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DatePicker
import androidx.compose.runtime.remember
import car.doctor.core.care.TrendDirection
import car.doctor.core.care.TrendReport
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import car.doctor.app.ClearStep
import car.doctor.app.DoctorScreen
import car.doctor.app.DoctorUi
import car.doctor.app.DoctorViewModel
import car.doctor.app.PickerMode
import car.doctor.app.VisitForm
import car.doctor.core.visit.VisitView
import car.doctor.core.catalog.GAP_BADGE_HE
import car.doctor.core.catalog.fuelLabel
import car.doctor.core.model.FaultId
import car.doctor.core.model.Fuel
import car.doctor.core.model.Posture
import car.doctor.core.present.CodeLine
import car.doctor.core.present.FindingView
import car.doctor.core.present.SampleLine
import car.doctor.core.present.SessionView

@Composable
fun DoctorApp(vm: DoctorViewModel) {
    val ui = vm.ui
    // Edge to edge: the page respects the status bar, cutouts and the keyboard; the nav bar
    // paints under the system navigation area and pads its own content above it.
    Box(Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to Tone.BaseTop, 0.4f to Tone.Base, 1f to Color(0xFF0A0A0C)))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (vm.screen) {
                DoctorScreen.HOME -> HomePage(ui, vm)
                DoctorScreen.BENCH -> BenchPage(ui, vm)
                DoctorScreen.RESULT -> ResultPage(ui)
                DoctorScreen.HISTORY -> HistoryPage(ui, vm)
                DoctorScreen.SETTINGS -> SettingsPage(ui, vm)
            }
        }
        NavBar(vm)
    }
    ClearDialogs(vm)
    // Plays once per launch (kept across rotation), over everything, then gets out of the way.
    var intro by rememberSaveable { mutableStateOf(true) }
    if (intro) LaunchIntro { intro = false }
    }
}

/**
 * Five destinations, always visible, equal width, in RTL order (בית on the right).
 * A solid strip like the bottom of a dashboard: the active item is ice-white with a short
 * copper bar above it; the others are dimmed.
 */
@Composable
private fun NavBar(vm: DoctorViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xFF141518), Color(0xFF0E0F11)))),
    ) {
        HorizontalDivider(thickness = 1.dp, color = Color(0xFF24272C))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .padding(horizontal = 4.dp)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem("בית", DoctorIcons.Home, vm.screen == DoctorScreen.HOME) { vm.showHome() }
            NavItem("ספסל", DoctorIcons.Gauge, vm.screen == DoctorScreen.BENCH) { vm.showBench() }
            NavItem("תוצאה", DoctorIcons.Report, vm.screen == DoctorScreen.RESULT) { vm.showResult() }
            NavItem("היסטוריה", DoctorIcons.History, vm.screen == DoctorScreen.HISTORY) { vm.showHistory() }
            NavItem("הגדרות", DoctorIcons.Settings, vm.screen == DoctorScreen.SETTINGS) { vm.showSettings() }
        }
    }
}

@Composable
private fun RowScope.NavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) Tone.Paper else Color(0xFF7C828B)
    Box(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 64.dp)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .size(width = 28.dp, height = 3.dp)
                    .clip(RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp))
                    .background(Tone.Accent),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            // Labels follow the system font size up to 1.3x; past that the five items no
            // longer fit one line on a small phone, and a wrapped label breaks the bar.
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(1.3f)),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = tint,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun HomePage(ui: DoctorUi, vm: DoctorViewModel) {
    BrandBar(ui)
    ui.storageNoticeHe?.let { Banner(it) }
    ui.noticeHe?.let { Banner(it) }

    if (ui.hasVehicle) {
        VehicleHero(ui)
        PrimaryAction("לספסל הסריקה", enabled = true) { vm.showBench() }
        CarePanel(ui, vm)
        FindServicePanel(ui.make)
    } else {
        Text(
            "קודי תקלה, מוכנות ונתונים חיים דרך מתאם ELM327 (בלוטות׳ או Wi-Fi), או רכב מדומה לתרגול. מחיקת קודים עם הגנות; אין צריבה.",
            style = MaterialTheme.typography.bodyMedium,
            color = Tone.Muted,
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("המוסך")
        if (ui.vehicles.isEmpty()) {
            Text(
                "אין עדיין פרופיל. הוסיפו רכב, ואז עברו לספסל כדי לבחור מתאם ולהריץ סריקה.",
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.Muted,
            )
        }
        ui.vehicles.forEach { vehicle ->
            SelectableCard(vehicle.titleHe, vehicle.metaHe, selected = vehicle.selected) {
                vm.selectVehicle(vehicle.id)
            }
        }
    }

    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("פרופיל חדש")
            Field("כינוי", vm.nickname) { vm.nickname = it }
            PairRow(
                left = { Field("יצרן", vm.make) { vm.make = it } },
                right = { Field("דגם", vm.model) { vm.model = it } },
            )
            PairRow(
                left = { Field("שנת ייצור", vm.yearText, number = true, ltr = true) { vm.yearText = it } },
                right = {
                    Field("VIN", vm.vin, mono = true, capitalizeAll = true, supporting = "לא חובה · לא מאומת") {
                        vm.vin = it
                    }
                },
            )
            FieldCaption("סוג דלק")
            Segmented(
                options = Fuel.entries,
                selected = vm.fuel,
                label = { fuelLabel(it) },
                onSelect = { vm.chooseFuel(it) },
            )
            Spacer(Modifier.height(4.dp))
            PrimaryAction("שמור רכב", enabled = true) { vm.createVehicle() }
            QuietAction("הוסף קורולה לדוגמה", enabled = true, selected = false) { vm.addDemo() }
        }
    }
}

/** App mark and the adapter's readiness, like the top line of a cluster. */
@Composable
private fun BrandBar(ui: DoctorUi) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(DoctorIcons.Gauge, contentDescription = null, tint = Tone.AccentText, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text("רופא רכב", style = MaterialTheme.typography.titleMedium, color = Tone.Paper, modifier = Modifier.weight(1f))
        StatusLamp(ui.sourceLabelHe, on = ui.canDisconnect)
    }
}

/** The selected car, presented like the spec plate on a cluster, with its last scan. */
@Composable
private fun VehicleHero(ui: DoctorUi) {
    ClusterPanel {
        Column {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("הרכב הנבחר", style = MaterialTheme.typography.labelMedium, color = Tone.Muted)
                Text(ui.nickname, style = MaterialTheme.typography.headlineLarge, color = Tone.Paper)
                Text(ui.metaHe, style = MaterialTheme.typography.bodyMedium, color = Tone.Soft)
            }
            HorizontalDivider(thickness = 1.dp, color = Tone.Line)
            val latest = ui.latest
            val codes = latest?.codes?.size ?: 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x2E000000))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    DoctorIcons.Engine,
                    contentDescription = null,
                    tint = if (codes > 0) Tone.Amber else Tone.Dim,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        latest == null -> "עדיין לא נסרק"
                        codes == 0 -> "סריקה אחרונה · ללא קודים"
                        codes == 1 -> "סריקה אחרונה · קוד אחד"
                        else -> "סריקה אחרונה · $codes קודים"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Tone.Soft,
                    modifier = Modifier.weight(1f),
                )
                latest?.let {
                    Text(
                        it.finishedAt,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = DigitFamily, textDirection = TextDirection.Ltr),
                        color = Tone.Muted,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun BenchPage(ui: DoctorUi, vm: DoctorViewModel) {
    if (!ui.hasVehicle) {
        PageTitle("ספסל הסריקה")
        Banner("אין רכב נבחר. חוזרים לבית ובוחרים פרופיל.")
        QuietAction("לבית", enabled = true, selected = false) { vm.showHome() }
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) { PageTitle("ספסל הסריקה", eyebrow = ui.nickname) }
        StatusLamp(ui.sessionLabelHe, on = ui.canDisconnect)
    }

    InstrumentCluster(ui.latest)

    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("הרכב הנוכחי")
            Text(ui.metaHe, style = MaterialTheme.typography.bodyMedium, color = Tone.Soft)
            Note(ui.vinNoteHe)
            HorizontalDivider(thickness = 1.dp, color = Tone.Line, modifier = Modifier.padding(vertical = 6.dp))
            if (!ui.live) StateLine("הצתה", ui.ignitionLabelHe)
            StateLine("חיבור OBD", ui.sessionLabelHe)
            ui.protocolHe?.let { StateLine("פרוטוקול", it) }
        }
    }
    AdapterPanel(ui, vm)
    ui.busyHe?.let { ScanProgress(it) }
    ui.noticeHe?.let { Banner(it) }
    ui.errorHe?.let { Banner(it) }
    PairRow(
        left = { PrimaryAction("התחבר", ui.canConnect) { vm.connect() } },
        right = { QuietAction("נתק", ui.canDisconnect, false) { vm.disconnect() } },
    )
    IgnitionAction("סריקה מהירה", "קודי תקלה · מוכנות · חיישנים", enabled = ui.canScan) { vm.scan() }
    QuietAction("בדיקה מעמיקה · VIN · תמונת מצב · קודים קבועים", enabled = ui.canScan, selected = false) { vm.deepScan() }
    if (ui.canClear) {
        CautionAction("מחיקת קודי תקלה…", enabled = true) { vm.startClear() }
    }
    VisitActions(ui, vm)
    ui.latest?.let {
        QuietAction("פתח את התוצאה האחרונה", enabled = true, selected = false) { vm.showResult() }
    }

    if (ui.live) {
        LiveTips()
        return
    }

    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("הצתה מדומה")
            Note("מצב נוכחי: ${ui.ignitionLabelHe}")
            ui.moves.forEach { move ->
                QuietAction(move.labelHe, enabled = true, selected = false) { vm.ignite(move.to) }
            }
            FieldCaption("תנאי נסיעה · ${ui.postureLabelHe}")
            Posture.entries.forEach { posture ->
                val label = when (posture) {
                    Posture.IDLE -> "סרק"
                    Posture.STRAIGHT -> "שיוט ישר"
                    Posture.TURN -> "פנייה"
                    Posture.BRAKE -> "בלימה"
                }
                QuietAction(label, enabled = true, selected = ui.posture == posture) { vm.posture(posture) }
            }
        }
    }

    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("מעבדת פיתוח")
            Note("ההזרקה לא יוצרת ממצא. רק סריקה שחוזרת עם נתונים מפוענחים יכולה ליצור ממצא.")
            LabFault(FaultId.MISFIRE, "הזרק החטאה", ui, vm)
            LabFault(FaultId.LOW_BATTERY, "הזרק מתח נמוך", ui, vm)
            LabFault(FaultId.WHEEL_SPEED_MISMATCH, "הזרק הפרש גלגלים", ui, vm)
        }
    }
}

@Composable
private fun ResultPage(ui: DoctorUi) {
    PageTitle("תוצאת סריקה", eyebrow = if (ui.hasVehicle) ui.nickname else null)
    val session = ui.open
    if (session == null) {
        Panel {
            Text(
                "אין סריקה שמורה לרכב הזה. סריקה מהירה בספסל היא מה שיוצר תוצאה.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.Muted,
            )
        }
        return
    }
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SessionBody(session)
        }
    }
}

@Composable
private fun HistoryPage(ui: DoctorUi, vm: DoctorViewModel) {
    PageTitle(
        "היסטוריה",
        "הסריקות שמורות בקובץ מקומי של הרכב הנבחר. סגירת האפליקציה לא מוחקת אותן.",
    )
    if (ui.hasVehicle) SaleReportPanel(ui)
    if (ui.services.isNotEmpty()) ServiceHistory(ui, vm)
    ui.trends?.let { TrendsPanel(it) }
    if (ui.visits.isNotEmpty()) {
        SectionLabel("ביקורים במוסך")
        ui.visits.forEach { VisitCard(it, vm) }
        SectionLabel("סריקות")
    }
    if (ui.history.isEmpty()) {
        Panel {
            Text(
                "אין עדיין סריקות שמורות.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.Muted,
            )
        }
        return
    }
    ui.history.forEach { session ->
        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(session.headlineHe, style = MaterialTheme.typography.titleSmall, color = Tone.Paper)
                Note("${session.finishedAt} · ${session.ignitionLabelHe}")
                QuietAction("פתח סריקה", enabled = true, selected = session.id == ui.open?.id) {
                    vm.openSession(session.id)
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(ui: DoctorUi, vm: DoctorViewModel) {
    PageTitle("הגדרות")
    ui.storageNoticeHe?.let { Banner(it) }
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("מאגר קודי תקלה")
            Text(vm.dtcStatusHe, style = MaterialTheme.typography.bodyMedium, color = Tone.Paper)
            Note("האפליקציה בודקת עדכון פעם בשבוע ושומרת עותק לשימוש בלי אינטרנט. עדכון שלא עובר בדיקה לא נטען.")
            QuietAction(
                if (vm.dtcChecking) "בודק…" else "בדוק עדכון עכשיו",
                enabled = !vm.dtcChecking,
                selected = false,
            ) { vm.checkDtcUpdate() }
        }
    }
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("האפליקציה")
            StateLine("גרסה", appVersion(), ltrValue = true)
            StateLine("מתאם", ui.sourceLabelHe)
            StateLine("כתיבה ליחידה", "מחיקת קודים בלבד")
            StateLine("צריבה / שיפור כוח", "חסומה")
            Note(
                "רופא רכב קורא נתונים, ומוחק קודי תקלה רק אחרי שני אישורים, כשהמנוע כבוי והרכב עומד. אין צריבה, אין שינוי תוכנה ביחידות, אין גישת אבטחה ואין ענן.",
            )
        }
    }
    WireLogPanel(vm)
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("שמירה")
            Note("הפרופילים והסריקות נשמרים בקובץ garage.json בתיקיית האפליקציה. זה לא זיכרון זמני.")
            Note("אם הקובץ חסר, המוסך נפתח ריק. אם הקובץ פגום, הוא לא מפוענח כסריקה תקינה והמוסך מאופס.")
        }
    }
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("איך קוראים תוצאה")
            TermLine("נמצאה תקלה", "קוד או חריגת מדידה שהיחידה החזירה.")
            TermLine("חשד", "ממצא שדורש בדיקה נוספת, בלי שם של חלק להחלפה.")
            Text(
                "לא נמצאו ממצאים לפי הנתונים שנבדקו. זה לא אישור תקינות.",
                style = MaterialTheme.typography.bodyMedium,
                color = Tone.Paper,
            )
            TermLine("לא נבדק", "היחידה לא נשאלה.")
            TermLine("לא בסריקה", "מערכת שאינה חלק מהסריקה הזו.")
            TermLine("שגיאת תקשורת", "הניסיון נכשל. זו לא תוצאה נקיה.")
        }
    }
}

/** "term: explanation", with the term in bold. Same words as before, clearer scan. */
@Composable
private fun TermLine(term: String, explanation: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Tone.Paper)) { append(term) }
            append(": ")
            append(explanation)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = Tone.Soft,
    )
}

@Composable
private fun SessionBody(session: SessionView) {
    WarningLamps(session)
    HorizontalDivider(thickness = 1.dp, color = Tone.Line, modifier = Modifier.padding(vertical = 4.dp))
    Text(session.headlineHe, style = MaterialTheme.typography.titleMedium, color = Tone.Paper)
    session.clearNoteHe?.let {
        InnerBlock {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Tone.Paper)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        session.protocolHe?.let { Note(it) }
        Note(session.adapterLabelHe)
        Note("${session.finishedAt} · הצתה ${session.ignitionLabelHe}")
    }
    ResultSection("ממצאים")
    if (session.findings.isEmpty()) {
        Text("אין ממצאים על הנתונים שנקראו בסריקה הזו.", style = MaterialTheme.typography.bodyMedium)
    } else {
        session.findings.forEach { FindingBlock(it) }
    }
    if (session.deepNotesHe.isNotEmpty() || session.freezeFrame.isNotEmpty()) {
        ResultSection("בדיקה מעמיקה")
        session.deepNotesHe.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        session.freezeFrame.forEach { SampleBlock(it) }
    }
    if (session.modulesHe.isNotEmpty()) {
        ResultSection("ABS וכריות אוויר")
        Note("קריאה בלבד, ניסיוני: הכתובות של המודולים משתנות בין דגמים. \"לא נקרא\" אינו \"תקין\".")
        session.modulesHe.forEach { m ->
            InnerBlock {
                Text(m.titleHe, style = MaterialTheme.typography.titleSmall, color = if (m.warning) Tone.Amber else Tone.Paper)
                Note(m.statusHe)
                m.faultsHe.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tone.Paper) }
            }
        }
    }
    ResultSection("קודים")
    session.codesNoteHe?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    session.codes.forEach { CodeBlock(it) }
    ResultSection("נתוני חיישנים")
    session.samplesNoteHe?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    session.samples.forEach { SampleBlock(it) }
    ResultSection("מוכנות לטסט")
    session.monitorsNoteHe?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    if (session.monitors.isNotEmpty()) ReadinessList(session.monitors)
    ResultSection("יחידות")
    session.ecus.forEach { line -> UnitLine("${line.nameHe} · ${line.badgeHe}", line.detailHe) }
    ResultSection("מחוץ לסריקה · $GAP_BADGE_HE")
    session.gaps.forEach { line -> UnitLine("${line.nameHe} · ${line.badgeHe}", line.detailHe) }
}

/** A section heading inside the result, with a hairline above it to separate the groups. */
@Composable
private fun ResultSection(text: String) {
    HorizontalDivider(thickness = 1.dp, color = Tone.Line, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
    SectionLabel(text)
}

@Composable
private fun InnerBlock(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Tone.High)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) { content() }
}

@Composable
private fun UnitLine(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Tone.Paper)
        Note(detail)
    }
}

@Composable
private fun FindingBlock(finding: FindingView) {
    InnerBlock {
        Text(finding.titleHe, style = MaterialTheme.typography.titleSmall, color = Tone.Paper)
        Text("טענה: ${finding.claimHe}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(finding.summaryHe, style = MaterialTheme.typography.bodyMedium)
        Note("חומרה ${finding.severityLabelHe} · ${finding.confidenceLabelHe}")
        finding.evidence.forEach { line -> Note("· $line") }
        Text(finding.nextStepHe, style = MaterialTheme.typography.bodySmall, color = Tone.Paper)
    }
}

@Composable
private fun CodeBlock(code: CodeLine) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        CodeChip(code.code)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${code.statusHe} · ${code.ecuHe}", style = MaterialTheme.typography.titleSmall, color = Tone.Paper)
            Note(code.descriptionHe)
        }
    }
}

@Composable
private fun SampleBlock(sample: SampleLine) {
    val value = if (sample.unit.isBlank()) sample.valueHe else "${sample.valueHe} ${sample.unit}"
    Text("${sample.nameHe}: $value · ${sample.ecuHe} · ${sample.qualityHe}", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun LabFault(id: FaultId, label: String, ui: DoctorUi, vm: DoctorViewModel) {
    QuietAction(label, enabled = true, selected = id in ui.activeFaults) { vm.fault(id) }
}

@Composable
private fun AdapterPanel(ui: DoctorUi, vm: DoctorViewModel) {
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("מתאם")
            Text(ui.sourceLabelHe, style = MaterialTheme.typography.titleMedium, color = Tone.Paper)
            if (vm.picker == PickerMode.NONE) {
                QuietAction("בחר מתאם", enabled = ui.canChangeSource, selected = false) {
                    vm.openPicker(PickerMode.BLUETOOTH)
                }
                return@Column
            }
            PairRow(
                left = {
                    QuietAction("בלוטות׳", enabled = true, selected = vm.picker == PickerMode.BLUETOOTH) {
                        vm.openPicker(PickerMode.BLUETOOTH)
                    }
                },
                right = {
                    QuietAction("Wi-Fi", enabled = true, selected = vm.picker == PickerMode.WIFI) {
                        vm.openPicker(PickerMode.WIFI)
                    }
                },
            )
            vm.pickerNoticeHe?.let { Note(it) }
            when (vm.picker) {
                PickerMode.BLUETOOTH -> {
                    Note("מתאם בלוטות׳ רגיל מצמדים קודם בהגדרות הטלפון. מתאם BLE מופיע בחיפוש.")
                    vm.devices.forEach { device ->
                        val kind = if (device.ble) "BLE" else "Bluetooth"
                        val paired = if (device.paired) " · מצומד" else ""
                        QuietAction("${device.name} ($kind$paired)", enabled = true, selected = false) {
                            vm.useDevice(device)
                        }
                    }
                    PairRow(
                        left = { QuietAction("רענן מצומדים", enabled = true, selected = false) { vm.refreshPaired() } },
                        right = {
                            QuietAction(
                                if (vm.scanningBle) "מחפש…" else "חפש BLE",
                                enabled = !vm.scanningBle,
                                selected = false,
                            ) { vm.scanBle() }
                        },
                    )
                }
                PickerMode.WIFI -> {
                    Note("מתחברים קודם ברשת ה-Wi-Fi של המתאם (בדרך כלל WiFi_OBDII או V-LINK), ואז חוזרים לכאן.")
                    Field("כתובת המתאם", vm.wifiHost, ltr = true) { vm.wifiHost = it }
                    Field("פורט", vm.wifiPort, number = true, ltr = true) { vm.wifiPort = it }
                    PrimaryAction("השתמש במתאם Wi-Fi", enabled = true) { vm.useWifi() }
                }
                PickerMode.NONE -> Unit
            }
            QuietAction("רכב מדומה (תרגול)", enabled = true, selected = !ui.live) { vm.useSimulator() }
            QuietAction("סגור", enabled = true, selected = false) { vm.closePicker() }
        }
    }
}

@Composable
private fun LiveTips() {
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("לפני סריקה ברכב")
            Text("1. מחברים את המתאם לשקע ה-OBD (בדרך כלל מתחת להגה).", style = MaterialTheme.typography.bodyMedium)
            Text("2. מעבירים הצתה למצב ON או מתניעים. במצב כבוי הרכב לא עונה.", style = MaterialTheme.typography.bodyMedium)
            Text("3. לוחצים ״התחבר״ ואז ״סריקה מהירה״. החיבור הראשון יכול לקחת עד 15 שניות.", style = MaterialTheme.typography.bodyMedium)
            Note("נקראים קודי מנוע ופליטה לפי תקן OBD-II. ABS, כריות אוויר ומרכב דורשים פרוטוקול יצרן ולא נסרקים.")
        }
    }
}

/**
 * Two steps before any code is cleared. The bench then still checks, on the car,
 * that the engine is off and the vehicle is still. Nothing here can flash or code.
 */
@Composable
private fun ClearDialogs(vm: DoctorViewModel) {
    when (vm.clearStep) {
        ClearStep.NONE -> Unit
        ClearStep.WARN -> AlertDialog(
            onDismissRequest = { vm.cancelClear() },
            title = { Text("מחיקה לא מתקנת תקלה") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("מחיקה מכבה את נורת התקלה ומוחקת את הקודים השמורים והממתינים. היא לא מתקנת דבר.")
                    Text("אם התקלה אמיתית, הקוד יחזור, לפעמים כבר בנסיעה הבאה.")
                    Text("הסריקה הנוכחית כבר שמורה בהיסטוריה, כך שהקודים לא ילכו לאיבוד.")
                }
            },
            confirmButton = { TextButton(onClick = { vm.clearNext() }) { Text("הבנתי, המשך") } },
            dismissButton = { TextButton(onClick = { vm.cancelClear() }) { Text("ביטול") } },
        )
        ClearStep.CONFIRM -> AlertDialog(
            onDismissRequest = { vm.cancelClear() },
            title = { Text("לפני שמוחקים") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("מוכנות המערכות תתאפס. רכב שנמחקו לו קודים ממש לפני טסט עלול להיכשל בו עד שהמערכות ייבדקו מחדש, בדרך כלל אחרי כמה נסיעות.")
                    Text("המנוע צריך להיות כבוי וההצתה במצב ON. הרכב עומד.")
                    Text("קודים קבועים (Permanent) לא נמחקים ממתאם. רק הרכב מוחק אותם אחרי תיקון.")
                    Text("אחרי המחיקה תרוץ סריקת אימות אוטומטית.")
                }
            },
            confirmButton = { TextButton(onClick = { vm.confirmClear() }) { Text("מחק קודים") } },
            dismissButton = { TextButton(onClick = { vm.cancelClear() }) { Text("ביטול") } },
        )
    }
}

/** The installed version as Android knows it, e.g. "0.3.0 (12)". Never hard-coded. */
@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        "${info.versionName} ($code)"
    }.getOrDefault("לא ידוע")
}

/**
 * Before / after a garage visit. Right after a scan the owner can save it as the "before"
 * picture with the garage's plan; after the visit, a new scan closes it and the app
 * compares the two. The comparison itself is computed in core, not here.
 */
@Composable
private fun VisitActions(ui: DoctorUi, vm: DoctorViewModel) {
    ui.openVisit?.let { open ->
        if (!ui.canFinishVisit && vm.visitForm == VisitForm.NONE) {
            Panel {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("ביקור פתוח במוסך")
                    Text(open.titleHe, style = MaterialTheme.typography.titleSmall, color = Tone.Paper)
                    Note("אחרי היציאה מהמוסך: מתחברים, מריצים סריקה מהירה, וסוגרים את הביקור כדי להשוות.")
                }
            }
        }
    }
    when (vm.visitForm) {
        VisitForm.NONE -> {
            if (ui.canStartVisit) {
                QuietAction("לפני מוסך: שמור את הסריקה הזו כ״לפני״…", enabled = true, selected = false) {
                    vm.openVisitForm(VisitForm.START)
                }
            }
            if (ui.canFinishVisit) {
                PrimaryAction("אחרי מוסך: סגור ביקור והשווה", enabled = true) { vm.openVisitForm(VisitForm.FINISH) }
            }
        }
        VisitForm.START -> Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("לפני הכניסה למוסך")
                Note("הסריקה שבוצעה עכשיו תישמר כנקודת ההשוואה. כותבים מה המוסך אמר שיעשה, במילים שלכם.")
                Field("שם המוסך או נותן השירות", vm.garageName) { vm.garageName = it }
                Field("מה מתוכנן לעשות ברכב", vm.plannedWork) { vm.plannedWork = it }
                PairRow(
                    left = { PrimaryAction("פתח ביקור", enabled = ui.busyHe == null) { vm.submitVisit() } },
                    right = { QuietAction("ביטול", enabled = true, selected = false) { vm.cancelVisitForm() } },
                )
            }
        }
        VisitForm.FINISH -> Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("אחרי היציאה מהמוסך")
                ui.openVisit?.let { Note("מתוכנן היה: ${it.plannedWorkHe}") }
                Field("מה המוסך אמר שנעשה (לא חובה)", vm.reportedWork) { vm.reportedWork = it }
                QuietAction(
                    if (vm.visitAsService) "✓ לרשום גם כטיפול בהיסטוריה" else "לרשום גם כטיפול בהיסטוריה",
                    enabled = true,
                    selected = vm.visitAsService,
                ) { vm.visitAsService = !vm.visitAsService }
                if (vm.visitAsService) {
                    FieldCaption("סוג הטיפול")
                    ServiceKind.entries.forEach { kind ->
                        QuietAction(kind.labelHe, enabled = true, selected = vm.visitServiceKind == kind) { vm.visitServiceKind = kind }
                    }
                    val unit = ui.care?.intervalUnit ?: IntervalUnit.KM
                    Field("${unit.labelHe} עכשיו (לא חובה)", vm.visitReadingText, number = true, ltr = true) { vm.visitReadingText = it }
                    Note("הטיפול יירשם בתאריך של היום, עם שם המוסך, ויקושר להשוואת לפני/אחרי. התזכורות יתחילו להיספר ממנו.")
                }
                PairRow(
                    left = { PrimaryAction("סגור והשווה", enabled = ui.busyHe == null) { vm.submitVisit() } },
                    right = { QuietAction("ביטול", enabled = true, selected = false) { vm.cancelVisitForm() } },
                )
            }
        }
    }
}

@Composable
private fun VisitCard(visit: VisitView, vm: DoctorViewModel) {
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(visit.titleHe, style = MaterialTheme.typography.titleSmall, color = Tone.Paper)
            if (visit.warning) Banner(visit.statusHe) else Note(visit.statusHe)
            StateLine("מתוכנן", visit.plannedWorkHe)
            visit.reportedWorkHe?.let { StateLine("לפי המוסך נעשה", it) }
            visit.serviceHe?.let { Note(it) }
            visit.summaryHe.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tone.Soft) }
            if (visit.newCodes.isNotEmpty()) {
                FieldCaption("קודים חדשים אחרי המוסך")
                visit.newCodes.forEach { change ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CodeChip(change.code)
                        Text(change.descriptionHe, style = MaterialTheme.typography.bodySmall, color = Tone.Soft)
                    }
                }
            }
            if (visit.goneCodes.isNotEmpty()) {
                FieldCaption("קודים שלא מופיעים יותר")
                visit.goneCodes.forEach { change ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CodeChip(change.code)
                        Text(change.descriptionHe, style = MaterialTheme.typography.bodySmall, color = Tone.Muted)
                    }
                }
            }
            visit.voltageHe?.let { Note(it) }
            PairRow(
                left = { QuietAction("סריקת לפני", enabled = true, selected = false) { vm.openSession(visit.beforeSessionId) } },
                right = {
                    QuietAction("סריקת אחרי", enabled = visit.afterSessionId != null, selected = false) {
                        visit.afterSessionId?.let(vm::openSession)
                    }
                },
            )
        }
    }
}

/** Reminders for the selected car, and the form that sets them. */
@Composable
private fun CarePanel(ui: DoctorUi, vm: DoctorViewModel) {
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("תזכורות")
            if (ui.reminders.isEmpty() && !vm.careOpen) {
                Note("אין כרגע מה להזכיר.")
            }
            ui.reminders.forEach { reminder ->
                InnerBlock {
                    Text(
                        reminder.titleHe,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (reminder.urgent) Tone.Amber else Tone.Paper,
                    )
                    Note(reminder.detailHe)
                }
            }
            if (!vm.careOpen) {
                val plan = ui.care
                val summary = buildString {
                    append(if ((plan?.checkEveryDays ?: 0) > 0) "סריקה כל ${plan?.checkEveryDays} ימים" else "בלי תזכורת סריקה")
                    val next = plan?.let { p ->
                        listOfNotNull(
                            p.nextServiceReading()?.let { "$it ${p.intervalUnit.shortHe}" },
                            p.nextServiceDate()?.let { "%02d/%02d/%d".format(it.dayOfMonth, it.monthValue, it.year) },
                        )
                    }.orEmpty()
                    append(if (next.isEmpty()) " · פרטי הטיפול האחרון לא הוזנו" else " · טיפול הבא: ${next.joinToString(" או ")}")
                    if (plan?.lastServiceDate != null) append(" · אחרון: ${plan.serviceKind.labelHe}")
                }
                Note(summary)
                QuietAction("הגדרת תזכורות וטיפולים…", enabled = ui.hasVehicle, selected = false) { vm.openCareForm() }
            } else {
                CareForm(ui, vm)
            }
        }
    }
}

@Composable
private fun CareForm(ui: DoctorUi, vm: DoctorViewModel) {
    val unit = vm.intervalUnit
    Note("התזכורות מופיעות כשפותחים את האפליקציה.")
    FieldCaption("סוג הטיפול")
    ServiceKind.entries.forEach { kind ->
        QuietAction(kind.labelHe, enabled = true, selected = vm.serviceKind == kind) { vm.serviceKind = kind }
    }
    if (vm.serviceKind == ServiceKind.BASELINE) {
        Note("טיפול יישור קו: טיפול מקיף שמאפס את היסטוריית הטיפולים, בדרך כלל אחרי קניית רכב משומש. הטיפולים הבאים נספרים ממנו.")
    }
    ServiceDateField(vm.lastServiceText) { vm.lastServiceText = it }
    Note("כל טיפול שנשמר נכנס להיסטוריית הטיפולים. אפשר להזין גם טיפולים ישנים, והתזכורות נספרות מהאחרון.")
    Field("מוסך / נותן שירות (לא חובה)", vm.serviceGarage) { vm.serviceGarage = it }
    FieldCaption("מרווח בין טיפולים לפי")
    Segmented(
        options = IntervalUnit.entries,
        selected = unit,
        label = { it.labelHe },
        onSelect = { vm.chooseIntervalUnit(it) },
    )
    Field("טיפול כל כמה ${unit.labelHe}", vm.intervalText, number = true, ltr = true) { vm.intervalText = it }
    Field("${unit.labelHe} בטיפול האחרון", vm.lastReadingText, number = true, ltr = true) { vm.lastReadingText = it }
    val readHint = ui.currentReading?.let { "הקריאה הידועה כרגע: $it. בדיקה מעמיקה מנסה לקרוא מהרכב." }
        ?: "רוב הרכבים לא מדווחים ${unit.labelHe} דרך OBD. בדיקה מעמיקה מנסה; אם לא, מעדכנים כאן."
    Field("${unit.labelHe} עכשיו", vm.currentReadingText, number = true, ltr = true, supporting = readHint) {
        vm.currentReadingText = it
    }
    Field("וגם כל כמה חודשים, מה שמגיע קודם (0 = בלי)", vm.serviceMonthsText, number = true, ltr = true) { vm.serviceMonthsText = it }
    Field("סריקה תקופתית כל כמה ימים (0 = בלי)", vm.checkEveryText, number = true, ltr = true) { vm.checkEveryText = it }
    Field("הערה לטיפול (למשל: שמן ופילטרים)", vm.serviceNote) { vm.serviceNote = it }
    PairRow(
        left = { PrimaryAction("שמור", enabled = ui.busyHe == null) { vm.submitCare() } },
        right = { QuietAction("ביטול", enabled = true, selected = false) { vm.cancelCareForm() } },
    )
}

/** Shows the chosen service date and opens a calendar. Future dates cannot be picked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceDateField(value: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val parsed = remember(value) { runCatching { java.time.LocalDate.parse(value) }.getOrNull() }
    FieldCaption("תאריך הטיפול האחרון / הנוכחי")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ControlShape)
            .background(Tone.Sunken)
            .border(1.dp, Tone.LineStrong, ControlShape)
            .clickable { open = true }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(DoctorIcons.Calendar, contentDescription = null, tint = Tone.AccentText)
        Spacer(Modifier.width(12.dp))
        Text(
            parsed?.let { "%02d/%02d/%d".format(it.dayOfMonth, it.monthValue, it.year) } ?: "בחרו תאריך בלוח השנה",
            style = MaterialTheme.typography.bodyLarge,
            color = if (parsed != null) Tone.Paper else Tone.Muted,
            modifier = Modifier.weight(1f),
        )
        if (parsed != null) {
            TextButton(onClick = { onPick("") }) { Text("נקה", color = Tone.Muted) }
        }
    }
    if (open) {
        val todayMs = remember { System.currentTimeMillis() }
        val initial = parsed?.atStartOfDay(java.time.ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial ?: todayMs,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayMs
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        onPick(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString())
                    }
                    open = false
                }) { Text("סיום", color = Tone.AccentText) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("ביטול", color = Tone.Soft) } },
        ) {
            DatePicker(state = state)
        }
    }
}

/** Long-term view: live values over time and codes that keep coming back. */
@Composable
private fun TrendsPanel(report: TrendReport) {
    if (report.scansUsed == 0) return
    SectionLabel("מגמות לאורך זמן")
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Note(report.noteHe)
            report.recurring.forEach { code ->
                InnerBlock {
                    Text(
                        "${code.code} · הופיע ב-${code.sessions} סריקות",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (code.returnedAfterClear) Tone.Amber else Tone.Paper,
                    )
                    Note(code.descriptionHe)
                    if (code.returnedAfterClear) Note("חזר אחרי מחיקה: התקלה עצמה לא תוקנה.")
                }
            }
            report.pids.forEach { trend ->
                val arrow = when (trend.direction) {
                    TrendDirection.UP -> "עולה"
                    TrendDirection.DOWN -> "יורד"
                    TrendDirection.STEADY -> "יציב"
                }
                InnerBlock {
                    Text(
                        "${trend.nameHe}: ${trend.first} → ${trend.last} ${trend.unit} · $arrow",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (trend.warningHe != null) Tone.Amber else Tone.Paper,
                    )
                    Note(trend.warningHe ?: "${trend.readings} קריאות. אין שינוי שמצריך בדיקה.")
                }
            }
        }
    }
}

/** Lets the user send the adapter traffic (for example to whoever is helping debug a real car). */
@Composable
private fun WireLogPanel(vm: DoctorViewModel) {
    val context = LocalContext.current
    val version = appVersion()
    var emptyNote by remember { mutableStateOf(false) }
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("יומן תקשורת")
            Note("מה נשלח למתאם ומה הרכב ענה, ב-600 השורות האחרונות. נשמר רק בזיכרון עד סגירת האפליקציה. כולל את ה-VIN אם נקרא.")
            if (emptyNote) Note("אין עדיין יומן. מתחברים למתאם אמיתי ומריצים סריקה, ואז משתפים.")
            QuietAction("שתף יומן תקשורת…", enabled = true, selected = false) {
                val text = vm.wireLogText(version)
                if (text == null) {
                    emptyNote = true
                } else {
                    emptyNote = false
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "רופא רכב · יומן תקשורת")
                        putExtra(android.content.Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(android.content.Intent.createChooser(send, "שיתוף יומן"))
                }
            }
        }
    }
}

/** Every recorded service, newest first. Delete needs two taps. */
@Composable
private fun ServiceHistory(ui: DoctorUi, vm: DoctorViewModel) {
    SectionLabel("היסטוריית טיפולים")
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.services.forEach { record ->
                val date = runCatching { java.time.LocalDate.parse(record.date) }.getOrNull()
                    ?.let { "%02d/%02d/%d".format(it.dayOfMonth, it.monthValue, it.year) } ?: record.date
                InnerBlock {
                    Text("$date · ${record.kind.labelHe}", style = MaterialTheme.typography.titleSmall, color = Tone.Paper)
                    val details = listOfNotNull(
                        record.reading?.let { "%,d %s".format(it, record.unit.shortHe) },
                        record.garageName.takeIf { it.isNotBlank() },
                        record.noteHe.takeIf { it.isNotBlank() },
                    )
                    if (details.isNotEmpty()) Note(details.joinToString(" · "))
                    record.visitId?.let { id ->
                        val visit = ui.visits.firstOrNull { it.id == id }
                        Note(
                            when {
                                visit == null -> "נרשם מביקור לפני/אחרי שכבר לא שמור."
                                visit.warning -> "מקושר לביקור לפני/אחרי: יש הבדלים שכדאי לברר עם המוסך."
                                else -> "מקושר לביקור לפני/אחרי: לא נמצאו הבדלים חשודים."
                            },
                        )
                    }
                    ReceiptButton(record.id, hasReceipt = record.receiptFile != null, enabled = ui.busyHe == null, vm = vm)
                    val confirming = vm.confirmDeleteService == record.id
                    TextButton(onClick = { vm.removeService(record.id) }, enabled = ui.busyHe == null) {
                        Text(
                            if (confirming) "ללחוץ שוב כדי למחוק" else "מחק",
                            color = if (confirming) Tone.Danger else Tone.Muted,
                        )
                    }
                }
            }
        }
    }
}

private data class ServiceSearch(val labelHe: String, val query: String)

/**
 * Opens the phone's map app with a nearby search. No listings of our own and no paid
 * placement: results come from the map app.
 */
@Composable
private fun FindServicePanel(make: String) {
    val context = LocalContext.current
    val searches = buildList {
        add(ServiceSearch("מוסך", "מוסך"))
        if (make.isNotBlank()) add(ServiceSearch("מרכז שירות $make", "מרכז שירות $make"))
        add(ServiceSearch("מרכז שירות", "מרכז שירות רכב"))
        add(ServiceSearch("חשמלאי רכב", "חשמלאי רכב"))
        add(ServiceSearch("פנצ׳רייה וצמיגים", "פנצריה צמיגים"))
        add(ServiceSearch("פחחות וצבע", "פחחות וצבע רכב"))
        add(ServiceSearch("מיזוג אוויר לרכב", "מיזוג אוויר לרכב"))
        add(ServiceSearch("מכון רישוי (טסט)", "מכון רישוי רכב"))
        add(ServiceSearch("גרר", "גרר רכב"))
    }
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("חיפוש בעלי מקצוע בסביבה")
            Note("נפתח באפליקציית המפות שבטלפון. התוצאות שם; לפני ביקור: סריקה ופתיחת ביקור.")
            searches.chunked(2).forEach { pair ->
                PairRow(
                    left = { QuietAction(pair[0].labelHe, enabled = true, selected = false) { openMapSearch(context, pair[0].query) } },
                    right = {
                        pair.getOrNull(1)?.let { s ->
                            QuietAction(s.labelHe, enabled = true, selected = false) { openMapSearch(context, s.query) }
                        }
                    },
                )
            }
        }
    }
}

private fun openMapSearch(context: android.content.Context, query: String) {
    val geo = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(query)))
    try {
        context.startActivity(geo)
    } catch (_: android.content.ActivityNotFoundException) {
        val web = android.net.Uri.parse("https://www.google.com/maps/search/?api=1&query=" + android.net.Uri.encode(query))
        try {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, web))
        } catch (_: android.content.ActivityNotFoundException) {
            // No map app and no browser: nothing to open.
        }
    }
}

/** Picks a photo of the garage receipt and stores a private, shrunk copy. */
@Composable
private fun ReceiptButton(serviceId: String, hasReceipt: Boolean, enabled: Boolean, vm: DoctorViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val name = withContext(Dispatchers.IO) {
                    runCatching { Receipts.importPhoto(context, uri, serviceId) }.getOrNull()
                }
                if (name != null) vm.attachReceipt(serviceId, name) else vm.receiptFailed()
            }
        }
    }
    TextButton(
        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        enabled = enabled,
    ) {
        Text(
            if (hasReceipt) "✓ קבלה צורפה · החלפה" else "צירוף צילום קבלה מהמוסך",
            color = if (hasReceipt) Tone.AccentText else Tone.Soft,
        )
    }
}

/**
 * Sale report: issued only when the car is clean, all readiness monitors are complete and every
 * past fault has a receipt. Otherwise lists what is still missing.
 */
@Composable
private fun SaleReportPanel(ui: DoctorUi) {
    val check = ui.saleCheck ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = appVersion()
    var working by remember { mutableStateOf(false) }
    SectionLabel("דו״ח רכב למכירה")
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Note("לקונה של הרכב. מופק רק אחרי שהתקלות תוקנו, אין קודים, כל בדיקות המוכנות הושלמו, ולכל תקלה שהייתה יש צילום קבלה מהמוסך.")
            val report = check.report
            if (report == null) {
                Text("עוד אי אפשר להפיק:", style = MaterialTheme.typography.titleSmall, color = Tone.Amber)
                check.blockersHe.forEach { Note("• $it") }
            } else {
                Text("אפשר להפיק דו״ח.", style = MaterialTheme.typography.titleSmall, color = Tone.AccentText)
                PrimaryAction(if (working) "מכין PDF…" else "הפק ושתף דו״ח", enabled = !working && ui.busyHe == null) {
                    working = true
                    scope.launch {
                        val file = withContext(Dispatchers.IO) {
                            runCatching { SaleReportPdf.write(context, report, version) }.getOrNull()
                        }
                        working = false
                        if (file != null) {
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".reports", file)
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                putExtra(android.content.Intent.EXTRA_SUBJECT, "דו״ח רכב · ${report.vehicleHe}")
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(android.content.Intent.createChooser(send, "שיתוף הדו״ח"))
                        }
                    }
                }
            }
        }
    }
}
