package com.example.hyundai_hackathon.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hyundai_hackathon.CoachRuntime
import com.example.hyundai_hackathon.CoachState
import com.example.hyundai_hackathon.domain.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val Ink = Color(0xFF101B1E)
private val CardColor = Color(0xFF1A292D)
private val Line = Color(0xFF304348)
private val Mint = Color(0xFFA9E8CD)
private val White = Color(0xFFF0F6F3)
private val Muted = Color(0xFFA2B6B3)
private val Amber = Color(0xFFF3C991)
private val Rose = Color(0xFFF3AAA1)
private val Shape = RoundedCornerShape(24.dp)

@Composable
fun CoachApp(runtime: CoachRuntime, onStart: (DriveSource) -> Unit, onRequestPermission: () -> Unit, onExport: () -> Unit) {
    val s by runtime.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf("drive") }
    var driveId by rememberSaveable { mutableStateOf<String?>(null) }
    var eventId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var clear by remember { mutableStateOf(false) }
    val restricted = !s.canInteract
    val drive = s.history.find { it.id == driveId }
    val event = drive?.events?.find { it.id == eventId }
    LaunchedEffect(s.vehicle.gear) { if (s.vehicle.gear == 4) onRequestPermission() }
    LaunchedEffect(restricted) { if (restricted) { tab = "drive"; driveId = null; eventId = null; deleteId = null; clear = false } }
    BackHandler(enabled = restricted || driveId != null || tab != "drive") {
        if (!restricted) when { eventId != null -> eventId = null; driveId != null -> driveId = null; else -> tab = "drive" }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Ink, background = Ink, surface = CardColor,
        onBackground = White, onSurface = White, secondary = Amber, secondaryContainer = Line, onSecondaryContainer = Mint, outline = Line)) {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp)) {
                Header(s)
                Box(Modifier.weight(1f)) {
                    when {
                        s.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Mint) }
                        restricted -> DrivingView(s, true) {}
                        event != null -> EventDetail(drive!!, event, { eventId = null }) { runtime.review(drive.id, event.id, it) }
                        drive != null -> DriveDetail(drive, { driveId = null }, { eventId = it },
                            { value, author -> runtime.feedback(drive.id, value, author) }, { deleteId = drive.id })
                        tab == "history" -> History(s) { driveId = it }
                        tab == "insights" -> Insights(s, onExport) { clear = true }
                        s.active != null -> DrivingView(s, false) { runtime.finish(); tab = "history" }
                        else -> Home(s, runtime::chooseMode, onStart, { tab = "history" }, { driveId = it }, runtime::reconnect)
                    }
                }
                if (!restricted && driveId == null) Navigation(tab) { tab = it } else Spacer(Modifier.height(16.dp))
            }
        }
        if (s.notice != null && !restricted) AlertDialog(onDismissRequest = runtime::dismissNotice,
            title = { Text("알림") }, text = { Text(s.notice!!) }, confirmButton = { TextButton(onClick = runtime::dismissNotice) { Text("확인") } })
        if (deleteId != null && !restricted) AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("이 드라이브를 삭제할까요?") },
            text = { Text("이 기기에 저장된 해당 주행 기록과 이벤트가 삭제됩니다.") },
            confirmButton = { TextButton(onClick = { runtime.deleteDrive(deleteId!!); deleteId = null; driveId = null; eventId = null }) { Text("삭제", color = Rose) } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("취소") } })
        if (clear && !restricted) AlertDialog(onDismissRequest = { clear = false }, title = { Text("모든 기록을 삭제할까요?") },
            text = { Text("차량 주행과 체험 주행 기록을 모두 삭제합니다. 내보낸 파일은 별도로 관리해주세요.") },
            confirmButton = { TextButton(onClick = { runtime.clearHistory(); clear = false }) { Text("모두 삭제", color = Rose) } },
            dismissButton = { TextButton(onClick = { clear = false }) { Text("취소") } })
    }
}

