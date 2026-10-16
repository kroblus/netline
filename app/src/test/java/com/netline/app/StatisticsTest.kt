package com.netline.app

import com.netline.app.data.*
import com.netline.app.export.*
import com.netline.app.monitor.*
import org.junit.Assert.*
import org.junit.Test

class StatisticsTest {
    private fun sample(start: Long, duration: Long, rx: Long, tx: Long = 0, valid: Boolean = true) = Sample(
        sessionId = 1, startedAt = start, endedAt = start + duration, durationMs = duration, received = rx, sent = tx, valid = valid)
    private fun report(raw: List<Sample>, from: Long = 0, to: Long = raw.maxOf { it.endedAt }) =
        ChartExportDataBuilder.build(Session(1, 0, to), raw, emptyList(), from, to)

    @Test fun weightedMeansAndZeroMinimaExcludeGaps() {
        val data = report(listOf(sample(0, 1000, 100), sample(1000, 2000, 1000, 200),
            sample(3000, 1000, 0), sample(4000, 8000, 0, valid = false), sample(12000, 1000, 100)))
        val stats = data.statistics
        assertEquals(1200L, stats.totalReceived)
        assertEquals(5000L, stats.validMs)
        assertEquals(240.0, stats.averageDown, .00001)
        assertEquals(0.0, stats.lowestDown!!.value, 0.0)
        assertEquals(4000L, stats.lowestDown.at)
        assertEquals(500.0, stats.peakDown.value, 0.0)
        assertEquals(3000L, stats.peakDown.at)
        assertEquals(2, ChartExportDataBuilder.segments(data, 100).size)
    }
    @Test fun equalExtremaChooseEarliestTimestamp() {
        val data = report(listOf(sample(0, 1000, 8), sample(1000, 1000, 8)))
        assertEquals(1000L, data.statistics.peakDown.at)
        assertEquals(1000L, data.statistics.lowestDown!!.at)
    }
    @Test fun sixtySecondsFiveMinutesAndFullUseExactIntervals() {
        val raw = (0 until 600).map { sample(it * 1000L, 1000, (it + 1).toLong()) }
        assertEquals(60L, report(raw, 540_000, 600_000).statistics.count)
        assertEquals(300L, report(raw, 300_000, 600_000).statistics.count)
        assertEquals(600L, report(raw).statistics.count)
        assertEquals(34230L, report(raw, 540_000, 600_000).statistics.totalReceived)
    }
    @Test fun boundaryIntervalsAreExcludedWithoutInventingBytes() {
        val data = report(listOf(sample(0, 1000, 100), sample(1000, 1000, 200)), 500, 2000)
        assertEquals(200L, data.statistics.totalReceived)
        assertEquals(1000L, data.statistics.validMs)
        assertEquals(1500L, data.statistics.requestedMs)
    }
    @Test(expected = IllegalArgumentException::class) fun noDataCannotExport() { report(listOf(sample(0, 1000, 0, valid = false))) }
    @Test(expected = IllegalArgumentException::class) fun outsideSingleSessionCannotExport() {
        ChartExportDataBuilder.build(Session(1, 100, 1000), emptyList(), emptyList(), 0, 1000)
    }
    @Test fun reductionPreservesBothSeriesPeaksValleysAndTimeOrder() {
        val raw = (0 until 10_000).map { i -> sample(i * 1000L, 1000,
            when(i) { 4567 -> 90000; 4568 -> 0; else -> 40 }, when(i) { 701 -> 40000; 702 -> 0; else -> 20 }) }
        val data = report(raw)
        val reduced = ChartExportDataBuilder.segments(data, 50).flatten()
        assertTrue(reduced.size < raw.size)
        assertEquals(90000.0, reduced.maxOf { it.down }, 0.0)
        assertEquals(0.0, reduced.minOf { it.down }, 0.0)
        assertEquals(40000.0, reduced.maxOf { it.up }, 0.0)
        assertEquals(0.0, reduced.minOf { it.up }, 0.0)
        assertEquals(reduced.map { it.end }.sorted(), reduced.map { it.end })
    }
    @Test fun aggregateKeepsExtremaAndGapsWithDeterministicTimes() {
        var aggregate: MinuteAggregate? = null
        listOf(sample(0, 1000, 5), sample(1000, 1000, 0), sample(2000, 1000, 5), sample(3000, 5000, 0, valid = false))
            .forEach { aggregate = accumulate(aggregate, it) }
        assertEquals(1000L, aggregate!!.peakDownAt)
        assertEquals(2000L, aggregate!!.lowestDownAt)
        assertEquals(5000L, aggregate!!.missingMs)
        assertEquals(3L, aggregate!!.sampleCount)
        val data = ChartExportDataBuilder.build(Session(1, 0, 8000), emptyList(), listOf(aggregate!!), 0, 8000)
        assertEquals(3000L, data.statistics.validMs)
        assertEquals(10L, data.statistics.totalReceived)
        assertTrue(ChartExportDataBuilder.segments(data, 50).isEmpty())
    }
    @Test fun legacySummariesCannotInventMissingMinimaOrTimes() {
        val a = MinuteAggregate(1, 0, 0, 60000, 1000, 100, 40000, 100.0, 10.0)
        val data = ChartExportDataBuilder.build(Session(1, 0, 60000), emptyList(), listOf(a), 0, 60000)
        assertNull(data.statistics.lowestDown)
        assertNull(data.statistics.peakDown.at)
        assertFalse(data.intervals.single().coverageKnown)
    }
    @Test fun rawDataTakesPrecedenceOverSameMinuteSummary() {
        val sample = sample(0, 1000, 100)
        val data = ChartExportDataBuilder.build(Session(1, 0, 1000), listOf(sample), listOf(accumulate(null, sample)), 0, 1000)
        assertEquals(100L, data.statistics.totalReceived)
        assertFalse(data.intervals.single().aggregated)
    }
    @Test fun tiedLegacyPeakDoesNotInventEarliestTime() {
        val legacy = MinuteAggregate(1, 0, 0, 59000, 100, 0, 1000, 100.0, 0.0)
        val later = sample(60000, 1000, 100)
        val data = ChartExportDataBuilder.build(Session(1, 0, 61000), listOf(later), listOf(legacy), 0, 61000)
        assertEquals(100.0, data.statistics.peakDown.value, 0.0)
        assertNull(data.statistics.peakDown.at)
    }
    @Test fun counterResetUnsupportedSleepAndClockAreGaps() {
        fun checkGap(next: CounterReading) {
            val sampler = CounterSampler(1, CounterReading(1000, 1000, 100, 100))
            val result = sampler.next(next)!!
            assertFalse(result.valid); assertEquals(0L, result.received)
        }
        checkGap(CounterReading(2000, 2000, 50, 120))
        checkGap(CounterReading(2000, 2000, -1, 120))
        checkGap(CounterReading(6000, 6000, 5000, 5000))
        checkGap(CounterReading(9000, 2000, 5000, 5000))
    }
    @Test fun zeroCounterDeltaIsValidIdleTraffic() {
        val sampler = CounterSampler(1, CounterReading(1000, 1000, 100, 100))
        val result = sampler.next(CounterReading(2000, 2000, 100, 100))!!
        assertTrue(result.valid); assertEquals(0.0, result.down, 0.0)
    }
}
