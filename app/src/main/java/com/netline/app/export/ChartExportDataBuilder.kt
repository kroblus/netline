package com.netline.app.export

import com.netline.app.data.*

data class Extremum(val value: Double, val at: Long?)
data class ChartInterval(
    val start: Long, val end: Long, val validMs: Long,
    val received: Long, val sent: Long,
    val peakDown: Extremum, val peakUp: Extremum,
    val lowestDown: Extremum?, val lowestUp: Extremum?,
    val aggregated: Boolean = false, val coverageKnown: Boolean = true,
    val count: Long = 1
) {
    val down get() = received * 1000.0 / validMs
    val up get() = sent * 1000.0 / validMs
}
data class ChartReportStatistics(
    val peakDown: Extremum, val peakUp: Extremum,
    val lowestDown: Extremum?, val lowestUp: Extremum?,
    val averageDown: Double, val averageUp: Double,
    val totalReceived: Long, val totalSent: Long,
    val validMs: Long, val requestedMs: Long, val count: Long
)
data class ChartPlotData(
    val sessionId: Long, val from: Long, val to: Long,
    val intervals: List<ChartInterval>, val statistics: ChartReportStatistics,
    val notes: List<String>
)

object ChartExportDataBuilder {
    fun fromSamples(samples: List<Sample>, from: Long, to: Long): ChartPlotData? =
        runCatching { build(Session(id = samples.firstOrNull()?.sessionId ?: 0, startedAt = from), samples, emptyList(), from, to) }.getOrNull()

    fun build(session: Session, raw: List<Sample>, summaries: List<MinuteAggregate>, from: Long, to: Long): ChartPlotData {
        require(to > from) { "时间范围无效 / End must follow start" }
        require(from >= session.startedAt && (session.endedAt == null || to <= session.endedAt)) { "请选择单个会话内的时间范围" }
        val notes = mutableListOf<String>()
        val rawBuckets = raw.mapTo(mutableSetOf()) { it.endedAt / 60_000 }
        val intervals = raw.asSequence().filter { it.valid && it.durationMs > 0 && it.startedAt >= from && it.endedAt <= to }
            .map { ChartInterval(it.startedAt, it.endedAt, it.durationMs, it.received, it.sent,
                Extremum(it.down, it.endedAt), Extremum(it.up, it.endedAt),
                Extremum(it.down, it.endedAt), Extremum(it.up, it.endedAt)) }.toMutableList()
        val historical = summaries.filter { it.bucket !in rawBuckets && it.endedAt > from && it.startedAt < to }
        if (historical.isNotEmpty()) notes += "Minute summaries: ranges show exact stored extrema; second-level curve unavailable."
        for (a in historical) {
            if (a.startedAt < from || a.endedAt > to) {
                notes += "Partial minute excluded: no invented boundary bytes."
                continue
            }
            if (a.validMs <= 0) continue
            intervals += ChartInterval(a.startedAt, a.endedAt, a.validMs, a.received, a.sent,
                Extremum(a.peakDown, a.peakDownAt), Extremum(a.peakUp, a.peakUpAt),
                a.lowestDown?.let { Extremum(it, a.lowestDownAt) }, a.lowestUp?.let { Extremum(it, a.lowestUpAt) },
                aggregated = true, coverageKnown = a.coverageKnown, count = a.sampleCount)
        }
        intervals.sortBy { it.end }
        require(intervals.isNotEmpty()) { "此范围没有有效采样；边界区间不按比例虚构流量 / No valid intervals" }
        val received = intervals.sumOf { it.received }
        val sent = intervals.sumOf { it.sent }
        val valid = intervals.sumOf { it.validMs }
        fun peak(selector: (ChartInterval) -> Extremum): Extremum {
            val values = intervals.map(selector)
            val maximum = values.maxOf { it.value }
            val ties = values.filter { it.value == maximum }
            // If a tied legacy maximum has no timestamp, the earliest occurrence is unknowable.
            return Extremum(maximum, if (ties.any { it.at == null }) null else ties.minOf { it.at!! })
        }
        fun lowest(selector: (ChartInterval) -> Extremum?): Extremum? {
            val values = intervals.map(selector)
            if (values.any { it == null }) return null
            return values.filterNotNull().sortedWith(compareBy<Extremum> { it.value }.thenBy { it.at ?: Long.MAX_VALUE }).first()
        }
        if (intervals.any { !it.coverageKnown || it.lowestDown == null || it.lowestUp == null })
            notes += "Legacy summary: unavailable minima / timestamps / coverage are marked N/A."
        if (raw.any { !it.valid && it.endedAt > from && it.startedAt < to }) notes += "Interrupted samples are gaps, not zero traffic."
        notes += "Only fully observed intervals are counted; boundary intervals are not prorated."
        return ChartPlotData(session.id, from, to, intervals,
            ChartReportStatistics(peak { it.peakDown }, peak { it.peakUp }, lowest { it.lowestDown }, lowest { it.lowestUp },
                received * 1000.0 / valid, sent * 1000.0 / valid, received, sent, valid, to - from, intervals.sumOf { it.count }), notes.distinct())
    }

    // Per screen column retain both series' local min/max plus endpoints, in timestamp order.
    // Gaps and minute summaries always split paths before reduction.
    fun segments(data: ChartPlotData, columns: Int): List<List<ChartInterval>> {
        val groups = mutableListOf<MutableList<ChartInterval>>()
        var previous: ChartInterval? = null
        for (point in data.intervals) {
            if (point.aggregated) { previous = null; continue }
            if (previous == null || point.start != previous.end || previous.aggregated) groups += mutableListOf<ChartInterval>()
            groups.last() += point
            previous = point
        }
        return groups.map { group ->
            if (group.size <= columns * 4) group
            else group.groupBy { (((it.end - data.from).toDouble() / (data.to - data.from)) * columns).toInt() }
                .values.flatMap { bucket ->
                    listOf(bucket.first(), bucket.last(), bucket.maxBy { it.down }, bucket.minBy { it.down },
                        bucket.maxBy { it.up }, bucket.minBy { it.up }).distinct().sortedBy { it.end }
                }
        }
    }
}
