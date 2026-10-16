package com.netline.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netline.app.NetlineApp
import com.netline.app.data.AppSettings
import com.netline.app.export.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ExportState(val busy: Boolean = false, val image: ExportImage? = null, val message: String? = null)
val EXPORT_RANGES = listOf("Full Session", "60 Seconds", "5 Minutes", "15 Minutes", "1 Hour", "Custom Range")

class NetlineViewModel(application: Application) : AndroidViewModel(application) {
    val app = application as NetlineApp
    val monitor = app.monitor.state
    val sessions = app.repository.sessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val settings = app.preferences.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private val mutableExport = MutableStateFlow(ExportState())
    val export = mutableExport.asStateFlow()
    init { viewModelScope.launch { runCatching { app.repository.initialize() }.onFailure { app.monitor.fail("数据库不可用：${it.localizedMessage}") } } }
    fun positionReset() { viewModelScope.launch { app.preferences.position(1f, .35f); app.monitor.request(com.netline.app.monitor.MonitorController.RESET) } }
    fun defaultRange(value: String) { viewModelScope.launch { app.preferences.range(value) } }
    fun clearCache() { viewModelScope.launch {
        val count = withContext(Dispatchers.IO) { ChartExportRenderer.cleanCache(app, true, export.value.image?.file) }
        mutableExport.update { it.copy(message = "已清理 $count 张临时图片（保留当前预览）") }
    } }
    fun clearExport() { if (!export.value.busy) mutableExport.value = ExportState() }
    fun generate(id: Long, range: String, start: String, end: String) {
        if (export.value.busy) return
        mutableExport.value = ExportState(busy = true)
        viewModelScope.launch {
            try {
                fun parse(text: String) = LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]"))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val span = when(range) { "60 Seconds" -> 60_000L; "5 Minutes" -> 300_000L; "15 Minutes" -> 900_000L; "1 Hour" -> 3_600_000L; else -> null }
                val data = app.repository.report(id, if (range == "Custom Range") parse(start) else null,
                    if (range == "Custom Range") parse(end) else null, span)
                mutableExport.value = ExportState(image = ChartExportRenderer(app).render(data))
            } catch (e: Exception) {
                mutableExport.value = ExportState(message = "无法生成：${e.localizedMessage ?: "请检查时间范围"}")
            }
        }
    }
    fun save() {
        val image = export.value.image ?: return
        if (export.value.busy) return
        mutableExport.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { ImageSaveManager(app).save(image); mutableExport.update { it.copy(busy = false, message = "已保存到 Pictures/NETLINE/") } }
            catch (e: Exception) { mutableExport.update { it.copy(busy = false, message = "保存失败：${e.localizedMessage}") } }
        }
    }
}
