@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.netline.app.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netline.app.data.*
import com.netline.app.export.*
import com.netline.app.monitor.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val Paper = Color(0xFFF4F3EF)
val Ink = Color(0xFF202020)
val Red = Color(0xFFE3342F)
val Muted = Color(0xFF6B6B65)
val Rule = Color(0xFFD3D3CD)

@Composable
fun NetlineRoot(onToggle: () -> Unit, onOverlay: (Boolean) -> Unit, vm: NetlineViewModel = viewModel()) {
    val monitor by vm.monitor.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val export by vm.export.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("MONITOR") }
    var exportSession by rememberSaveable { mutableStateOf<Long?>(null) }
    BackHandler(enabled = page == "EXPORT") { if (!export.busy) page = "HISTORY" }
    val context = LocalContext.current
    MaterialTheme(colorScheme = lightColorScheme(primary = Ink, secondary = Red, background = Paper, surface = Paper, onSurface = Ink),
        typography = Typography(bodyLarge = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp))) {
        Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(7.dp).height(31.dp).background(Red))
                    Text("NETLINE", fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.6).sp, modifier = Modifier.padding(start = 9.dp))
                    Spacer(Modifier.weight(1f))
                    Text("LOCAL\nV 1.1", color = Muted, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace)
                }
                HorizontalDivider(color = Ink, thickness = 2.dp, modifier = Modifier.padding(horizontal = 24.dp))
                Box(Modifier.weight(1f)) {
                    when (page) {
                        "MONITOR" -> Dashboard(monitor, sessions, onToggle) {
                            exportSession = sessions.firstOrNull { it.endedAt != null }?.id ?: monitor.activeSessionId
                            vm.clearExport(); page = "EXPORT"
                        }
                        "HISTORY" -> HistoryScreen(sessions) { exportSession = it; vm.clearExport(); page = "EXPORT" }
                        "SETTINGS" -> SettingsScreen(monitor, settings, onOverlay, vm)
                        "EXPORT" -> ExportPreviewScreen(sessions, exportSession, settings.range, export,
                            onSelect = { exportSession = it; vm.clearExport() }, onGenerate = vm::generate,
                            onSave = vm::save, onBack = { page = "HISTORY" }, onShare = {
                                runCatching { export.image?.let { ImageShareManager(context).share(it.file) } }
                                    .onFailure { Toast.makeText(context, it.localizedMessage, Toast.LENGTH_LONG).show() }
                            })
                    }
                }
                if (page != "EXPORT") {
                    HorizontalDivider(color = Ink, modifier = Modifier.padding(horizontal = 24.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        listOf("MONITOR", "HISTORY", "SETTINGS").forEachIndexed { index, label ->
                            TextButton(onClick = { page = label }, modifier = Modifier.weight(1f)) {
                                Column { Text("0${index + 1}", fontSize = 10.sp, color = if (page == label) Red else Muted)
                                    Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (page == label) Ink else Muted) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Label(value: String, modifier: Modifier = Modifier, color: Color = Muted) {
    Text(value, modifier, color = color, fontSize = 11.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Medium)
}
@Composable private fun SectionTitle(number: String, name: String) {
    Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 16.dp)) {
        Text(number, color = Red, fontSize = 12.sp, modifier = Modifier.width(32.dp)); Label(name, color = Ink)
    }
}
@Composable private fun Action(label: String, enabled: Boolean = true, red: Boolean = false, onClick: () -> Unit) {
    Button(onClick, enabled = enabled, shape = androidx.compose.ui.graphics.RectangleShape,
        colors = ButtonDefaults.buttonColors(containerColor = if (red) Red else Ink),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(label, fontSize = 12.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun Dashboard(state: MonitorState, sessions: List<Session>, onToggle: () -> Unit, onExport: () -> Unit) {
    val last = state.recent.lastOrNull { it.valid }
    val recording = state.recordingState == RecordingState.RECORDING
    val busy = state.recordingState == RecordingState.STARTING || state.recordingState == RecordingState.STOPPING
    val report = remember(state.recent) {
        val raw = state.recent
        if (raw.isEmpty()) null else ChartExportDataBuilder.fromSamples(raw, raw.first().startedAt, raw.last().endedAt)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Label("DEVICE TRAFFIC / 设备流量")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (recording) Red else Muted)); Spacer(Modifier.width(6.dp))
                Text(state.recordingState.name, fontSize = 10.sp, color = if (recording) Red else Muted, fontFamily = FontFamily.Monospace)
            }
        }
        Text("Every byte.\nIn sight.", fontSize = 45.sp, lineHeight = 45.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp,
            modifier = Modifier.padding(top = 22.dp, bottom = 28.dp))
        Label("↓ DOWNLOAD")
        Text(if (last != null && recording) speed(last.down) else "— B/s", fontSize = 43.sp, letterSpacing = (-1.5).sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Label("↑ UPLOAD", Modifier.width(105.dp), Red)
            Text(if (last != null && recording) speed(last.up) else "— B/s", fontSize = 24.sp, color = Red, fontWeight = FontWeight.Medium) }
        SectionTitle("01", "SPEED OVER TIME")
        SpeedChart(report, Modifier.fillMaxWidth().height(145.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 22.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Label(report?.let { timestamp(it.from) } ?: "NO SAMPLES")
            Label(report?.let { timestamp(it.to) } ?: "LOCAL ONLY")
        }
        Action(when (state.recordingState) {
            RecordingState.IDLE -> "START RECORDING"
            RecordingState.RECORDING -> "STOP RECORDING"
            RecordingState.STARTING -> "STARTING…"
            RecordingState.STOPPING -> "SAVING…"
        }, enabled = !busy, red = recording, onClick = onToggle)
        OutlinedButton(onClick = onExport, enabled = sessions.isNotEmpty(), shape = RectangleShape,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) { Text("EXPORT CHART", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Text("本机流量 · 1 秒采样 · 无云端上传\n锁屏或系统休眠可能产生采样间断。", color = Muted, fontSize = 11.sp,
            lineHeight = 17.sp, modifier = Modifier.padding(top = 16.dp, bottom = 24.dp))
    }
}

@Composable fun SpeedChart(data: ChartPlotData?, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        repeat(4) { i -> val y = size.height * i / 3; drawLine(Rule, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1f) }
        if (data == null) return@Canvas
        val maximum = maxOf(data.statistics.peakDown.value, data.statistics.peakUp.value, 1.0) * 1.12
        fun point(time: Long, value: Double) = androidx.compose.ui.geometry.Offset(
            ((time - data.from).toDouble() / (data.to - data.from) * size.width).toFloat(),
            size.height - (value / maximum * size.height).toFloat())
        ChartExportDataBuilder.segments(data, size.width.toInt().coerceAtLeast(1)).forEach { group ->
            listOf(true, false).forEach { down ->
                val path = Path()
                group.forEachIndexed { index, v -> val pos = point(v.end, if (down) v.down else v.up)
                    if (index == 0) path.moveTo(pos.x, pos.y) else path.lineTo(pos.x, pos.y) }
                drawPath(path, if (down) Ink else Red, style = Stroke(width = 2.dp.toPx()))
                if (group.size == 1) drawCircle(if (down) Ink else Red, 2.dp.toPx(), point(group[0].end, if (down) group[0].down else group[0].up))
            }
        }
        data.intervals.filter { it.aggregated }.forEach { v ->
            val at = (v.start + v.end) / 2
            v.lowestDown?.let { drawLine(Ink, point(at, it.value), point(at, v.peakDown.value), 2f) }
            v.lowestUp?.let { drawLine(Red, point(at, it.value), point(at, v.peakUp.value), 2f) }
        }
    }
}

@Composable private fun HistoryScreen(sessions: List<Session>, onSelect: (Long) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        item { SectionTitle("02", "RECORDING ARCHIVE"); Text("History.", fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp); Spacer(Modifier.height(20.dp)) }
        if (sessions.isEmpty()) item { Text("还没有记录。开始一次采样后，会话会保存在这里。", color = Muted, modifier = Modifier.padding(vertical = 20.dp)) }
        items(sessions, key = { it.id }) { session ->
            Column(Modifier.fillMaxWidth().clickable { onSelect(session.id) }.padding(vertical = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Label(timestamp(session.startedAt, "yyyy.MM.dd"), color = Ink)
                    Label(when { session.endedAt == null -> "RECORDING"; session.interrupted -> "INTERRUPTED"; else -> "SAVED" }, color = if (session.endedAt == null) Red else Muted)
                }
                Text("${timestamp(session.startedAt)} — ${session.endedAt?.let { timestamp(it) } ?: "LIVE"}", fontSize = 22.sp, modifier = Modifier.padding(vertical = 9.dp))
                Label("SESSION ${session.id.toString().padStart(4, '0')}   /   SHARE REPORT →")
            }
            HorizontalDivider(color = Rule)
        }
        item { Spacer(Modifier.height(24.dp)); Text("原始采样保留 7 天，之后保留分钟汇总。\n间断、零速率与中断会话均如实保留。", fontSize = 12.sp, color = Muted); Spacer(Modifier.height(24.dp)) }
    }
}

@Composable private fun SettingsScreen(state: MonitorState, settings: AppSettings, onOverlay: (Boolean) -> Unit, vm: NetlineViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        SectionTitle("03", "PREFERENCES")
        Text("Less, but\nbetter.", fontSize = 44.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp)
        SectionTitle("A", "FLOATING CONTROLS")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Enable Floating Ball", fontSize = 16.sp, fontWeight = FontWeight.Medium); Label("ENABLE FLOATING BALL", Modifier.padding(top = 5.dp)) }
            Switch(checked = state.overlayEnabled, onCheckedChange = onOverlay)
        }
        Text("需要单独的“显示在其他应用上层”权限。单击开始/停止，拖动移动，长按打开菜单。停止记录后保持待机；关闭悬浮球不会结束正在进行的记录。", fontSize = 12.sp, color = Muted, lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp))
        TextButton(onClick = vm::positionReset) { Text("FLOATING BALL POSITION: RESET", fontSize = 11.sp) }
        Text("系统受保护界面可能隐藏悬浮球；系统终止后从此处重新启用。不会开机自启或循环重启。", fontSize = 11.sp, color = Muted, lineHeight = 17.sp)
        HorizontalDivider(color = Rule, modifier = Modifier.padding(top = 20.dp))
        SectionTitle("B", "EXPORT SETTINGS")
        Text("Default Export Range", fontWeight = FontWeight.Medium)
        RangeSelector(settings.range, EXPORT_RANGES.filter { it != "Custom Range" }, vm::defaultRange)
        Text("EXPORT IMAGE INFORMATION", fontSize = 11.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 14.dp))
        Text("PNG · 1080 × 1920 · 固定瑞士风格\n有效区间字节数 ÷ 有效时长计算均速。缺失区间不补零，分钟汇总不伪造秒级曲线。", fontSize = 12.sp, color = Muted, lineHeight = 19.sp, modifier = Modifier.padding(vertical = 12.dp))
        OutlinedButton(onClick = vm::clearCache, shape = RectangleShape) { Text("CLEAR TEMPORARY EXPORT CACHE", fontSize = 10.sp) }
        val export by vm.export.collectAsStateWithLifecycle()
        export.message?.let { Text(it, color = Red, fontSize = 12.sp) }
        HorizontalDivider(color = Rule, modifier = Modifier.padding(vertical = 20.dp))
        Label("NETLINE 1.1 / PERSONAL EDITION")
        Text("全部记录仅保存在本机。TrafficStats 包含设备范围流量，可能受系统计数器、VPN、网络接口和休眠限制；不代表测速服务器的带宽测试。", color = Muted, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp, bottom = 30.dp))
    }
}

