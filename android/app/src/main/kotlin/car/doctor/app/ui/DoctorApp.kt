package car.doctor.app.ui

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import car.doctor.app.ClearStep
import car.doctor.app.DoctorScreen
import car.doctor.app.DoctorUi
import car.doctor.app.DoctorViewModel
import car.doctor.app.PickerMode
import car.doctor.core.catalog.GAP_BADGE_HE
import car.doctor.core.catalog.fuelLabel
import car.doctor.core.model.FaultId
import car.doctor.core.model.Fuel
import car.doctor.core.model.Posture
import car.doctor.core.present.CodeLine
import car.doctor.core.present.FindingView
import car.doctor.core.present.MonitorLine
import car.doctor.core.present.SampleLine
import car.doctor.core.present.SessionView

@Composable
fun DoctorApp(vm: DoctorViewModel) {
    val ui = vm.ui
    // Edge to edge: the page respects the status bar, cutouts and the keyboard; the nav bar
    // paints under the system navigation area and pads its own content above it.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
}

/**
 * Five destinations, always visible, equal width, in RTL order (בית on the right).
 * Icon above a short one-line label; the active one gets a brass pill and brass text.
 */
@Composable
private fun NavBar(vm: DoctorViewModel) {
    Surface(color = Tone.High, modifier = Modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(thickness = 1.dp, color = Tone.Line)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                    .padding(horizontal = 4.dp, vertical = 6.dp)
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
}

@Composable
private fun RowScope.NavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) Tone.Brass else Tone.Muted
    Column(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 56.dp)
                .fillMaxWidth()
                .height(30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(if (selected) Tone.BrassTint else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
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
                color = if (selected) Tone.Brass else Tone.Muted,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HomePage(ui: DoctorUi, vm: DoctorViewModel) {
    PageTitle(
        "רופא רכב",
        "קודי תקלה, מוכנות ונתונים חיים דרך מתאם ELM327 (בלוטות׳ או Wi-Fi), או רכב מדומה לתרגול. מחיקת קודים עם הגנות; אין צריבה.",
    )
    ui.storageNoticeHe?.let { Banner(it) }
    ui.noticeHe?.let { Banner(it) }

    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("הרכבים במוסך")
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
    }

    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("פרופיל חדש")
            Field("כינוי", vm.nickname) { vm.nickname = it }
            Field("יצרן", vm.make) { vm.make = it }
            Field("דגם", vm.model) { vm.model = it }
            Field("שנת ייצור", vm.yearText, number = true, ltr = true) { vm.yearText = it }
            Field(
                "VIN",
                vm.vin,
                mono = true,
                capitalizeAll = true,
                supporting = "לא חובה · מוקלד ידנית, לא מאומת מול הרכב",
            ) { vm.vin = it }
            FieldCaption("סוג דלק")
            ChoiceGrid(
                options = Fuel.entries,
                selected = vm.fuel,
                label = { fuelLabel(it) },
            ) { vm.chooseFuel(it) }
            Spacer(Modifier.height(4.dp))
            PrimaryAction("שמור רכב", enabled = true) { vm.createVehicle() }
            QuietAction("הוסף קורולה לדוגמה", enabled = true, selected = false) { vm.addDemo() }
        }
    }
}

@Composable
private fun BenchPage(ui: DoctorUi, vm: DoctorViewModel) {
    PageTitle("ספסל הסריקה")
    if (!ui.hasVehicle) {
        Banner("אין רכב נבחר. חוזרים לבית ובוחרים פרופיל.")
        QuietAction("לבית", enabled = true, selected = false) { vm.showHome() }
        return
    }
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("הרכב הנוכחי")
            Text(ui.nickname, style = MaterialTheme.typography.titleLarge, color = Tone.Paper)
            Text(ui.metaHe, style = MaterialTheme.typography.bodyMedium, color = Tone.Muted)
            Note(ui.vinNoteHe)
            HorizontalDivider(thickness = 1.dp, color = Tone.Line, modifier = Modifier.padding(vertical = 6.dp))
            if (!ui.live) StateLine("הצתה", ui.ignitionLabelHe)
            StateLine("חיבור OBD", ui.sessionLabelHe)
            ui.protocolHe?.let { StateLine("פרוטוקול", it) }
        }
    }
    AdapterPanel(ui, vm)
    ui.busyHe?.let { Busy(it) }
    ui.noticeHe?.let { Banner(it) }
    ui.errorHe?.let { Banner(it) }
    PairRow(
        left = { PrimaryAction("התחבר", ui.canConnect) { vm.connect() } },
        right = { PrimaryAction("סריקה מהירה", ui.canScan) { vm.scan() } },
    )
    QuietAction("נתק", ui.canDisconnect, false) { vm.disconnect() }
    if (ui.canClear) {
        QuietAction("מחיקת קודי תקלה…", enabled = true, selected = false) { vm.startClear() }
    }
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
    PageTitle("תוצאת סריקה")
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
    ResultSection("קודים")
    session.codesNoteHe?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    session.codes.forEach { CodeBlock(it) }
    ResultSection("נתוני חיישנים")
    session.samplesNoteHe?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    session.samples.forEach { SampleBlock(it) }
    ResultSection("מוכנות")
    session.monitorsNoteHe?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    session.monitors.forEach { MonitorBlock(it) }
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
private fun MonitorBlock(monitor: MonitorLine) {
    Text("${monitor.nameHe} · ${monitor.statusHe}", style = MaterialTheme.typography.bodyMedium)
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

@Composable
private fun Busy(text: String) {
    Panel {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Tone.Brass,
                trackColor = Tone.Line,
            )
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Tone.Paper)
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
