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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.netline.app.data.*
import com.netline.app.export.*
import com.netline.app.monitor.*
import com.netline.app.*
import com.netline.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val Paper = Color(0xFFF4F3EF)
val Ink = Color(0xFF202020)
val Red = Color(0xFFE3342F)
val Muted = Color(0xFF6B6B65)
val Rule = Color(0xFFD3D3CD)

@Composable private fun tr(id: Int, vararg args: Any): String = stringResource(id, *args)

@Composable
fun NetlineRoot(onToggle: () -> Unit, onOverlay: (Boolean) -> Unit, vm: NetlineViewModel = viewModel()) {
    val monitor by vm.monitor.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val language by vm.app.language.collectAsStateWithLifecycle()
    val export by vm.export.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("MONITOR") }
    var exportSession by rememberSaveable { mutableStateOf<Long?>(null) }
    BackHandler(enabled = page == "EXPORT") { if (!export.busy) page = "HISTORY" }
    val context = LocalContext.current
    val translated = remember(context, language) { context.localizedContext(language) }
    CompositionLocalProvider(LocalContext provides translated, LocalResources provides translated.resources) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Ink, secondary = Red, background = Paper, surface = Paper, onSurface = Ink),
        typography = Typography(bodyLarge = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp))) {
        Surface(color = Paper, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(7.dp).height(31.dp).background(Red))
                    Text("NETLINE", fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.6).sp, modifier = Modifier.padding(start = 9.dp))
                    Spacer(Modifier.weight(1f))
                    Text(tr(R.string.local_version), color = Muted, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace)
                }
                HorizontalDivider(color = Ink, thickness = 2.dp, modifier = Modifier.padding(horizontal = 24.dp))
                Box(Modifier.weight(1f)) {
                    when (page) {
                        "MONITOR" -> Dashboard(monitor, sessions, onToggle) {
                            exportSession = sessions.firstOrNull { it.endedAt != null }?.id ?: monitor.activeSessionId
                            vm.clearExport(); page = "EXPORT"
                        }
                        "HISTORY" -> HistoryScreen(sessions, onDelete = vm::deleteSession,
                            onSelect = { exportSession = it; vm.clearExport(); page = "EXPORT" })
                        "SETTINGS" -> SettingsScreen(monitor, settings, onOverlay, vm)
                        "EXPORT" -> ExportPreviewScreen(sessions, exportSession, settings.range, export,
                            onSelect = { exportSession = it; vm.clearExport() }, onGenerate = vm::generate,
                            onSave = vm::save, onBack = { page = "HISTORY" }, onShare = {
                                runCatching { export.image?.let { ImageShareManager(context).share(it.file) } }
                                    .onFailure { Toast.makeText(context, context.localizedError(it), Toast.LENGTH_LONG).show() }
                            })
                    }
                }
                if (page != "EXPORT") {
                    HorizontalDivider(color = Ink, modifier = Modifier.padding(horizontal = 24.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        listOf("MONITOR", "HISTORY", "SETTINGS").forEachIndexed { index, label ->
                            TextButton(onClick = { page = label }, modifier = Modifier.weight(1f)) {
                                Column { Text("0${index + 1}", fontSize = 10.sp, color = if (page == label) Red else Muted)
                                    Text(tr(when (label) { "MONITOR" -> R.string.monitor; "HISTORY" -> R.string.history; else -> R.string.settings }), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (page == label) Ink else Muted) }
                            }
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
            Label(tr(R.string.device_traffic))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (recording) Red else Muted)); Spacer(Modifier.width(6.dp))
                Text(tr(when (state.recordingState) { RecordingState.IDLE -> R.string.idle; RecordingState.STARTING -> R.string.starting; RecordingState.RECORDING -> R.string.recording; RecordingState.STOPPING -> R.string.stopping }), fontSize = 10.sp, color = if (recording) Red else Muted, fontFamily = FontFamily.Monospace)
            }
        }
        Text(tr(R.string.tagline), fontSize = 45.sp, lineHeight = 45.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp,
            modifier = Modifier.padding(top = 22.dp, bottom = 28.dp))
        Label(tr(R.string.download))
        Text(if (last != null && recording) speed(last.down) else "— B/s", fontSize = 43.sp, letterSpacing = (-1.5).sp,
            fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Label(tr(R.string.upload), Modifier.width(105.dp), Red)
            Text(if (last != null && recording) speed(last.up) else "— B/s", fontSize = 24.sp, color = Red, fontWeight = FontWeight.Medium) }
        SectionTitle("01", tr(R.string.speed_over_time))
        SpeedChart(report, Modifier.fillMaxWidth().height(145.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 22.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Label(report?.let { timestamp(it.from) } ?: tr(R.string.no_samples))
            Label(report?.let { timestamp(it.to) } ?: tr(R.string.local_only))
        }
        Action(when (state.recordingState) {
            RecordingState.IDLE -> tr(R.string.start_recording)
            RecordingState.RECORDING -> tr(R.string.stop_recording)
            RecordingState.STARTING -> tr(R.string.starting)
            RecordingState.STOPPING -> tr(R.string.stopping)
        }, enabled = !busy, red = recording, onClick = onToggle)
        OutlinedButton(onClick = onExport, enabled = sessions.isNotEmpty(), shape = RectangleShape,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)) { Text(tr(R.string.export_chart), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        Text(tr(R.string.monitor_info), color = Muted, fontSize = 11.sp,
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

@Composable private fun HistoryScreen(sessions: List<Session>, onSelect: (Long) -> Unit, onDelete: (Long) -> Unit) {
    var menuId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    val deleting = sessions.firstOrNull { it.id == deletingId }
    if (deleting != null) AlertDialog(onDismissRequest = { deletingId = null },
        containerColor = Paper, shape = RectangleShape, titleContentColor = Ink, textContentColor = Ink,
        title = { Text(tr(R.string.delete_title)) },
        text = { Text(tr(R.string.delete_body, timestamp(deleting.startedAt, "yyyy.MM.dd HH:mm:ss"))) },
        confirmButton = { TextButton(onClick = { deletingId = null; onDelete(deleting.id) }) { Text(tr(R.string.delete), color = Red) } },
        dismissButton = { TextButton(onClick = { deletingId = null }) { Text(tr(R.string.cancel)) } })
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        item { SectionTitle("02", tr(R.string.archive)); Text(tr(R.string.history_title), fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp); Spacer(Modifier.height(20.dp)) }
        if (sessions.isEmpty()) item { Text(tr(R.string.history_empty), color = Muted, modifier = Modifier.padding(vertical = 20.dp)) }
        items(sessions, key = { it.id }) { session ->
            Box {
            Column(Modifier.fillMaxWidth().combinedClickable(onClick = { onSelect(session.id) }, onLongClick = { menuId = session.id }).padding(vertical = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Label(timestamp(session.startedAt, "yyyy.MM.dd"), color = Ink)
                    Label(when { session.endedAt == null -> tr(R.string.recording); session.interrupted -> tr(R.string.interrupted); else -> tr(R.string.saved) }, color = if (session.endedAt == null) Red else Muted)
                }
                Text("${timestamp(session.startedAt)} — ${session.endedAt?.let { timestamp(it) } ?: tr(R.string.live)}", fontSize = 22.sp, modifier = Modifier.padding(vertical = 9.dp))
                Label(tr(R.string.session_share, session.id.toString().padStart(4, '0')))
            }
            DropdownMenu(expanded = menuId == session.id, onDismissRequest = { menuId = null },
                containerColor = Paper, shape = RectangleShape, tonalElevation = 0.dp, shadowElevation = 0.dp,
                border = BorderStroke(1.dp, Rule)) {
                DropdownMenuItem(enabled = session.endedAt != null,
                    text = { Text(tr(if (session.endedAt == null) R.string.delete_active else R.string.delete_recording), color = if (session.endedAt == null) Muted else Red) },
                    onClick = { menuId = null; deletingId = session.id })
            }
            }
            HorizontalDivider(color = Rule)
        }
        item { Spacer(Modifier.height(24.dp)); Text(tr(R.string.history_info), fontSize = 12.sp, color = Muted); Spacer(Modifier.height(24.dp)) }
    }
}

@Composable private fun SettingsScreen(state: MonitorState, settings: AppSettings, onOverlay: (Boolean) -> Unit, vm: NetlineViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
        SectionTitle("03", tr(R.string.preferences))
        Text(tr(R.string.settings_title), fontSize = 44.sp, lineHeight = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp)
        SectionTitle("A", tr(R.string.language))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(selected = settings.language == "zh", onClick = { vm.language("zh") }, label = { Text("中文") }, shape = RectangleShape,
                border = BorderStroke(1.dp, Rule), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Ink, selectedLabelColor = Color.White))
            FilterChip(selected = settings.language == "en", onClick = { vm.language("en") }, label = { Text("English") }, shape = RectangleShape,
                border = BorderStroke(1.dp, Rule), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Ink, selectedLabelColor = Color.White))
        }
        SectionTitle("B", tr(R.string.floating_controls))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(tr(R.string.enable_ball), fontSize = 16.sp, fontWeight = FontWeight.Medium); Label(tr(R.string.enable_ball_label), Modifier.padding(top = 5.dp)) }
            Switch(checked = state.overlayEnabled, onCheckedChange = onOverlay)
        }
        Text(tr(R.string.overlay_explanation), fontSize = 12.sp, color = Muted, lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp))
        TextButton(onClick = vm::positionReset) { Text(tr(R.string.reset_ball), fontSize = 11.sp) }
        Text(tr(R.string.overlay_limit), fontSize = 11.sp, color = Muted, lineHeight = 17.sp)
        HorizontalDivider(color = Rule, modifier = Modifier.padding(top = 20.dp))
        SectionTitle("C", tr(R.string.export_settings))
        Text(tr(R.string.default_range), fontWeight = FontWeight.Medium)
        RangeSelector(settings.range, EXPORT_RANGES.filter { it != "Custom Range" }, vm::defaultRange)
        Text(tr(R.string.image_info), fontSize = 11.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 14.dp))
        Text(tr(R.string.export_info), fontSize = 12.sp, color = Muted, lineHeight = 19.sp, modifier = Modifier.padding(vertical = 12.dp))
        OutlinedButton(onClick = vm::clearCache, shape = RectangleShape) { Text(tr(R.string.clear_cache), fontSize = 10.sp) }
        val export by vm.export.collectAsStateWithLifecycle()
        export.message?.let { Text(it, color = Red, fontSize = 12.sp) }
        HorizontalDivider(color = Rule, modifier = Modifier.padding(vertical = 20.dp))
        Label(tr(R.string.edition))
        Text(tr(R.string.privacy_info), color = Muted, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp, bottom = 30.dp))
    }
}

