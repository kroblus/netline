package com.netline.app.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val interrupted: Boolean = false
)

// Every row is an observed counter interval. Invalid intervals are retained as gaps.
@Entity(tableName = "samples", indices = [Index(value = ["sessionId", "endedAt"])])
data class Sample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
    val received: Long,
    val sent: Long,
    val valid: Boolean,
    val gapReason: String? = null
) {
    val down: Double get() = if (valid && durationMs > 0) received * 1000.0 / durationMs else 0.0
    val up: Double get() = if (valid && durationMs > 0) sent * 1000.0 / durationMs else 0.0
}

@Entity(tableName = "minute_aggregates", primaryKeys = ["sessionId", "bucket"])
data class MinuteAggregate(
    val sessionId: Long,
    val bucket: Long,
    val startedAt: Long,
    val endedAt: Long,
    val received: Long,
    val sent: Long,
    val validMs: Long,
    val peakDown: Double,
    val peakUp: Double,
    @ColumnInfo(defaultValue = "NULL") val lowestDown: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val lowestUp: Double? = null,
    @ColumnInfo(defaultValue = "NULL") val peakDownAt: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val peakUpAt: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val lowestDownAt: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val lowestUpAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val sampleCount: Long = 0,
    @ColumnInfo(defaultValue = "0") val missingMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val coverageKnown: Boolean = false
)

@Dao
interface TrafficDao {
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC") fun observeSessions(): Flow<List<Session>>
    @Insert suspend fun insertSession(session: Session): Long
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun session(id: Long): Session?
    @Query("UPDATE sessions SET endedAt = :end, interrupted = :interrupted WHERE id = :id")
    suspend fun endSession(id: Long, end: Long, interrupted: Boolean)
    @Query("UPDATE sessions SET endedAt = COALESCE((SELECT MAX(endedAt) FROM samples WHERE sessionId = sessions.id), startedAt), interrupted = 1 WHERE endedAt IS NULL")
    suspend fun closeInterruptedSessions()
    @Insert suspend fun insertSample(sample: Sample)
    @Upsert suspend fun aggregate(value: MinuteAggregate)
    @Query("SELECT * FROM minute_aggregates WHERE sessionId = :session AND bucket = :bucket")
    suspend fun aggregate(session: Long, bucket: Long): MinuteAggregate?
    @Query("SELECT * FROM samples WHERE sessionId = :session ORDER BY endedAt, id")
    suspend fun samples(session: Long): List<Sample>
    @Query("SELECT * FROM samples WHERE sessionId = :session AND endedAt >= :from AND endedAt <= :to ORDER BY endedAt, id")
    suspend fun samplesInRange(session: Long, from: Long, to: Long): List<Sample>
    @Query("SELECT MAX(endedAt) FROM samples WHERE sessionId = :session")
    suspend fun lastSampleAt(session: Long): Long?
    @Query("SELECT * FROM minute_aggregates WHERE sessionId = :session ORDER BY bucket")
    suspend fun aggregates(session: Long): List<MinuteAggregate>
    @Query("DELETE FROM samples WHERE endedAt < :cutoff AND sessionId IN (SELECT id FROM sessions WHERE endedAt IS NOT NULL)")
    suspend fun prune(cutoff: Long)
    @Query("SELECT COUNT(*) FROM sessions WHERE id = :id AND endedAt IS NOT NULL")
    suspend fun endedSessionExists(id: Long): Int
    @Query("DELETE FROM samples WHERE sessionId = :id") suspend fun deleteSamples(id: Long)
    @Query("DELETE FROM minute_aggregates WHERE sessionId = :id") suspend fun deleteAggregates(id: Long)
    @Query("DELETE FROM sessions WHERE id = :id AND endedAt IS NOT NULL") suspend fun deleteEndedRow(id: Long): Int
    @Transaction
    suspend fun deleteEndedSession(id: Long): Boolean {
        if (endedSessionExists(id) != 1) return false
        deleteSamples(id)
        deleteAggregates(id)
        return deleteEndedRow(id) == 1
    }
}

@Database(entities = [Session::class, Sample::class, MinuteAggregate::class], version = 2, exportSchema = true)
abstract class NetlineDatabase : RoomDatabase() {
    abstract fun traffic(): TrafficDao
    companion object {
        // Legacy extrema timestamps remain NULL. No destructive fallback and no invented history.
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("lowestDown REAL", "lowestUp REAL", "peakDownAt INTEGER", "peakUpAt INTEGER",
                    "lowestDownAt INTEGER", "lowestUpAt INTEGER").forEach {
                    db.execSQL("ALTER TABLE minute_aggregates ADD COLUMN $it DEFAULT NULL")
                }
                listOf("sampleCount", "missingMs", "coverageKnown").forEach {
                    db.execSQL("ALTER TABLE minute_aggregates ADD COLUMN $it INTEGER NOT NULL DEFAULT 0")
                }
            }
        }
    }
}

fun accumulate(old: MinuteAggregate?, s: Sample): MinuteAggregate {
    val base = old ?: MinuteAggregate(s.sessionId, s.endedAt / 60_000, s.startedAt, s.endedAt,
        0, 0, 0, 0.0, 0.0, coverageKnown = true)
    if (!s.valid) return base.copy(endedAt = s.endedAt, missingMs = base.missingMs + s.durationMs)
    val first = base.validMs == 0L
    val downPeak = first || s.down > base.peakDown
    val upPeak = first || s.up > base.peakUp
    val downLow = first || (base.lowestDown != null && s.down < base.lowestDown)
    val upLow = first || (base.lowestUp != null && s.up < base.lowestUp)
    return base.copy(endedAt = s.endedAt, received = base.received + s.received, sent = base.sent + s.sent,
        validMs = base.validMs + s.durationMs, peakDown = if (downPeak) s.down else base.peakDown,
        peakUp = if (upPeak) s.up else base.peakUp,
        lowestDown = if (downLow) s.down else base.lowestDown, lowestUp = if (upLow) s.up else base.lowestUp,
        peakDownAt = if (downPeak) s.endedAt else base.peakDownAt,
        peakUpAt = if (upPeak) s.endedAt else base.peakUpAt,
        lowestDownAt = if (downLow) s.endedAt else base.lowestDownAt,
        lowestUpAt = if (upLow) s.endedAt else base.lowestUpAt,
        sampleCount = base.sampleCount + 1)
}
