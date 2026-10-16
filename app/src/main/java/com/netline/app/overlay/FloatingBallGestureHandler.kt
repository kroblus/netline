package com.netline.app.overlay

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.hypot

class FloatingBallGestureHandler(
    private val slop: Int,
    private val click: () -> Unit,
    private val longClick: () -> Unit,
    private val drag: (Float, Float) -> Unit,
    private val release: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var x = 0f
    private var y = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moving = false
    private var longPressed = false
    private val longPress = Runnable { if (!moving) { longPressed = true; longClick() } }
    fun touch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x = event.rawX; y = event.rawY; lastX = x; lastY = y
                moving = false; longPressed = false
                handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (!longPressed && hypot(event.rawX - x, event.rawY - y) > slop) {
                    moving = true; handler.removeCallbacks(longPress)
                }
                if (moving) drag(event.rawX - lastX, event.rawY - lastY)
                lastX = event.rawX; lastY = event.rawY
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (moving) release() else if (!longPressed) click()
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }
    fun cancel() { handler.removeCallbacks(longPress); moving = false; longPressed = false }
}
