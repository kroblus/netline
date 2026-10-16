package com.netline.app.overlay

import android.content.Context
import android.graphics.*
import android.view.View
import com.netline.app.monitor.RecordingState

class FloatingBallView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playPath = Path()
    var state: RecordingState = RecordingState.IDLE
        set(value) {
            field = value
            contentDescription = when (value) {
                RecordingState.IDLE -> "开始记录"
                RecordingState.RECORDING -> "停止记录"
                else -> "操作处理中"
            }
            invalidate()
        }
    init { isClickable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; state = RecordingState.IDLE }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f; val cy = height / 2f; val r = width / 2f
        paint.color = if (state == RecordingState.RECORDING || state == RecordingState.STOPPING) 0xFFE3342F.toInt() else 0xFF242424.toInt()
        canvas.drawCircle(cx, cy, r - 1, paint)
        paint.color = Color.WHITE
        when (state) {
            RecordingState.IDLE -> {
                playPath.reset()
                playPath.moveTo(cx - r * .22f, cy - r * .38f); playPath.lineTo(cx + r * .4f, cy)
                playPath.lineTo(cx - r * .22f, cy + r * .38f); playPath.close()
                canvas.drawPath(playPath, paint)
            }
            RecordingState.RECORDING -> canvas.drawRect(cx - r * .28f, cy - r * .28f, cx + r * .28f, cy + r * .28f, paint)
            else -> { paint.textSize = r * .8f; paint.textAlign = Paint.Align.CENTER; canvas.drawText("…", cx, cy + r * .1f, paint) }
        }
    }
}
