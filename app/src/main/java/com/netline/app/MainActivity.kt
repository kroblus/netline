package com.netline.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.netline.app.monitor.MonitorController
import com.netline.app.ui.NetlineRoot

class MainActivity : ComponentActivity() {
    private val app get() = application as NetlineApp
    private var pendingOverlay = false
    private var afterNotification: (() -> Unit)? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        afterNotification?.invoke(); afterNotification = null
    }
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingOverlay) {
            pendingOverlay = false
            if (Settings.canDrawOverlays(this)) app.monitor.request(MonitorController.ENABLE)
            else Toast.makeText(this, "悬浮球需要单独授权；主界面记录仍可用", Toast.LENGTH_LONG).show()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingOverlay = savedInstanceState?.getBoolean("pendingOverlay") ?: false
        setContent { NetlineRoot(onToggle = { notifyThen { app.monitor.toggle() } }, onOverlay = ::overlay) }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("pendingOverlay", pendingOverlay); super.onSaveInstanceState(outState) }
    override fun onResume() {
        super.onResume()
        if (!Settings.canDrawOverlays(this) && app.monitor.state.value.overlayEnabled) app.monitor.request(MonitorController.HIDE)
    }
    private fun notifyThen(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            afterNotification = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else action()
    }
    private fun overlay(enabled: Boolean) {
        if (!enabled) { app.monitor.request(MonitorController.HIDE); return }
        notifyThen {
            if (Settings.canDrawOverlays(this)) app.monitor.request(MonitorController.ENABLE)
            else {
                pendingOverlay = true
                try { overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                catch (_: Exception) { pendingOverlay = false; Toast.makeText(this, "无法打开权限设置，请从系统应用设置授权", Toast.LENGTH_LONG).show() }
            }
        }
    }
}
