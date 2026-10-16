package com.netline.app.monitor

import com.netline.app.data.Sample
import kotlin.math.abs

data class CounterReading(val wall: Long, val elapsed: Long, val rx: Long, val tx: Long)
class CounterSampler(private val session: Long, private var previous: CounterReading) {
    fun next(now: CounterReading): Sample? {
        val last = previous
        previous = now
        val elapsed = now.elapsed - last.elapsed
        if (elapsed <= 0) return null
        val reason = when {
            last.rx < 0 || last.tx < 0 || now.rx < 0 || now.tx < 0 -> "Counter unavailable"
            now.rx < last.rx || now.tx < last.tx -> "Counter reset"
            elapsed > 2500 -> "Sampling interrupted / device sleep"
            abs((now.wall - last.wall) - elapsed) > 1000 || now.wall <= last.wall -> "System clock changed"
            else -> null
        }
        return Sample(sessionId = session, startedAt = last.wall, endedAt = now.wall, durationMs = elapsed,
            received = if (reason == null) now.rx - last.rx else 0,
            sent = if (reason == null) now.tx - last.tx else 0, valid = reason == null, gapReason = reason)
    }
}