@Composable fun RangeSelector(selected: String, options: List<String> = EXPORT_RANGES, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 10.dp)) {
        options.forEach { label -> FilterChip(selected = selected == label, onClick = { onSelect(label) },
            shape = RectangleShape, label = { Text(label, fontSize = 10.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Ink, selectedLabelColor = Color.White)) }
    }
}

@Composable fun ExportPreviewScreen(
    sessions: List<Session>, selectedId: Long?, defaultRange: String, state: ExportState,
    onSelect: (Long) -> Unit, onGenerate: (Long, String, String, String) -> Unit,
    onSave: () -> Unit, onShare: () -> Unit, onBack: () -> Unit
) {
    var range by rememberSaveable(selectedId) { mutableStateOf(defaultRange) }
    var expanded by remember { mutableStateOf(false) }
    val selected = sessions.firstOrNull { it.id == selectedId }
    var start by rememberSaveable(selectedId) { mutableStateOf(selected?.let { timestamp(it.startedAt, "yyyy-MM-dd HH:mm:ss.SSS") } ?: "") }
    var end by rememberSaveable(selectedId) { mutableStateOf(selected?.let { timestamp(it.endedAt ?: System.currentTimeMillis(), "yyyy-MM-dd HH:mm:ss.SSS") } ?: "") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        TextButton(onClick = onBack, enabled = !state.busy, contentPadding = PaddingValues(0.dp)) { Text("← BACK", fontSize = 11.sp) }
        Text("The report.", fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.8).sp)
        SectionTitle("01", "SELECT ONE SESSION")
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = !state.busy && sessions.isNotEmpty(), shape = RectangleShape, modifier = Modifier.fillMaxWidth()) {
                Text(selected?.let { "#${it.id}  ${timestamp(it.startedAt, "MM.dd HH:mm:ss")}  /  ${if (it.endedAt == null) "LIVE" else "SAVED"}" } ?: "SELECT RECORDING", fontSize = 12.sp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                sessions.forEach { session -> DropdownMenuItem(text = { Text("#${session.id}  ${timestamp(session.startedAt, "yyyy.MM.dd HH:mm:ss")}", fontSize = 12.sp) },
                    onClick = { onSelect(session.id); expanded = false }) }
            }
        }
        RangeSelector(range, onSelect = { if (!state.busy) range = it })
        if (range == "Custom Range") {
            OutlinedTextField(start, { start = it }, label = { Text("START · yyyy-MM-dd HH:mm:ss[.SSS]") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(end, { end = it }, label = { Text("END · yyyy-MM-dd HH:mm:ss[.SSS]") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
        }
        Text("固定范围取此会话末尾；仅导出已持久化数据。记录中可导出，不会暂停采样。自定义范围使用设备本地时间。", fontSize = 11.sp, color = Muted, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 14.dp))
        Action(if (state.busy) "PROCESSING…" else "GENERATE PNG", !state.busy && selected != null) { selected?.id?.let { onGenerate(it, range, start, end) } }
        state.message?.let { Text(it, color = Red, fontSize = 12.sp, modifier = Modifier.padding(vertical = 14.dp)) }
        state.image?.let { image ->
            SectionTitle("02", "IMAGE PREVIEW / PINCH TO ZOOM")
            // Decode at half resolution for the preview; the shared PNG stays at 1080 × 1920.
            val bitmap by produceState<android.graphics.Bitmap?>(null, image.file) {
                value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(image.file.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 }) }
            }
            var scale by remember(image.file) { mutableFloatStateOf(1f) }
            var pan by remember(image.file) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
            var previewSize by remember { mutableStateOf(IntSize.Zero) }
            val zoomState = rememberTransformableState { zoom, offset, _ ->
                scale = (scale * zoom).coerceIn(1f, 4f)
                val maxX = previewSize.width * (scale - 1f) / 2
                val maxY = previewSize.height * (scale - 1f) / 2
                pan = androidx.compose.ui.geometry.Offset((pan.x + offset.x).coerceIn(-maxX, maxX), (pan.y + offset.y).coerceIn(-maxY, maxY))
            }
            Box(Modifier.fillMaxWidth().aspectRatio(1080f / 1920f).clip(RectangleShape).background(Rule)
                .onSizeChanged { previewSize = it }
                .transformable(state = zoomState, canPan = { scale > 1f })) {
                bitmap?.let { Image(it.asImageBitmap(), contentDescription = "NETLINE 网络流量报告预览", modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y }) }
            }
            image.report.notes.forEach { Text(it, fontSize = 10.sp, color = Muted, modifier = Modifier.padding(top = 5.dp)) }
            Spacer(Modifier.height(16.dp))
            Action("SAVE IMAGE", !state.busy, onClick = onSave)
            Spacer(Modifier.height(8.dp))
            Action("SHARE IMAGE", !state.busy, red = true, onClick = onShare)
            Spacer(Modifier.height(28.dp))
        }
        if (sessions.isEmpty()) Text("没有记录可导出，请先开始记录。", modifier = Modifier.padding(vertical = 20.dp), color = Muted)
    }
}
