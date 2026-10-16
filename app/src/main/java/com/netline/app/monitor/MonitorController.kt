package com.netline.app.monitor

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.netline.app.data.Sample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class RecordingState { IDLE, STARTING, RECORDING, STOPPING }
data class MonitorState(
    val recordingState: RecordingState = RecordingState.IDLE,
    val activeSessionId: Long? = null,
    val overlayEnabled: Boolean = false,
    val overlayVisible: Boolean = false,
    val recent: List<Sample> = emptyList(),
    val error: String? = null
)

// All entry points call this on the main thread. Only MonitorService owns the sampler.
class MonitorController(private val context: Context) {
    private val mutable = MutableStateFlow(MonitorState())
    val state = mutable.asStateFlow()
    internal var serviceDispatch: ((String) -> Unit)? = null
    internal fun update(block: (MonitorState) -> MonitorState) = mutable.update(block)
    fun clearError() = update { it.copy(error = null) }
    fun toggle() = when (state.value.recordingState) {
        RecordingState.IDLE -> request(START)
        RecordingState.RECORDING -> request(STOP)
        else -> Unit
    }
    fun request(action: String) {
        val current = state.value
        when (action) {
            START -> {
                if (current.recordingState != RecordingState.IDLE) return
                update { it.copy(recordingState = RecordingState.STARTING, error = null) }
            }
            STOP -> {
                if (current.recordingState != RecordingState.RECORDING) return
                update { it.copy(recordingState = RecordingState.STOPPING) }
            }
            ENABLE -> {
                if (!Settings.canDrawOverlays(context)) { fail("请先授予悬浮窗权限 / Overlay permission required"); return }
                update { it.copy(overlayEnabled = true) }
            }
        }
        try {
            val dispatch = serviceDispatch
            if (dispatch != null) dispatch(action)
            else if (action == START || action == ENABLE) {
                ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java).setAction(action))
            } else update { it.copy(overlayEnabled = false, overlayVisible = false) }
        } catch (e: Exception) {
            update { it.copy(recordingState = if (it.activeSessionId == null) RecordingState.IDLE else RecordingState.RECORDING,
                overlayEnabled = if (action == ENABLE) false else it.overlayEnabled,
                error = "系统无法启动服务，请回到 NETLINE 重试：${e.localizedMessage}") }
        }
    }
    internal fun fail(message: String) = update { it.copy(error = message) }
    companion object {
        const val START = "com.netline.START"
        const val STOP = "com.netline.STOP"
        const val ENABLE = "com.netline.ENABLE"
        const val HIDE = "com.netline.HIDE"
        const val RESET = "com.netline.RESET"
    }
}