@Composable private fun Header(s: CoachState) {
    Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(Mint), contentAlignment = Alignment.Center) { Text("온", fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, color = Ink) }
            Column { Text("온유 드라이브", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold); Text("조금 더 부드러운 이동", color = Muted, fontSize = 11.sp) }
        }
        Pill(if (s.active?.source == DriveSource.DEMO) "체험 주행" else if (s.vehicle.connected) "차량 연결됨" else "연결 대기", if (s.active?.source == DriveSource.DEMO) Amber else if (s.vehicle.connected) Mint else Muted)
    }
}

@Composable private fun Home(s: CoachState, onMode: (PassengerMode) -> Unit, onStart: (DriveSource) -> Unit,
                             onHistory: () -> Unit, onDrive: (String) -> Unit, onReconnect: () -> Unit) {
    val last = s.history.firstOrNull { it.source == DriveSource.VEHICLE }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Title("YOUR GENTLE JOURNEY", "오늘도, 부드럽게.", "나의 습관을 알고, 함께 탄 사람을 배려해요.")
        BoxWithConstraints {
            if (maxWidth > 800.dp) Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                Surface(Modifier.weight(0.9f).fillMaxHeight(), color = CardColor, shape = Shape) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        ScoreRing(last?.score ?: 100, if (last == null) "시작 점수" else "지난 차량 주행", 220)
                        Text("점수보다 중요한 건, 좋은 습관", color = Muted, fontSize = 13.sp)
                    }
                }
                Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { Modes(s.mode, onMode); StartCard(s, onStart, onReconnect) }
            } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Panel {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceAround) {
                        ScoreRing(last?.score ?: 100, if (last == null) "시작 점수" else "지난 차량 주행", 170)
                        Column(Modifier.widthIn(max = 190.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("좋은 습관을 쌓고\n점수를 회복해요.", color = White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text("주행 중엔 간결하게,\n자세한 건 도착 후에.", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
                Modes(s.mode, onMode); StartCard(s, onStart, onReconnect)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("최근 드라이브", color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = onHistory) { Text("전체 보기  →", color = Mint) }
        }
        if (s.history.isEmpty()) Empty("첫 드라이브를 기다리고 있어요", "체험 주행으로 점수와 기록을 먼저 만나보세요.")
        else s.history.take(2).forEach { DriveRow(it) { onDrive(it.id) } }
        Info("한 번의 점수보다 반복되는 습관", "점수는 시범 평가 기준에 따른 참고 정보예요. 실제 차량 수명이나 동승자의 느낌을 측정한 값은 아니에요.")
        Spacer(Modifier.height(8.dp))
    }
}

@Composable private fun Modes(mode: PassengerMode, onMode: (PassengerMode) -> Unit) {
    Panel {
        Eyebrow("WHO'S WITH YOU?"); Text("누구와 함께하나요?", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PassengerMode.entries.forEach { item ->
                Surface(Modifier.weight(1f).heightIn(min = 60.dp).clip(RoundedCornerShape(14.dp)).clickable { onMode(item) },
                    color = if (mode == item) Mint else Ink, shape = RoundedCornerShape(14.dp)) {
                    Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) { Text(item.title, color = if (mode == item) Ink else Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        Spacer(Modifier.height(10.dp)); Text(mode.description, color = Muted, fontSize = 13.sp)
    }
}

@Composable private fun StartCard(s: CoachState, onStart: (DriveSource) -> Unit, onReconnect: () -> Unit) {
    Panel {
        val ready = s.vehicle.connected && s.vehicle.speedMps != null
        Text(if (ready) "편안한 이동을 시작해요" else "먼저 가볍게 체험해보세요", color = White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp)); Text(s.vehicle.message, color = Muted, fontSize = 12.sp)
        if (ready && !s.vehicle.fresh) Text("정차 중에는 속도 신호가 갱신되지 않을 수 있어요. 출발 후 새 신호로 평가해요.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onStart(DriveSource.VEHICLE) }, enabled = ready, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("차량 주행 시작  ↗", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { onStart(DriveSource.DEMO) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("체험 주행 · 약 1분", color = Mint, fontSize = 15.sp) }
        if (s.vehicle.automotive && !ready) TextButton(onClick = onReconnect) { Text("차량 정보 다시 연결", color = Muted, fontSize = 12.sp) }
        Spacer(Modifier.height(8.dp)); Text("체험은 준비된 주행 신호로 진행하며 실제 기록과 구분돼요.", color = Muted, fontSize = 11.sp)
    }
}

@Composable private fun DrivingView(s: CoachState, restricted: Boolean, onFinish: () -> Unit) {
    val active = s.active
    Column(Modifier.fillMaxSize().then(if (restricted) Modifier else Modifier.verticalScroll(rememberScrollState())),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Pill(if (active == null) "운전에 집중해주세요" else "${active.source.title} · ${active.mode.title}", Mint)
        ScoreRing(active?.score ?: 100, if (active == null) "기록 대기" else "운전 습관 점수", if (restricted) 205 else 240)
        val event = s.lastEvent?.takeIf { s.now - s.lastEventAt < 4_000 }
        Surface(Modifier.fillMaxWidth().heightIn(min = 66.dp), color = CardColor, shape = RoundedCornerShape(18.dp)) {
            Box(Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                Text(if (event != null) "${event.kind.title}  ·  ${eventDelta(event)}" else if (active == null) "안전하게 주차한 뒤 기록을 시작할 수 있어요." else "부드러운 이동을 기록하고 있어요.",
                    color = if (event?.review == EventReview.PENDING) Amber else Mint, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Metric("현재 속도", s.speedKmh?.roundToInt()?.toString() ?: "—", "km/h")
            Metric("이동 거리", String.format(Locale.KOREA, "%.1f", (active?.distanceMeters ?: 0.0) / 1000), "km")
            Metric("기록 시간", duration(active?.durationMs ?: 0), "")
        }
        if (restricted) {
            Text("상세 기록과 설정은 주차 후 확인할 수 있어요.", color = Muted, fontSize = 13.sp)
            if (!s.vehicle.fresh) Text("차량 신호 확인 중 · 평가를 보류합니다.", color = Amber, fontSize = 12.sp)
            if (active == null) Text(s.vehicle.message, color = Muted, fontSize = 12.sp)
        } else {
            if (active?.source == DriveSource.DEMO) Text("준비된 주행 신호를 재생하고 있어요 · 실제 기록과 별도 저장", color = Amber, fontSize = 12.sp)
            if ((active?.pending ?: 0) > 0) Text("강한 감속은 원인을 알 수 없어 감점을 보류했어요. 종료 후 상황을 확인해주세요.", color = Muted, fontSize = 13.sp)
            Button(onClick = onFinish, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("주행 마치고 기록 보기", fontSize = 16.sp) }
            active?.events?.takeLast(4)?.reversed()?.forEach { e -> Panel { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${duration(e.atMs)}  ${e.kind.title}", color = White, fontSize = 14.sp); Text(eventDelta(e), color = eventColor(e), fontWeight = FontWeight.Bold) } } }
        }
        if (s.storageError) Text("기록 저장 상태를 확인해주세요", color = Rose, fontSize = 13.sp)
    }
}

@Composable private fun History(s: CoachState, onDrive: (String) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("전체") }
    val drives = s.history.filter { filter == "전체" || it.source.title == filter }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item { Title("DRIVE JOURNAL", "쌓이는 기록, 달라지는 습관", "점수 뒤에 있는 나의 주행 이야기를 확인하세요.") }
        item { Chips(listOf("전체", "차량 주행", "체험 주행"), filter) { filter = it } }
        if (drives.isEmpty()) item { Empty("아직 기록이 없어요", "주행을 마치면 이곳에 점수와 이벤트가 남아요.") }
        items(drives, key = { it.id }) { d -> DriveRow(d) { onDrive(d.id) } }
    }
}

@Composable private fun DriveRow(d: Drive, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag("drive-${d.id}").clip(Shape).clickable(onClick = onClick), color = CardColor, shape = Shape) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(Ink), contentAlignment = Alignment.Center) { Text("${d.score}", color = Mint, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(date(d.startedAt), color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("${d.mode.title} · ${duration(d.durationMs)} · ${String.format(Locale.KOREA, "%.1f", d.distanceMeters / 1000)} km", color = Muted, fontSize = 12.sp)
                if (d.pending > 0) Text("확인할 이벤트 ${d.pending}개", color = Amber, fontSize = 11.sp)
                if (d.interrupted) Text("중단 지점까지 복구된 기록", color = Amber, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) { Pill(d.source.title, if (d.source == DriveSource.DEMO) Amber else Mint); Text("→", color = Muted, fontSize = 20.sp) }
        }
    }
}

@Composable private fun DriveDetail(d: Drive, onBack: () -> Unit, onEvent: (String) -> Unit, onFeedback: (Int, String) -> Unit, onDelete: () -> Unit) {
    var filter by rememberSaveable(d.id) { mutableStateOf("전체") }
    var author by rememberSaveable(d.id) { mutableStateOf(d.feedbackAuthor ?: "동승자 본인") }
    val events = d.events.filter { when (filter) { "가점" -> it.review == EventReview.APPLIED && it.requestedDelta > 0; "감점" -> it.delta < 0; "확인 필요" -> it.review == EventReview.PENDING; else -> true } }
    LazyColumn(modifier = Modifier.testTag("drive-report"), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { BackLink("주행 기록", onBack); Title("DRIVE REPORT", "이번 이동을 돌아볼까요?", "${date(d.startedAt)} · ${d.mode.title} · ${d.source.title}") }
        item { Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
                ScoreRing(d.score, "최종 점수", 160)
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("감점 ${d.losses}점   ·   회복 ${d.gains}점", color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("${duration(d.durationMs)}  ·  ${String.format(Locale.KOREA, "%.2f", d.distanceMeters / 1000)} km", color = Muted)
                    Text("평가 가능 구간 ${(d.coverage * 100).roundToInt()}%", color = Muted, fontSize = 12.sp)
                    if (d.pending > 0) Text("확인할 이벤트 ${d.pending}개", color = Amber, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(12.dp)); ScoreChart(d); Text("출발 → 도착", color = Muted, fontSize = 11.sp)
        } }
        item { val kind = d.events.filter { it.review == EventReview.APPLIED && it.delta < 0 }.groupingBy { it.kind }.eachCount().maxByOrNull { it.value }?.key
            Info("다음 주행에서 한 가지만", kind?.advice()?.next ?: "주변 교통 흐름을 살피며 지금의 부드러운 조작을 이어가세요.") }
        if (d.interrupted) item { Info("중단 지점까지 저장된 기록", "앱이나 기록이 중단되어 마지막으로 저장된 지점까지 복구했어요.", Amber) }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("점수가 변한 순간", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold); Text("${d.events.size}개 이벤트", color = Muted, fontSize = 12.sp) } }
        item { Chips(listOf("전체", "가점", "감점", "확인 필요"), filter) { filter = it } }
        if (events.isEmpty()) item { Empty("이 항목의 이벤트가 없어요", "감지된 행동은 자동으로 기록돼요. 짧거나 신호가 부족한 주행은 평가가 제한될 수 있어요.") }
        items(events, key = { it.id }) { e -> EventRow(e) { onEvent(e.id) } }
        item { Panel {
            Eyebrow("HOW DID IT FEEL?"); Text("실제로는 얼마나 편안했나요?", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp)); Text("선택 사항이에요. 점수와 별도로 체감을 기록해요.", color = Muted, fontSize = 12.sp)
            Chips(listOf("동승자 본인", "운전자 관찰"), author) { author = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3 to "편안했어요", 2 to "보통이에요", 1 to "불편했어요").forEach { (value, label) ->
                    OutlinedButton(onClick = { onFeedback(value, author) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 12.dp), shape = RoundedCornerShape(12.dp)) { Text(if (d.comfortFeedback == value && d.feedbackAuthor == author) "✓ $label" else label, fontSize = 12.sp) }
                }
            }
        } }
        item { Text("평가 기준 ${d.ruleVersion} · 차량 수명이나 실제 편안함의 측정값이 아니에요.", color = Muted, fontSize = 11.sp) }
        item { TextButton(onClick = onDelete) { Text("이 드라이브 삭제", color = Rose) } }
    }
}

@Composable private fun EventRow(e: DriveEvent, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag("event-${e.id}").clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick), color = CardColor, shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(eventColor(e)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(e.kind.title, color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold); Text("출발 후 ${duration(e.atMs)} · ${e.before} → ${e.after}점", color = Muted, fontSize = 12.sp) }
            Text(eventDelta(e), color = eventColor(e), fontSize = 16.sp, fontWeight = FontWeight.Bold); Text("→", color = Muted)
        }
    }
}

