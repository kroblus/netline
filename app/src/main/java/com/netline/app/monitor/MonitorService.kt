package com.netline.app.monitor

import android.app.*
import android.app.AppOpsManager
import android.content.*
import android.content.pm.ServiceInfo
import android.net.TrafficStats
import android.os.*
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.netline.app.*
import com.netline.app.overlay.FloatingBallController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MonitorService : Service() {
    private val app get() = application as NetlineApp
    private val controller get() = app.monitor
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commands = Mutex()
    private var samplerJob: Job? = null
    private var sampler: CounterSampler? = null
    private var session: Long? = null
    private var lastPersistedAt = 0L
    private lateinit var ball: FloatingBallController
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val visible = intent?.action == Intent.ACTION_USER_PRESENT && controller.state.value.overlayEnabled
            controller.update { it.copy(overlayVisible = visible) }
        }
    }
    private val opsListener = AppOpsManager.OnOpChangedListener { op, pkg ->
        if (op == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && pkg == packageName) {
            Handler(mainLooper).post {
                if (!Settings.canDrawOverlays(this) && controller.state.value.overlayEnabled) dispatch(MonitorController.HIDE)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, localizedString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(7, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(7, notification())
        } catch (e: Exception) {
            controller.update { it.copy(recordingState = RecordingState.IDLE, overlayEnabled = false,
                error = localizedString(R.string.foreground_error, e.localizedMessage ?: "")) }
            stopSelf()
            return
        }
        ball = FloatingBallController(this, app.preferences, controller, scope)
        controller.serviceDispatch = ::dispatch
        androidx.core.content.ContextCompat.registerReceiver(this, screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        getSystemService(AppOpsManager::class.java).startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, opsListener)
        scope.launch { controller.state.map { Triple(it.recordingState, it.overlayEnabled, it.overlayVisible) }
            .distinctUntilChanged().collectLatest {
                ball.render(controller.state.value)
                notifications.notify(7, notification())
            } }
        scope.launch { app.language.collect {
            ball.languageChanged()
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, localizedString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
            notifications.notify(7, notification())
        } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (::ball.isInitialized) intent?.action?.let(::dispatch)
        return START_NOT_STICKY
    }
    private fun reading() = CounterReading(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
        TrafficStats.getTotalRxBytes(), TrafficStats.getTotalTxBytes())

    private fun dispatch(action: String) {
        scope.launch {
            commands.withLock {
                try {
                    when (action) {
                        MonitorController.START -> startRecording()
                        MonitorController.STOP -> stopRecording()
                        MonitorController.ENABLE -> {
                            if (!Settings.canDrawOverlays(this@MonitorService)) {
                                controller.update { it.copy(overlayEnabled = false) }
                                controller.fail(localizedString(R.string.permission_denied))
                            } else {
                                controller.update { it.copy(overlayEnabled = true) }
                                ball.show()
                            }
                        }
                        MonitorController.HIDE -> {
                            ball.hide()
                            controller.update { it.copy(overlayEnabled = false, overlayVisible = false) }
                        }
                        MonitorController.RESET -> ball.reset()
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (action == MonitorController.ENABLE) {
                        ball.hide()
                        controller.update { it.copy(overlayEnabled = false, overlayVisible = false) }
                    }
                    controller.fail(localizedString(R.string.operation_failed, localizedError(e)))
                    if (session == null) controller.update { it.copy(recordingState = RecordingState.IDLE) }
                }
                releaseIfIdle()
            }
        }
    }
    private suspend fun startRecording() {
        if (session != null) return
        controller.update { it.copy(recordingState = RecordingState.STARTING, error = null) }
        val baseline = reading()
        check(baseline.rx >= 0 && baseline.tx >= 0) { localizedString(R.string.counter_unsupported) }
        val id = app.repository.start(baseline.wall)
        session = id
        lastPersistedAt = baseline.wall
        sampler = CounterSampler(id, baseline)
        controller.update { it.copy(recordingState = RecordingState.RECORDING, activeSessionId = id, recent = emptyList()) }
        samplerJob = scope.launch {
            try {
                while (isActive) { delay(1000); persistReading() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                commands.withLock {
                    sampler = null
                    runCatching { app.repository.end(id, lastPersistedAt, true) }
                    session = null
                    controller.update { it.copy(recordingState = RecordingState.IDLE, activeSessionId = null,
                        error = localizedString(R.string.recording_failed, localizedError(e))) }
                    releaseIfIdle()
                }
            }
        }
    }
    private suspend fun persistReading() {
        val sample = sampler?.next(reading()) ?: return
        app.repository.append(sample)
        lastPersistedAt = maxOf(lastPersistedAt, sample.endedAt)
        controller.update { it.copy(recent = (it.recent + sample).takeLast(1800)) }
        check(sample.gapReason != "System clock changed") { localizedString(R.string.clock_changed) }
    }
    private suspend fun stopRecording() {
        val id = session ?: return
        controller.update { it.copy(recordingState = RecordingState.STOPPING) }
        samplerJob?.cancelAndJoin()
        samplerJob = null
        try {
            persistReading()
            app.repository.end(id, lastPersistedAt)
        } catch (e: Exception) {
            runCatching { app.repository.end(id, lastPersistedAt, true) }
            controller.fail(localizedString(R.string.stop_failed, localizedError(e)))
        } finally {
            sampler = null
            session = null
            controller.update { it.copy(recordingState = RecordingState.IDLE, activeSessionId = null) }
        }
    }
    private fun releaseIfIdle() {
        if (session == null && !controller.state.value.overlayEnabled) {
            controller.serviceDispatch = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
    private fun notification(): Notification {
        val state = controller.state.value
        val recording = state.recordingState == RecordingState.RECORDING || state.recordingState == RecordingState.STOPPING
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_netline)
            .setContentTitle(localizedString(if (recording) R.string.notification_recording else R.string.notification_idle))
            .setContentText(localizedString(if (recording) R.string.notification_recording_info else R.string.notification_idle_info))
            .setContentIntent(open).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
        if (recording) builder.addAction(0, localizedString(R.string.stop_recording), action(MonitorController.STOP, 1))
        if (state.overlayEnabled) builder.addAction(0, localizedString(R.string.hide_ball), action(MonitorController.HIDE, 2))
        return builder.build()
    }
    private fun action(value: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, MonitorService::class.java).setAction(value), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    override fun onBind(intent: Intent?) = null
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) { super.onConfigurationChanged(newConfig); if (::ball.isInitialized) ball.reposition() }
    override fun onDestroy() {
        val unfinished = session
        if (unfinished != null) {
            val end = lastPersistedAt
            (application as NetlineApp).applicationScope.launch { runCatching { app.repository.end(unfinished, end, true) } }
        }
        if (::ball.isInitialized) ball.hide()
        runCatching { unregisterReceiver(screenReceiver) }
        getSystemService(AppOpsManager::class.java).stopWatchingMode(opsListener)
        controller.serviceDispatch = null
        // Abrupt process death is repaired from committed rows on the next launch; no loop restart.
        controller.update { it.copy(recordingState = RecordingState.IDLE, activeSessionId = null,
            overlayEnabled = false, overlayVisible = false) }
        scope.cancel()
        super.onDestroy()
    }
    companion object { private const val CHANNEL = "netline_monitor" }
}
