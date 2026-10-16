package com.netline.app

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale

fun defaultLanguage(): String = if (Locale.getDefault().language == "zh") "zh" else "en"
fun Context.localizedContext(language: String = (applicationContext as NetlineApp).language.value): Context =
    createConfigurationContext(Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) })
fun Context.localizedString(@StringRes id: Int, vararg args: Any): String = localizedContext().getString(id, *args)

fun rangeLabel(range: String): Int = when (range) {
    "60 Seconds" -> R.string.range_60
    "5 Minutes" -> R.string.range_5
    "15 Minutes" -> R.string.range_15
    "1 Hour" -> R.string.range_hour
    "Custom Range" -> R.string.range_custom
    else -> R.string.range_full
}
fun Context.reportNote(note: String): String = getString(when (note) {
    "Minute summaries: ranges show exact stored extrema; second-level curve unavailable." -> R.string.note_summary
    "Partial minute excluded: no invented boundary bytes." -> R.string.note_partial
    "Legacy summary: unavailable minima / timestamps / coverage are marked N/A." -> R.string.note_legacy
    "Interrupted samples are gaps, not zero traffic." -> R.string.note_gaps
    else -> R.string.note_boundaries
})
fun Context.localizedError(error: Throwable): String {
    val id = when (error.message) {
        "时间范围无效 / End must follow start" -> R.string.error_range
        "请选择单个会话内的时间范围" -> R.string.error_single_session
        "此范围没有有效采样；边界区间不按比例虚构流量 / No valid intervals" -> R.string.error_no_data
        "记录不存在 / Session not found" -> R.string.error_missing_session
        "结束时间超出已确认数据 / End exceeds persisted data" -> R.string.error_unpersisted
        "可用内存不足，请关闭其他应用后重试" -> R.string.error_memory
        "无法创建相册图片" -> R.string.error_create_image
        "相册发布失败" -> R.string.error_publish_image
        "临时图片已清理，请重新生成" -> R.string.error_cache_missing
        "没有可接收图片的应用" -> R.string.error_no_receiver
        else -> null
    }
    return if (id != null) localizedString(id) else error.localizedMessage ?: localizedString(R.string.error_unknown)
}
