package com.netline.app.data

import androidx.room.withTransaction
import com.netline.app.export.ChartExportDataBuilder
import com.netline.app.export.ChartPlotData
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TrafficRepository(val db: NetlineDatabase) {
    private val dao = db.traffic()
    private val initialization = Mutex()
    private var initialized = false
    val sessions = dao.observeSessions()
    suspend fun deleteSession(id: Long): Boolean = dao.deleteEndedSession(id)
    suspend fun initialize() = initialization.withLock {
        if (!initialized) {
            db.withTransaction {
                dao.closeInterruptedSessions()
                // Remove complete minute buckets only; a retained minute never mixes partial raw and summary data.
                dao.prune((System.currentTimeMillis() - 7L * 86_400_000) / 60_000 * 60_000)
            }
            initialized = true
        }
    }
    suspend fun start(at: Long): Long { initialize(); return dao.insertSession(Session(startedAt = at)) }
    suspend fun append(sample: Sample) = db.withTransaction {
        dao.insertSample(sample)
        dao.aggregate(accumulate(dao.aggregate(sample.sessionId, sample.endedAt / 60_000), sample))
    }
    suspend fun end(id: Long, at: Long, interrupted: Boolean = false) = dao.endSession(id, at, interrupted)
    suspend fun report(id: Long, from: Long?, to: Long?, lastMs: Long? = null): ChartPlotData = withContext(Dispatchers.Default) {
        data class Snapshot(val session: Session, val raw: List<Sample>, val aggregates: List<MinuteAggregate>, val from: Long, val to: Long)
        val snapshot = db.withTransaction {
            val session = requireNotNull(dao.session(id)) { "记录不存在 / Session not found" }
            val aggregates = dao.aggregates(id)
            val end = session.endedAt ?: maxOf(dao.lastSampleAt(id) ?: session.startedAt,
                aggregates.lastOrNull()?.endedAt ?: session.startedAt)
            val selectedEnd = to ?: end
            val selectedStart = from ?: lastMs?.let { maxOf(session.startedAt, selectedEnd - it) } ?: session.startedAt
            require(selectedEnd <= end) { "结束时间超出已确认数据 / End exceeds persisted data" }
            val raw = dao.samplesInRange(id, selectedStart / 60_000 * 60_000, (selectedEnd / 60_000 + 1) * 60_000)
            Snapshot(session, raw, aggregates, selectedStart, selectedEnd)
        }
        // Do CPU work after releasing the transaction, so rendering/statistics never hold the writer.
        ChartExportDataBuilder.build(snapshot.session, snapshot.raw, snapshot.aggregates, snapshot.from, snapshot.to)
    }
}
