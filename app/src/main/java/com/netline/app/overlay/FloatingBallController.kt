package com.netline.app.overlay

import android.animation.ValueAnimator
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.provider.Settings
import android.view.*
import android.widget.*
import com.netline.app.MainActivity
import com.netline.app.R
import com.netline.app.localizedString
import com.netline.app.data.Preferences
import com.netline.app.monitor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class FloatingBallController(
    private val context: Context,
    private val preferences: Preferences,
    private val controller: MonitorController,
    private val scope: CoroutineScope
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val size = (48 * context.resources.displayMetrics.density).toInt()
    private var view: FloatingBallView? = null
    private var gesture: FloatingBallGestureHandler? = null
    private var menu: PopupWindow? = null
    private var animator: ValueAnimator? = null
    private var relativeX = 1f
    private var relativeY = .35f
    private var menuDismissedAt = -1000L
    private var dismissGesture = false
    private val params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
        title = "NETLINE Floating Ball"
    }
    suspend fun show() {
        if (view != null) return
        check(Settings.canDrawOverlays(context)) { "Overlay permission unavailable" }
        val saved = preferences.settings.first()
        relativeX = saved.x; relativeY = saved.y
        val ball = FloatingBallView(context)
        gesture = FloatingBallGestureHandler(ViewConfiguration.get(context).scaledTouchSlop,
            click = { if (menu?.isShowing == true) menu?.dismiss() else ball.performClick() }, longClick = ::showMenu,
            drag = { dx, dy ->
                animator?.cancel()
                params.x += dx.toInt(); params.y += dy.toInt(); clamp(); update()
            }, release = ::snap)
        ball.setOnClickListener { controller.toggle() }
        ball.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                dismissGesture = menu?.isShowing == true || android.os.SystemClock.uptimeMillis() - menuDismissedAt < 250
            }
            if (dismissGesture) {
                menu?.dismiss()
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) dismissGesture = false
                true
            } else gesture?.touch(event) ?: true
        }
        ball.setOnLongClickListener { showMenu(); true }
        position()
        wm.addView(ball, params)
        view = ball
        ball.state = controller.state.value.recordingState
        controller.update { it.copy(overlayVisible = true) }
    }
    private fun safeBounds(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = wm.maximumWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return Rect(insets.left, insets.top, (metrics.bounds.width() - insets.right - size).coerceAtLeast(insets.left),
                (metrics.bounds.height() - insets.bottom - size).coerceAtLeast(insets.top))
        }
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        val insets = view?.rootWindowInsets
        val cutout = insets?.displayCutout
        @Suppress("DEPRECATION")
        return Rect(maxOf(insets?.systemWindowInsetLeft ?: 0, cutout?.safeInsetLeft ?: 0),
            maxOf(insets?.systemWindowInsetTop ?: 0, cutout?.safeInsetTop ?: 0),
            metrics.widthPixels - size - maxOf(insets?.systemWindowInsetRight ?: 0, cutout?.safeInsetRight ?: 0),
            metrics.heightPixels - size - maxOf(insets?.systemWindowInsetBottom ?: 0, cutout?.safeInsetBottom ?: 0))
    }
    private fun position() {
        val b = safeBounds()
        params.x = (b.left + (b.right - b.left) * relativeX.coerceIn(0f, 1f)).toInt()
        params.y = (b.top + (b.bottom - b.top) * relativeY.coerceIn(0f, 1f)).toInt()
        clamp()
    }
    private fun clamp() { val b = safeBounds(); params.x = params.x.coerceIn(b.left, b.right); params.y = params.y.coerceIn(b.top, b.bottom) }
    private fun update() {
        val ball = view ?: return
        try { wm.updateViewLayout(ball, params) }
        catch (_: RuntimeException) {
            hide()
            controller.update { it.copy(overlayEnabled = false, overlayVisible = false,
                error = context.localizedString(R.string.overlay_unavailable)) }
            controller.request(MonitorController.HIDE)
        }
    }
    private fun snap() {
        val b = safeBounds()
        val target = if (params.x - b.left < b.right - params.x) b.left else b.right
        animator = ValueAnimator.ofInt(params.x, target).apply {
            duration = 140
            addUpdateListener { params.x = it.animatedValue as Int; update() }
            start()
        }
        relativeX = if (target == b.left) 0f else 1f
        relativeY = (params.y - b.top).toFloat() / (b.bottom - b.top).coerceAtLeast(1)
        scope.launch { preferences.position(relativeX, relativeY) }
    }
    private fun showMenu() {
        val ball = view ?: return
        if (menu?.isShowing == true) return
        val layout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(244, 243, 239)) }
        fun item(label: String, action: () -> Unit) {
            layout.addView(TextView(context).apply {
                text = label; setTextColor(Color.rgb(32, 32, 32)); textSize = 12f
                setPadding(24, 24, 24, 24); minHeight = (48 * resources.displayMetrics.density).toInt()
                setOnClickListener { menu?.dismiss(); action() }
            })
        }
        item(context.localizedString(R.string.open_netline)) { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        item(context.localizedString(R.string.hide_ball)) { controller.request(MonitorController.HIDE) }
        menu = PopupWindow(layout, (224 * context.resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            setOnDismissListener { menuDismissedAt = android.os.SystemClock.uptimeMillis() }
            windowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            elevation = 0f
            val b = safeBounds()
            showAtLocation(ball, Gravity.TOP or Gravity.LEFT,
                (params.x - width + size).coerceIn(b.left, (b.right + size - width).coerceAtLeast(b.left)),
                (params.y + size).coerceAtMost((b.bottom - (100 * context.resources.displayMetrics.density).toInt()).coerceAtLeast(b.top)))
        }
    }
    fun render(state: MonitorState) { view?.state = state.recordingState }
    fun languageChanged() { menu?.dismiss(); view?.state = controller.state.value.recordingState }
    fun reposition() { menu?.dismiss(); animator?.cancel(); position(); update() }
    suspend fun reset() { relativeX = 1f; relativeY = .35f; preferences.position(relativeX, relativeY); reposition() }
    fun hide() {
        animator?.cancel(); animator = null
        gesture?.cancel(); gesture = null
        menu?.dismiss(); menu = null
        view?.let { runCatching { wm.removeViewImmediate(it) } }
        view = null
        controller.update { if (it.overlayVisible) it.copy(overlayVisible = false) else it }
    }
}