@Composable fun RangeSelector(selected: String, options: List<String> = EXPORT_RANGES, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 10.dp)) {
        options.forEach { label -> FilterChip(selected = selected == label, onClick = { onSelect(label) },
            shape = RectangleShape, label = { Text(tr(rangeLabel(label)), fontSize = 10.sp) }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Ink, selectedLabelColor = Color.White)) }
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
        TextButton(onClick = onBack, enabled = !state.busy, contentPadding = PaddingValues(0.dp)) { Text(tr(R.string.back), fontSize = 11.sp) }
        Text(tr(R.string.report_title), fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.8).sp)
        SectionTitle("01", tr(R.string.select_session))
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = !state.busy && sessions.isNotEmpty(), shape = RectangleShape, modifier = Modifier.fillMaxWidth()) {
                Text(selected?.let { "#${it.id}  ${timestamp(it.startedAt, "MM.dd HH:mm:ss")}  /  ${if (it.endedAt == null) tr(R.string.live) else tr(R.string.saved)}" } ?: tr(R.string.select_recording), fontSize = 12.sp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                sessions.forEach { session -> DropdownMenuItem(text = { Text("#${session.id}  ${timestamp(session.startedAt, "yyyy.MM.dd HH:mm:ss")}", fontSize = 12.sp) },
                    onClick = { onSelect(session.id); expanded = false }) }
            }
        }
        RangeSelector(range, onSelect = { if (!state.busy) range = it })
        if (range == "Custom Range") {
            OutlinedTextField(start, { start = it }, label = { Text(tr(R.string.start_input)) }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(end, { end = it }, label = { Text(tr(R.string.end_input)) }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
        }
        Text(tr(R.string.range_explanation), fontSize = 11.sp, color = Muted, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 14.dp))
        Action(if (state.busy) tr(R.string.processing) else tr(R.string.generate_png), !state.busy && selected != null) { selected?.id?.let { onGenerate(it, range, start, end) } }
        state.message?.let { Text(it, color = Red, fontSize = 12.sp, modifier = Modifier.padding(vertical = 14.dp)) }
        state.image?.let { image ->
            SectionTitle("02", tr(R.string.image_preview))
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
                bitmap?.let { Image(it.asImageBitmap(), contentDescription = tr(R.string.preview_description), modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y }) }
            }
            image.report.notes.forEach { Text(LocalContext.current.reportNote(it), fontSize = 10.sp, color = Muted, modifier = Modifier.padding(top = 5.dp)) }
            Spacer(Modifier.height(16.dp))
            Action(tr(R.string.save_image), !state.busy, onClick = onSave)
            Spacer(Modifier.height(8.dp))
            Action(tr(R.string.share_image), !state.busy, red = true, onClick = onShare)
            Spacer(Modifier.height(28.dp))
        }
        if (sessions.isEmpty()) Text(tr(R.string.no_recording), modifier = Modifier.padding(vertical = 20.dp), color = Muted)
    }
}
