package com.netline.app.export

import android.content.Context
import android.graphics.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class ExportImage(val file: File, val report: ChartPlotData)

class ChartExportRenderer(private val context: Context) {
    suspend fun render(data: ChartPlotData): ExportImage = withContext(Dispatchers.Default) {
        val directory = File(context.cacheDir, "reports").apply { mkdirs() }
        cleanCache(context)
        val file = File(directory, "NETLINE_${timestamp(data.from, "yyyyMMdd_HHmmss")}_${timestamp(data.to, "HHmmss")}_${UUID.randomUUID()}.png")
        val bitmap = try { Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888) }
            catch (e: OutOfMemoryError) { throw IllegalStateException("可用内存不足，请关闭其他应用后重试", e) }
        try {
            draw(Canvas(bitmap), data)
            file.outputStream().buffered().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encoding failed" } }
            ExportImage(file, data)
        } catch (e: Exception) { file.delete(); throw e }
        finally { bitmap.recycle() }
    }

    private fun draw(c: Canvas, d: ChartPlotData) {
        val ink = Color.rgb(32, 32, 32); val red = Color.rgb(227, 52, 47); val grey = Color.rgb(107, 107, 101)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun text(value: String, x: Float, y: Float, size: Float, color: Int = ink, bold: Boolean = false) {
            p.color = color; p.textSize = size; p.style = Paint.Style.FILL
            p.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            c.drawText(value, x, y, p)
        }
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float = 2f) {
            p.color = color; p.strokeWidth = width; c.drawLine(x1, y1, x2, y2, p)
        }
        c.drawColor(Color.rgb(244, 243, 239))
        p.color = red; c.drawRect(64f, 64f, 88f, 146f, p)
        text("NETLINE", 110f, 143f, 88f, bold = true)
        text("01 / NETWORK TRAFFIC REPORT", 64f, 203f, 25f)
        line(64f, 239f, 1016f, 239f, ink, 3f)
        text(timestamp(d.from, "yyyy.MM.dd"), 64f, 305f, 40f, bold = true)
        text("START / END · ${java.time.ZoneId.systemDefault().id}", 64f, 350f, 22f, grey)
        text("${timestamp(d.from)} — ${timestamp(d.to)}", 64f, 402f, 44f)
        if (timestamp(d.from, "yyyyMMdd") != timestamp(d.to, "yyyyMMdd")) text("END DATE ${timestamp(d.to, "yyyy.MM.dd")}", 64f, 440f, 21f, grey)
        text("NETWORK SPEED OVER TIME", 64f, 496f, 30f, bold = true)
        text("— DOWNLOAD", 64f, 541f, 22f)
        text("— UPLOAD", 340f, 541f, 22f, red)
        val left = 164f; val right = 1016f; val top = 610f; val bottom = 1010f
        val peak = maxOf(d.statistics.peakDown.value, d.statistics.peakUp.value, 1.0) * 1.12
        val unit = when { peak >= 1024 * 1024 -> 1024.0 * 1024; peak >= 1024 -> 1024.0; else -> 1.0 }
        text(when (unit) { 1048576.0 -> "MB/s"; 1024.0 -> "KB/s"; else -> "B/s" }, 64f, 590f, 19f, grey)
        fun x(at: Long) = left + ((at - d.from).toDouble() / (d.to - d.from) * (right - left)).toFloat()
        fun y(value: Double) = bottom - (value / peak * (bottom - top)).toFloat()
        repeat(5) { index ->
            val y = top + index * (bottom - top) / 4
            line(left, y, right, y, Color.rgb(211, 211, 205), 1f)
            text(String.format(java.util.Locale.US, "%.1f", peak * (4 - index) / 4 / unit), 64f, y + 7, 20f, grey)
        }
        for (segment in ChartExportDataBuilder.segments(d, 852)) {
            for ((color, isDown) in listOf(ink to true, red to false)) {
                val path = Path()
                segment.forEachIndexed { i, v ->
                    val yy = y(if (isDown) v.down else v.up)
                    if (i == 0) path.moveTo(x(v.end), yy) else path.lineTo(x(v.end), yy)
                }
                p.color = color; p.strokeWidth = 3f; p.style = Paint.Style.STROKE; c.drawPath(path, p); p.style = Paint.Style.FILL
                if (segment.size == 1) c.drawCircle(x(segment[0].end), y(if (isDown) segment[0].down else segment[0].up), 3f, p)
            }
        }
        d.intervals.filter { it.aggregated }.forEach { v ->
            // A summary is an envelope, never a fabricated second-level line.
            val middle = x((v.start + v.end) / 2)
            listOf(Triple(ink, v.lowestDown, v.peakDown), Triple(red, v.lowestUp, v.peakUp)).forEach { (color, low, high) ->
                if (low != null) line(middle, y(low.value), middle, y(high.value), color, 2f)
            }
            p.color = ink; c.drawCircle(middle, y(v.down), 3f, p)
            p.color = red; c.drawCircle(middle, y(v.up), 3f, p)
        }
        repeat(3) { i ->
            val at = d.from + (d.to - d.from) * i / 2
            val tx = when(i) { 0 -> left; 1 -> (left + right) / 2 - 62; else -> right - 125 }
            text(timestamp(at), tx, 1055f, 22f, grey)
        }
        val marks = listOf("D PEAK" to d.statistics.peakDown, "D LOW" to d.statistics.lowestDown,
            "U PEAK" to d.statistics.peakUp, "U LOW" to d.statistics.lowestUp)
        marks.forEachIndexed { i, (label, extreme) ->
            val col = i % 2; val row = i / 2
            val tx = 64f + col * 490f; val ty = 1110f + row * 52f
            val color = if (i >= 2) red else ink
            if (extreme?.at != null) {
                p.color = color; c.drawCircle(x(extreme.at), y(extreme.value), 7f, p)
                text("${i + 1}", (x(extreme.at) + 10).coerceAtMost(995f), (y(extreme.value) - 12 - i * 12).coerceAtLeast(top), 20f, color, true)
            }
            text("${i + 1}  $label  ${extreme?.let { speed(it.value) } ?: "N/A"}", tx, ty, 23f, color, true)
            text(extreme?.at?.let { timestamp(it) } ?: "TIME N/A", tx, ty + 23f, 19f, grey)
        }
        line(64f, 1220f, 1016f, 1220f, ink, 3f)
        val s = d.statistics
        val cells = listOf("PEAK DOWN" to speed(s.peakDown.value), "LOWEST DOWN" to (s.lowestDown?.let { speed(it.value) } ?: "N/A"),
            "AVG DOWN" to speed(s.averageDown), "PEAK UP" to speed(s.peakUp.value),
            "LOWEST UP" to (s.lowestUp?.let { speed(it.value) } ?: "N/A"), "AVG UP" to speed(s.averageUp),
            "TOTAL RECEIVED" to bytes(s.totalReceived), "TOTAL SENT" to bytes(s.totalSent), "DURATION" to duration(s.requestedMs))
        cells.forEachIndexed { index, (label, value) ->
            val tx = 64f + index % 3 * 324f; val ty = 1273f + index / 3 * 116f
            text(label, tx, ty, 20f, grey)
            text(value, tx, ty + 47f, 34f, if (index in 3..5) red else ink, true)
        }
        line(64f, 1585f, 1016f, 1585f, ink, 2f)
        text("VALID SAMPLED TIME  ${duration(s.validMs)} / ${duration(s.requestedMs)}", 64f, 1630f, 23f, bold = true)
        val countLabel = if (d.intervals.any { !it.coverageKnown }) "N/A" else s.count.toString()
        text("$countLabel valid intervals · ${if (d.intervals.any { it.aggregated }) "minute summaries included" else "observed counter intervals"}", 64f, 1672f, 22f, grey)
        text("Gaps are unobserved. Boundary intervals are excluded, never estimated.", 64f, 1715f, 21f, grey)
        if (d.intervals.any { it.aggregated }) text("Range bars = minute extrema; no reconstructed second-level curve.", 64f, 1750f, 21f, grey)
        if (d.intervals.any { !it.coverageKnown }) text("Legacy coverage / extrema may be unavailable (N/A).", 64f, 1780f, 18f, grey)
        text("Generated by NETLINE", 64f, 1820f, 25f, bold = true)
        text("Traffic recorded on this device. Binary units: 1 KB = 1024 B.", 64f, 1858f, 20f, grey)
        text("设备与网络统计可能存在系统计数器相关限制。", 64f, 1890f, 20f, grey)
    }
    companion object {
        fun cleanCache(context: Context, all: Boolean = false, keep: File? = null): Int {
            val files = File(context.cacheDir, "reports").listFiles()?.filter { it != keep }?.sortedByDescending { it.lastModified() } ?: return 0
            var removed = 0
            files.forEachIndexed { index, file ->
                if (all || index >= 11 || System.currentTimeMillis() - file.lastModified() > 86_400_000) if (file.delete()) removed++
            }
            return removed
        }
    }
}
