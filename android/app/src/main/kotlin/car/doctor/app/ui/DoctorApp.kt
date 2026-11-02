package car.doctor.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
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

@Composable
private fun NavBar(vm: DoctorViewModel) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        val gap = 4.dp
        val share = (maxWidth - gap * 4) / 5
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem("בית", vm.screen == DoctorScreen.HOME, share) { vm.showHome() }
            NavItem("ספסל", vm.screen == DoctorScreen.BENCH, share) { vm.showBench() }
            NavItem("תוצאה", vm.screen == DoctorScreen.RESULT, share) { vm.showResult() }
            NavItem("היסטוריה", vm.screen == DoctorScreen.HISTORY, share) { vm.showHistory() }
            NavItem("הגדרות", vm.screen == DoctorScreen.SETTINGS, share) { vm.showSettings() }
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    selected: Boolean,
    minWidth: Dp,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier
            .widthIn(min = minWidth)
            .heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) scheme.primary else Color.Transparent,
        contentColor = if (selected) scheme.onPrimary else scheme.primary,
        border = if (selected) null else BorderStroke(1.dp, scheme.outline),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun HomePage(ui: DoctorUi, vm: DoctorViewModel) {
    Text("רופא רכב", fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "קודי תקלה, מוכנות ונתונים חיים דרך מתאם ELM327 (בלוטות׳ או Wi-Fi), או רכב מדומה לתרגול. מחיקת קודים עם הגנות; אין צריבה.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )
    ui.storageNoticeHe?.let { Banner(it) }
    ui.noticeHe?.let { Banner(it) }

    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("הרכבים במוסך")
            if (ui.vehicles.isEmpty()) {
                Text(
                    "אין עדיין פרופיל. הוסיפו רכב, ואז עברו לספסל כדי לבחור מתאם ולהריץ סריקה.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp,
                )
            }
            ui.vehicles.forEach { vehicle ->
                QuietAction(vehicle.titleHe, enabled = true, selected = vehicle.selected) {
                    vm.selectVehicle(vehicle.id)
                }
                Text(vehicle.metaHe, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
    }

    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("פרופיל חדש")
            Field("כינוי", vm.nickname) { vm.nickname = it }
            Field("יצרן", vm.make) { vm.make = it }
            Field("דגם", vm.model) { vm.model = it }
            Field("שנת ייצור", vm.yearText, number = true) { vm.yearText = it }
            Field("VIN, אם הוקלד ידנית", vm.vin) { vm.vin = it }
            Text("סוג דלק", fontWeight = FontWeight.Medium)
            Fuel.entries.forEach { option ->
                QuietAction(fuelLabel(option), enabled = true, selected = vm.fuel == option) {
                    vm.chooseFuel(option)
                }
            }
            PrimaryAction("שמור רכב", enabled = true) { vm.createVehicle() }
            QuietAction("הוסף קורולה לדוגמה", enabled = true, selected = false) { vm.addDemo() }
        }
    }
}

