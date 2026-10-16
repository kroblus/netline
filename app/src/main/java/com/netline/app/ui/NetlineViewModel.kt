package com.netline.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.netline.app.NetlineApp
import com.netline.app.R
import com.netline.app.localizedString
import com.netline.app.localizedError
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
    init { viewModelScope.launch { runCatching { app.repository.initialize() }.onFailure { app.monitor.fail(app.localizedString(R.string.database_error, app.localizedError(it))) } } }
    private val deleting = mutableSetOf<Long>()
    fun deleteSession(id: Long) {
        if (!deleting.add(id)) return
        viewModelScope.launch {
            try {
                if (app.repository.deleteSession(id)) {
                    if (export.value.image?.report?.sessionId == id) mutableExport.value = ExportState()
                    app.monitor.update { if (it.activeSessionId != id && it.recent.firstOrNull()?.sessionId == id) it.copy(recent = emptyList()) else it }
                    app.monitor.fail(app.localizedString(R.string.delete_success))
                } else app.monitor.fail(app.localizedString(R.string.delete_unavailable))
            } catch (e: Exception) { app.monitor.fail(app.localizedString(R.string.delete_failed, app.localizedError(e))) }
            finally { deleting.remove(id) }
        }
    }
    fun language(code: String) { viewModelScope.launch {
        app.preferences.language(code)
        if (!export.value.busy) mutableExport.value = ExportState()
    } }
    fun positionReset() { viewModelScope.launch { app.preferences.position(1f, .35f); app.monitor.request(com.netline.app.monitor.MonitorController.RESET) } }
    fun defaultRange(value: String) { viewModelScope.launch { app.preferences.range(value) } }
    fun clearCache() { viewModelScope.launch {
        val count = withContext(Dispatchers.IO) { ChartExportRenderer.cleanCache(app, true, export.value.image?.file) }
        mutableExport.update { it.copy(message = app.localizedString(R.string.cache_cleared, count)) }
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
                mutableExport.value = ExportState(message = app.localizedString(R.string.generate_failed, app.localizedError(e)))
            }
        }
    }
    fun save() {
        val image = export.value.image ?: return
        if (export.value.busy) return
        mutableExport.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { ImageSaveManager(app).save(image); mutableExport.update { it.copy(busy = false, message = app.localizedString(R.string.image_saved)) } }
            catch (e: Exception) { mutableExport.update { it.copy(busy = false, message = app.localizedString(R.string.save_failed, app.localizedError(e))) } }
        }
    }
}