@Composable private fun EventDetail(d: Drive, e: DriveEvent, onBack: () -> Unit, onReview: (EventReview) -> Unit) {
    val advice = e.kind.advice()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BackLink("드라이브 상세", onBack); Title("MOMENT DETAILS", e.kind.title, "출발 후 ${duration(e.atMs)} · ${d.mode.title}")
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("${e.before} → ${e.after}", color = White, fontSize = 40.sp, fontWeight = FontWeight.Bold); Pill(eventDelta(e), eventColor(e)) }
            Spacer(Modifier.height(12.dp)); Text(e.observation, color = White, fontSize = 16.sp)
            if (e.context.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(e.context, color = Muted, fontSize = 13.sp) }
        }
        if (e.kind == EventKind.BRAKING) Panel {
            Text("어떤 상황이었나요?", color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp)); Text("감속 원인은 움직임만으로 알 수 없어요. 필요한 제동을 피하지 마세요. 선택하면 이후 점수도 함께 다시 계산해요.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onReview(EventReview.EXCLUDED) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("위험 회피 · 평가 제외", fontSize = 12.sp) }
                OutlinedButton(onClick = { onReview(EventReview.APPLIED) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("평소 주행 · 평가 반영", fontSize = 12.sp) }
            }
            TextButton(onClick = { onReview(EventReview.PENDING) }) { Text("잘 모르겠어요 · 보류 유지", color = Muted, fontSize = 12.sp) }
        }
        Panel {
            Text("점수에 반영된 요인", color = White, fontSize = 19.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(12.dp))
            val applies = e.review == EventReview.APPLIED
            val baseComfort = if (e.comfortPoints < 0) e.comfortPoints / d.mode.comfortWeight else e.comfortPoints
            Breakdown("차량 관리 기준", if (applies) e.vehiclePoints else 0)
            Breakdown("기본 부드러움 기준", if (applies) baseComfort else 0)
            Breakdown("동승 배려 추가 가중치", if (applies) e.comfortPoints - baseComfort else 0)
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Line); Breakdown("실제 점수 변화", e.delta)
            if (!applies) Text(if (e.review == EventReview.EXCLUDED) "평가에서 제외된 이벤트예요." else "아직 점수에 반영하지 않은 참고 기록이에요.", color = Amber, fontSize = 12.sp)
            if (applies && e.delta != e.requestedDelta) Text("점수는 0~100점 범위여서 요청한 가감점과 실제 변화량이 다를 수 있어요.", color = Muted, fontSize = 12.sp)
        }
        Info("차량에는 어떤 관련이 있나요?", advice.vehicle); Info("함께 탄 사람에게는?", advice.passenger); Info("다음 주행에서는", advice.next)
        Text("관찰된 움직임으로 산정한 시범 점수예요. 브레이크 마모량·의학적 상태를 진단하지 않아요.", color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable private fun Insights(s: CoachState, onExport: () -> Unit, onClear: () -> Unit) {
    var source by rememberSaveable { mutableStateOf(DriveSource.VEHICLE) }
    var mode by rememberSaveable { mutableStateOf(PassengerMode.NORMAL) }
    val drives = s.history.filter { it.source == source && it.mode == mode }
    val events = drives.flatMap { it.events }.filter { it.review == EventReview.APPLIED }
    val repeated = events.filter { it.requestedDelta < 0 }.groupingBy { it.kind }.eachCount()
        .filter { (kind, _) -> drives.count { d -> d.events.any { it.kind == kind && it.review == EventReview.APPLIED } } >= 2 }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Title("SMALL CHANGES, BETTER DRIVES", "나를 알아가는 시간", "반복되는 행동을 찾고, 작은 변화를 이어가세요.")
        Chips(DriveSource.entries.map { it.title }, source.title) { title -> source = DriveSource.entries.first { it.title == title } }
        Chips(PassengerMode.entries.map { it.title }, mode.title) { title -> mode = PassengerMode.entries.first { it.title == title } }
        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                Metric("드라이브", "${drives.size}", "회"); Metric("평균 점수", if (drives.isEmpty()) "—" else "${drives.map { it.score }.average().roundToInt()}", "점"); Metric("좋은 행동", "${events.count { it.requestedDelta > 0 }}", "회")
            }
            Spacer(Modifier.height(12.dp)); Text("같은 출처·동승 모드의 기록만 모았어요. 도로와 주행 시간이 달라 점수만으로 운전 실력을 비교할 수는 없어요.", color = Muted, fontSize = 12.sp)
        }
        if (repeated.isEmpty()) Info("아직 반복 습관을 단정하지 않아요", "같은 행동이 두 번 이상의 드라이브에서 나타나면 이곳에 안내해요. 한 번의 이벤트와 반복 습관은 구분해요.")
        else repeated.forEach { (kind, count) ->
            val trips = drives.count { d -> d.events.any { it.kind == kind && it.review == EventReview.APPLIED } }
            val km = drives.sumOf { it.distanceMeters } / 1000
            Info("${kind.title} · ${trips}번의 드라이브", "총 ${count}회 관찰됐어요.${if (km >= 1) " 10km당 ${String.format(Locale.KOREA, "%.1f", count / km * 10)}회예요." else " 비교할 주행 거리를 더 쌓고 있어요."}\n${kind.advice().next}", Amber)
        }
        Info("사람이 운전해도, AI가 운전해도", "주행 특성과 동승자의 직접 피드백을 연결하면 더 편안한 자동주행을 연구할 수 있어요. 지금은 기기 안에 기록만 보관하며 FSD에 연결하거나 데이터를 전송하지 않아요.")
        Panel {
            Text("내 기록은 내가 관리해요", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp)); Text("최근 30일, 최대 100개의 드라이브를 기기 안에 보관해요. 위치와 계정 정보는 수집하지 않아요. 내보내는 기록에는 주행 시각과 선택한 동승 모드가 포함돼요.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onExport, enabled = s.active == null && s.history.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) { Text("주행 기록 내보내기") }
            TextButton(onClick = onClear, enabled = s.active == null && s.history.isNotEmpty()) { Text("모든 기록 삭제", color = Rose) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable private fun ScoreRing(score: Int, label: String, size: Int) {
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val stroke = 11.dp.toPx(); val inset = stroke / 2; val arc = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(Line, 135f, 270f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(Mint, 135f, 270f * score / 100f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("$score", color = White, fontSize = (size * 0.29).sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp); Text(label, color = Muted, fontSize = 12.sp) }
    }
}

@Composable private fun ScoreChart(d: Drive) {
    Canvas(Modifier.fillMaxWidth().height(125.dp).padding(vertical = 8.dp)) {
        val height = size.height - 12; val total = maxOf(d.durationMs, 1).toFloat()
        listOf(0f, 0.5f, 1f).forEach { f -> drawLine(Line, Offset(0f, f * height), Offset(size.width, f * height), 1f) }
        val path = Path()
        var previousY = 0f
        d.scorePoints.forEachIndexed { index, p ->
            val x = p.atMs / total * size.width; val y = (100 - p.score) / 100f * height
            if (index == 0) path.moveTo(x, y) else { path.lineTo(x, previousY); path.lineTo(x, y) }
            previousY = y
        }
        path.lineTo(size.width, (100 - d.score) / 100f * height)
        drawPath(path, Mint, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        d.events.forEach { e -> drawCircle(eventColor(e), 4.dp.toPx(), Offset(e.atMs / total * size.width, (100 - e.after) / 100f * height)) }
    }
}

@Composable private fun Panel(content: @Composable ColumnScope.() -> Unit) { Surface(Modifier.fillMaxWidth(), color = CardColor, shape = Shape) { Column(Modifier.padding(22.dp), content = content) } }
@Composable private fun Info(title: String, body: String, accent: Color = Mint) { Surface(Modifier.fillMaxWidth(), color = CardColor, shape = Shape) { Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) { Box(Modifier.width(3.dp).height(38.dp).clip(CircleShape).background(accent)); Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, color = White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp); Text(body, color = Muted, fontSize = 13.sp, lineHeight = 21.sp) } } } }
@Composable private fun Empty(title: String, body: String) { Panel { Text(title, color = White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(8.dp)); Text(body, color = Muted, fontSize = 13.sp, lineHeight = 21.sp) } }
@Composable private fun Pill(title: String, color: Color) { Surface(color = color.copy(alpha = 0.10f), shape = CircleShape) { Text(title, color = color, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontWeight = FontWeight.Medium) } }
@Composable private fun Eyebrow(text: String) { Text(text, color = Mint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, modifier = Modifier.padding(bottom = 8.dp)) }
@Composable private fun Title(eyebrow: String, title: String, subtitle: String) { Column { Eyebrow(eyebrow); Text(title, color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(7.dp)); Text(subtitle, color = Muted, fontSize = 13.sp) } }
@Composable private fun Metric(label: String, value: String, unit: String) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) { Text(label, color = Muted, fontSize = 11.sp); Row(verticalAlignment = Alignment.Bottom) { Text(value, color = White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold); if (unit.isNotEmpty()) Text(" $unit", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 3.dp)) } } }
@Composable private fun Breakdown(title: String, amount: Int) { Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(title, color = Muted, fontSize = 13.sp); Text(if (amount > 0) "+$amount" else "$amount", color = if (amount < 0) Rose else Mint, fontWeight = FontWeight.SemiBold) } }
@Composable private fun BackLink(label: String, onBack: () -> Unit) { TextButton(onClick = onBack, contentPadding = PaddingValues(vertical = 8.dp)) { Text("←  $label", color = Muted) } }
@Composable private fun Chips(options: List<String>, selected: String, onSelect: (String) -> Unit) { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { options.forEach { f -> FilterChip(selected = selected == f, onClick = { onSelect(f) }, label = { Text(f, fontSize = 12.sp) }) } } }
@Composable private fun Navigation(tab: String, onTab: (String) -> Unit) { Column { HorizontalDivider(color = Line); Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) { listOf("drive" to "드라이브", "history" to "주행 기록", "insights" to "인사이트").forEach { (key, label) -> Surface(Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(14.dp)).clickable { onTab(key) }, color = if (tab == key) Mint.copy(alpha = 0.1f) else Ink, shape = RoundedCornerShape(14.dp)) { Box(contentAlignment = Alignment.Center) { Text(label, color = if (tab == key) Mint else Muted, fontSize = 14.sp, fontWeight = if (tab == key) FontWeight.Bold else FontWeight.Normal) } } } } } }
private fun eventColor(e: DriveEvent): Color = when { e.review == EventReview.PENDING -> Amber; e.review == EventReview.EXCLUDED || e.review == EventReview.REFERENCE -> Muted; e.requestedDelta < 0 -> Rose; else -> Mint }
private fun eventDelta(e: DriveEvent): String = when { e.review == EventReview.PENDING -> "확인 예정"; e.review == EventReview.EXCLUDED -> "평가 제외"; e.review == EventReview.REFERENCE -> "참고 기록"; e.delta > 0 -> "+${e.delta}점"; e.delta < 0 -> "${e.delta}점"; e.requestedDelta > 0 -> "100점 유지"; else -> "변화 없음" }
private fun duration(ms: Long): String { val sec = ms.coerceAtLeast(0) / 1000; return if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%02d:%02d".format(sec / 60, sec % 60) }
private fun date(time: Long): String = SimpleDateFormat("M월 d일 · HH:mm", Locale.KOREA).format(Date(time))