@Composable
private fun BenchPage(ui: DoctorUi, vm: DoctorViewModel) {
    Text("ספסל הסריקה", fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
    if (!ui.hasVehicle) {
        Banner("אין רכב נבחר. חוזרים לבית ובוחרים פרופיל.")
        QuietAction("לבית", enabled = true, selected = false) { vm.showHome() }
        return
    }
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("הרכב הנוכחי")
            Text(ui.nickname, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(ui.metaHe, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(ui.vinNoteHe, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(8.dp))
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
            Text("מצב נוכחי: ${ui.ignitionLabelHe}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            ui.moves.forEach { move ->
                QuietAction(move.labelHe, enabled = true, selected = false) { vm.ignite(move.to) }
            }
            Text("תנאי נסיעה · ${ui.postureLabelHe}", fontWeight = FontWeight.Medium)
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
            Text(
                "ההזרקה לא יוצרת ממצא. רק סריקה שחוזרת עם נתונים מפוענחים יכולה ליצור ממצא.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            LabFault(FaultId.MISFIRE, "הזרק החטאה", ui, vm)
            LabFault(FaultId.LOW_BATTERY, "הזרק מתח נמוך", ui, vm)
            LabFault(FaultId.WHEEL_SPEED_MISMATCH, "הזרק הפרש גלגלים", ui, vm)
        }
    }
}

@Composable
private fun ResultPage(ui: DoctorUi) {
    Text("תוצאת סריקה", fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
    val session = ui.open
    if (session == null) {
        Panel {
            Text(
                "אין סריקה שמורה לרכב הזה. סריקה מהירה בספסל היא מה שיוצר תוצאה.",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
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
    Text("היסטוריה", fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
    Text(
        "הסריקות שמורות בקובץ מקומי של הרכב הנבחר. סגירת האפליקציה לא מוחקת אותן.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
    if (ui.history.isEmpty()) {
        Panel {
            Text(
                "אין עדיין סריקות שמורות.",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    ui.history.forEach { session ->
        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(session.headlineHe, fontWeight = FontWeight.Medium, lineHeight = 22.sp)
                Text(
                    "${session.finishedAt} · ${session.ignitionLabelHe}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
                QuietAction("פתח סריקה", enabled = true, selected = session.id == ui.open?.id) {
                    vm.openSession(session.id)
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(ui: DoctorUi, vm: DoctorViewModel) {
    Text("הגדרות", fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
    ui.storageNoticeHe?.let { Banner(it) }
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("מאגר קודי תקלה")
            Text(vm.dtcStatusHe, lineHeight = 20.sp)
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
            StateLine("גרסה", appVersion())
            StateLine("מתאם", ui.sourceLabelHe)
            StateLine("כתיבה ליחידה", "מחיקת קודים בלבד")
            StateLine("צריבה / שיפור כוח", "חסומה")
            Text(
                "רופא רכב קורא נתונים, ומוחק קודי תקלה רק אחרי שני אישורים, כשהמנוע כבוי והרכב עומד. אין צריבה, אין שינוי תוכנה ביחידות, אין גישת אבטחה ואין ענן.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
            )
        }
    }
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("שמירה")
            Text(
                "הפרופילים והסריקות נשמרים בקובץ garage.json בתיקיית האפליקציה. זה לא זיכרון זמני.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
            )
            Text(
                "אם הקובץ חסר, המוסך נפתח ריק. אם הקובץ פגום, הוא לא מפוענח כסריקה תקינה והמוסך מאופס.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
            )
        }
    }
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("איך קוראים תוצאה")
            Text("נמצאה תקלה: קוד או חריגת מדידה שהיחידה החזירה.", lineHeight = 20.sp)
            Text("חשד: ממצא שדורש בדיקה נוספת, בלי שם של חלק להחלפה.", lineHeight = 20.sp)
            Text("לא נמצאו ממצאים לפי הנתונים שנבדקו. זה לא אישור תקינות.", lineHeight = 20.sp)
            Text("לא נבדק: היחידה לא נשאלה.", lineHeight = 20.sp)
            Text("לא בסריקה: מערכת שאינה חלק מהסריקה הזו.", lineHeight = 20.sp)
            Text("שגיאת תקשורת: הניסיון נכשל. זו לא תוצאה נקיה.", lineHeight = 20.sp)
        }
    }
}

@Composable
private fun SessionBody(session: SessionView) {
    Text(session.headlineHe, fontWeight = FontWeight.Medium, lineHeight = 22.sp)
    session.clearNoteHe?.let {
        Panel {
            Text(it, modifier = Modifier.padding(12.dp), lineHeight = 20.sp)
        }
    }
    session.protocolHe?.let {
        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    }
    Text(session.adapterLabelHe, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
    Text(
        "${session.finishedAt} · הצתה ${session.ignitionLabelHe}",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
    )
    SectionLabel("ממצאים")
    if (session.findings.isEmpty()) {
        Text("אין ממצאים על הנתונים שנקראו בסריקה הזו.", lineHeight = 20.sp)
    } else {
        session.findings.forEach { FindingBlock(it) }
    }
    SectionLabel("קודים")
    session.codesNoteHe?.let { Text(it, lineHeight = 20.sp) }
    session.codes.forEach { CodeBlock(it) }
    SectionLabel("נתוני חיישנים")
    session.samplesNoteHe?.let { Text(it, lineHeight = 20.sp) }
    session.samples.forEach { SampleBlock(it) }
    SectionLabel("מוכנות")
    session.monitorsNoteHe?.let { Text(it, lineHeight = 20.sp) }
    session.monitors.forEach { MonitorBlock(it) }
    SectionLabel("יחידות")
    session.ecus.forEach { line ->
        Text("${line.nameHe} · ${line.badgeHe}", fontWeight = FontWeight.Medium)
        Text(line.detailHe, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
    }
    SectionLabel("מחוץ לסריקה · $GAP_BADGE_HE")
    session.gaps.forEach { line ->
        Text("${line.nameHe} · ${line.badgeHe}", fontWeight = FontWeight.Medium)
        Text(line.detailHe, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun FindingBlock(finding: FindingView) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(finding.titleHe, fontWeight = FontWeight.SemiBold)
        Text("טענה: ${finding.claimHe}", fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Text(finding.summaryHe, lineHeight = 20.sp, fontSize = 14.sp)
        Text(
            "חומרה ${finding.severityLabelHe} · ${finding.confidenceLabelHe}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        finding.evidence.forEach { line ->
            Text("· $line", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(finding.nextStepHe, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun CodeBlock(code: CodeLine) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${code.code} · ${code.statusHe} · ${code.ecuHe}", fontWeight = FontWeight.SemiBold)
        Text(code.descriptionHe, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun SampleBlock(sample: SampleLine) {
    val value = if (sample.unit.isBlank()) sample.valueHe else "${sample.valueHe} ${sample.unit}"
    Text("${sample.nameHe}: $value · ${sample.ecuHe} · ${sample.qualityHe}", fontSize = 14.sp, lineHeight = 20.sp)
}

@Composable
private fun MonitorBlock(monitor: MonitorLine) {
    Text("${monitor.nameHe} · ${monitor.statusHe}", fontSize = 14.sp)
}

@Composable
private fun StateLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Banner(text: String) {
    Panel {
        Text(
            text,
            modifier = Modifier.padding(14.dp),
            color = MaterialTheme.colorScheme.error,
            lineHeight = 20.sp,
        )
    }
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
            Text(ui.sourceLabelHe, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
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
                    Field("כתובת המתאם", vm.wifiHost) { vm.wifiHost = it }
                    Field("פורט", vm.wifiPort, number = true) { vm.wifiPort = it }
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("לפני סריקה ברכב")
            Text("1. מחברים את המתאם לשקע ה-OBD (בדרך כלל מתחת להגה).", lineHeight = 20.sp)
            Text("2. מעבירים הצתה למצב ON או מתניעים. במצב כבוי הרכב לא עונה.", lineHeight = 20.sp)
            Text("3. לוחצים ״התחבר״ ואז ״סריקה מהירה״. החיבור הראשון יכול לקחת עד 15 שניות.", lineHeight = 20.sp)
            Note("נקראים קודי מנוע ופליטה לפי תקן OBD-II. ABS, כריות אוויר ומרכב דורשים פרוטוקול יצרן ולא נסרקים.")
        }
    }
}

@Composable
private fun Busy(text: String) {
    Panel {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.height(22.dp).widthIn(max = 22.dp), strokeWidth = 2.dp)
            Text(text, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
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
                    Text("מחיקה מכבה את נורת התקלה ומוחקת את הקודים השמורים והממתינים. היא לא מתקנת דבר.", lineHeight = 20.sp)
                    Text("אם התקלה אמיתית, הקוד יחזור, לפעמים כבר בנסיעה הבאה.", lineHeight = 20.sp)
                    Text("הסריקה הנוכחית כבר שמורה בהיסטוריה, כך שהקודים לא ילכו לאיבוד.", lineHeight = 20.sp)
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
                    Text("מוכנות המערכות תתאפס. רכב שנמחקו לו קודים ממש לפני טסט עלול להיכשל בו עד שהמערכות ייבדקו מחדש, בדרך כלל אחרי כמה נסיעות.", lineHeight = 20.sp)
                    Text("המנוע צריך להיות כבוי וההצתה במצב ON. הרכב עומד.", lineHeight = 20.sp)
                    Text("קודים קבועים (Permanent) לא נמחקים ממתאם. רק הרכב מוחק אותם אחרי תיקון.", lineHeight = 20.sp)
                    Text("אחרי המחיקה תרוץ סריקת אימות אוטומטית.", lineHeight = 20.sp)
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
