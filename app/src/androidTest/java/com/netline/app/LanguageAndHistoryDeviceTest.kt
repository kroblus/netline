package com.netline.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.netline.app.monitor.*
import com.netline.app.overlay.FloatingBallView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class LanguageAndHistoryDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<NetlineApp>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun waitFor(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        assertTrue(message, predicate())
    }
    private fun click(text: String) {
        val target = device.wait(Until.findObject(By.text(text)), 7000)
        assertNotNull("Visible text: $text", target)
        target.click()
    }
    @Test fun compactBallDraws40DpInside48DpTouchTarget() {
        val density = app.resources.displayMetrics.density
        val size = (48 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        try {
            main { FloatingBallView(app).apply { layout(0, 0, size, size); draw(Canvas(bitmap)) } }
            val colored = (0 until size).filter { android.graphics.Color.alpha(bitmap.getPixel(it, size / 2)) > 0 }
            assertTrue(abs(colored.size - 40 * density) <= 3)
            assertEquals(0, android.graphics.Color.alpha(bitmap.getPixel(0, size / 2)))
        } finally { bitmap.recycle() }
    }

    @Test fun switchingLanguagesKeepsRecordingAndLongPressDeletesOnlySelectedSession() = runBlocking {
        app.repository.initialize()
        app.preferences.language("en")
        waitFor("English ready") { app.language.value == "en" }
        val before = app.repository.sessions.first().map { it.id }.toSet()
        var own: Long? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                assertEquals(RecordingState.IDLE, app.monitor.state.value.recordingState)
                click("START RECORDING")
                waitFor("Recording started") { app.monitor.state.value.recordingState == RecordingState.RECORDING }
                own = app.monitor.state.value.activeSessionId!!
                val id = own!!
                click("SETTINGS")
                click("中文")
                waitFor("Chinese persisted") { app.language.value == "zh" }
                assertEquals("zh", app.preferences.settings.first().language)
                assertNotNull(device.wait(Until.findObject(By.text("悬浮控制")), 5000))
                scenario.recreate()
                assertNotNull(device.wait(Until.findObject(By.text("设置")), 5000))
                assertEquals(id, app.monitor.state.value.activeSessionId)
                main { app.monitor.request(MonitorController.ENABLE) }
                waitFor("Chinese overlay") { app.monitor.state.value.overlayVisible }
                val ball = device.wait(Until.findObject(By.desc("停止记录")), 5000)
                assertNotNull(ball)
                assertTrue(abs(ball.visibleBounds.width() - 48 * app.resources.displayMetrics.density) <= 2)
                click("English")
                waitFor("English applied") { app.language.value == "en" }
                assertNotNull(device.wait(Until.findObject(By.desc("STOP RECORDING")), 5000))
                device.openNotification()
                assertNotNull(device.wait(Until.findObject(By.text("NETLINE · Recording")), 5000))
                device.pressBack()
                click("中文")
                waitFor("Chinese reapplied") { app.language.value == "zh" }
                click("历史")
                val row = app.localizedString(R.string.session_share, id.toString().padStart(4, '0'))
                val activeRow = device.wait(Until.findObject(By.textContains(row)), 5000)
                assertNotNull("Active session row: $row", activeRow)
                activeRow.longClick()
                assertNotNull(device.wait(Until.findObject(By.text("停止记录后可删除")), 5000))
                assertFalse(app.repository.deleteSession(id))
                device.pressBack()
                main { app.monitor.request(MonitorController.HIDE); app.monitor.request(MonitorController.STOP) }
                waitFor("Stopped") { app.monitor.state.value.recordingState == RecordingState.IDLE }
                waitFor("Session ended") { runBlocking { app.database.traffic().session(id)?.endedAt != null } }
                device.wait(Until.findObject(By.textContains(row)), 5000)!!.longClick()
                click("删除记录")
                click("取消")
                assertNotNull(app.database.traffic().session(id))
                device.wait(Until.findObject(By.textContains(row)), 5000)!!.longClick()
                click("删除记录")
                click("删除")
                waitFor("Deleted through UI") { runBlocking { app.database.traffic().session(id) == null } }
                assertTrue(app.database.traffic().samples(id).isEmpty())
                assertTrue(app.database.traffic().aggregates(id).isEmpty())
                assertEquals(before, app.repository.sessions.first().map { it.id }.toSet())
                own = null
            } finally {
                own?.let { id ->
                    if (app.monitor.state.value.activeSessionId == id) {
                        main { app.monitor.request(MonitorController.HIDE); app.monitor.request(MonitorController.STOP) }
                        waitFor("Cleanup recording") { app.monitor.state.value.recordingState == RecordingState.IDLE }
                    }
                    app.repository.deleteSession(id)
                }
            }
        }
    }
}
