package com.netline.app

import android.content.*
import android.provider.Settings
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.netline.app.export.*
import com.netline.app.monitor.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MonitorDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<NetlineApp>()
    private val device = UiDevice.getInstance(instrumentation)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue(message, condition())
    }
    @Test fun floatingBallAndSingleSamplerOnAndroid16() = runBlocking {
        assumeTrue("Grant overlay permission in Settings before this real-device test", Settings.canDrawOverlays(app))
        ActivityScenario.launch(MainActivity::class.java).use {
            main { app.monitor.request(MonitorController.ENABLE) }
            waitFor("overlay shown") { app.monitor.state.value.overlayVisible }
            device.pressHome()
            val idle = device.wait(Until.findObject(By.desc("开始记录")), 5000)
            assertNotNull("Visible over launcher", idle)
            idle.click()
            waitFor("real session started") { app.monitor.state.value.recordingState == RecordingState.RECORDING }
            val first = app.monitor.state.value.activeSessionId!!
            SystemClock.sleep(2200)
            assertTrue(app.database.traffic().samples(first).isNotEmpty())
            assertNotNull(device.wait(Until.findObject(By.desc("停止记录")), 3000))
            device.findObject(By.desc("停止记录")).click()
            waitFor("stopped and still visible") { app.monitor.state.value.recordingState == RecordingState.IDLE && app.monitor.state.value.overlayVisible }
            assertNotNull(app.database.traffic().session(first)!!.endedAt)
            val beforeDrag = app.repository.sessions.first().size
            val ball = device.findObject(By.desc("开始记录"))
            val bounds = ball.visibleBounds
            device.swipe(bounds.centerX(), bounds.centerY(), device.displayWidth / 2, device.displayHeight / 2, 25)
            SystemClock.sleep(500)
            assertEquals(RecordingState.IDLE, app.monitor.state.value.recordingState)
            assertEquals(beforeDrag, app.repository.sessions.first().size)
            val moved = device.findObject(By.desc("开始记录")).visibleBounds
            device.swipe(moved.centerX(), moved.centerY(), moved.centerX(), moved.centerY(), 100)
            assertNotNull(device.wait(Until.findObject(By.text("OPEN NETLINE")), 3000))
            assertEquals(RecordingState.IDLE, app.monitor.state.value.recordingState)
            device.click(moved.centerX(), moved.centerY())
            SystemClock.sleep(500)
            assertFalse("Outside touch dismisses menu", device.hasObject(By.text("OPEN NETLINE")))
            assertEquals("Dismissing menu must not start recording", RecordingState.IDLE, app.monitor.state.value.recordingState)
            device.swipe(moved.centerX(), moved.centerY(), moved.centerX(), moved.centerY(), 100)
            assertNotNull(device.wait(Until.findObject(By.text("OPEN NETLINE")), 3000))
            device.findObject(By.text("OPEN NETLINE")).click()
            assertTrue(device.wait(Until.hasObject(By.text("NETLINE")), 5000))
            main { repeat(30) { app.monitor.toggle() } }
            waitFor("only one new session") { app.monitor.state.value.recordingState == RecordingState.RECORDING }
            assertEquals(beforeDrag + 1, app.repository.sessions.first().size)
            val second = app.monitor.state.value.activeSessionId!!
            assertNotEquals(first, second)
            SystemClock.sleep(1300)
            main { app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            SystemClock.sleep(1200)
            assertNotEquals("Browser opened outside NETLINE", "com.netline.app", device.currentPackageName)
            assertNotNull("Visible over browser", device.wait(Until.findObject(By.desc("停止记录")), 5000))
            main { app.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            SystemClock.sleep(1000)
            assertEquals("com.android.settings", device.currentPackageName)
            assertNotNull("Visible over another app", device.wait(Until.findObject(By.desc("停止记录")), 5000))
            try {
                device.setOrientationLeft()
                SystemClock.sleep(1400)
                val rotated = device.wait(Until.findObject(By.desc("停止记录")), 5000)!!.visibleBounds
                assertTrue(rotated.left >= 0 && rotated.right <= device.displayWidth)
                assertTrue(rotated.top >= 0 && rotated.bottom <= device.displayHeight)
            } finally { device.setOrientationNatural(); device.unfreezeRotation() }
            val beforeExport = app.database.traffic().samples(second).size
            val activeReport = app.repository.report(second, null, null)
            val png = ChartExportRenderer(app).render(activeReport)
            SystemClock.sleep(1200)
            assertTrue("Sampling continues during export", app.database.traffic().samples(second).size > beforeExport)
            assertEquals(RecordingState.RECORDING, app.monitor.state.value.recordingState)
            png.file.delete()
            main { app.monitor.request(MonitorController.HIDE) }
            waitFor("hide leaves recording running") { !app.monitor.state.value.overlayVisible && app.monitor.state.value.recordingState == RecordingState.RECORDING }
            assertNull(app.database.traffic().session(second)!!.endedAt)
            device.openNotification()
            val notificationTitle = device.wait(Until.findObject(By.text("NETLINE · 正在记录")), 5000)
            assertNotNull(notificationTitle)
            if (!device.hasObject(By.text("停止记录"))) {
                var ancestor = notificationTitle
                for (level in 0 until 8) {
                    val expand = ancestor?.findObject(By.res("android", "expand_button"))
                    if (expand != null) { expand.click(); break }
                    ancestor = ancestor?.parent
                }
            }
            val stop = device.wait(Until.findObject(By.text("停止记录")), 5000)
            assertNotNull("Notification exposes stop action", stop)
            stop.click()
            waitFor("notification path stops and persists") { app.monitor.state.value.recordingState == RecordingState.IDLE }
            device.pressBack()
            assertNotNull(app.database.traffic().session(second)!!.endedAt)
            waitFor("idle host released") { app.monitor.serviceDispatch == null }
            Unit
        }
    }

    @Test fun realTrafficReportCrossUidReadGrant() = runBlocking {
        val ended = app.repository.sessions.first().firstOrNull { it.endedAt != null && app.database.traffic().samples(it.id).any { s -> s.valid } }
        assumeTrue("Record real traffic before receiver test", ended != null)
        val data = app.repository.report(ended!!.id, null, null)
        val image = ChartExportRenderer(app).render(data)
        val uri = FileProvider.getUriForFile(app, "com.netline.app.exports", image.file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(app.contentResolver, "NETLINE report", uri)
            component = ComponentName("com.netline.app.test", "com.netline.app.ReportReceiverActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            main { app.startActivity(intent) }
            assertTrue("Separate receiving app can decode PNG", device.wait(Until.hasObject(By.text("PNG OK 1080 x 1920")), 8000))
            image.file.copyTo(File(app.getExternalFilesDir(null), "REAL_TRAFFIC_REPORT.png"), overwrite = true)
            device.pressBack()
            Unit
        }
    }
}
