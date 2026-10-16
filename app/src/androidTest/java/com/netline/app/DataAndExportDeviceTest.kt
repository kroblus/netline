package com.netline.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.netline.app.data.*
import com.netline.app.export.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DataAndExportDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Test fun chineseAndEnglishReportsRenderWithoutChangingData() = runBlocking {
        val app = context.applicationContext as NetlineApp
        val original = app.preferences.settings.first().language
        val started = 1_792_114_200_000L
        val samples = (0 until 10).map { index -> Sample(sessionId = 1, startedAt = started + index * 1000L,
            endedAt = started + (index + 1) * 1000L, durationMs = 1000, received = index * 100L, sent = index * 10L, valid = true) }
        val data = ChartExportDataBuilder.build(Session(1, started, started + 10000), samples, emptyList(), started, started + 10000)
        try {
            for (language in listOf("zh", "en")) {
                app.preferences.language(language)
                withTimeout(5000) { app.language.first { it == language } }
                val expected = if (language == "zh") "01 / 网络流量统计报告" else "01 / NETWORK TRAFFIC REPORT"
                assertEquals(expected, app.localizedString(R.string.report_heading))
                val image = ChartExportRenderer(app).render(data)
                try {
                    image.file.copyTo(File(app.getExternalFilesDir(null), "TEST_REPORT_$language.png"), overwrite = true)
                    val bitmap = BitmapFactory.decodeFile(image.file.absolutePath)!!
                    assertEquals(1080, bitmap.width); assertEquals(1920, bitmap.height); bitmap.recycle()
                    assertEquals(4500L, image.report.statistics.totalReceived)
                } finally { image.file.delete() }
            }
        } finally { app.preferences.language(original) }
    }
    @Test fun deletingEndedSessionIsAtomicAndProtectsActiveAndOtherHistory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NetlineDatabase::class.java).build()
        try {
            val repo = TrafficRepository(db)
            val ids = (0..2).map { index ->
                val start = System.currentTimeMillis() + index * 2000
                val id = repo.start(start)
                repo.append(Sample(sessionId = id, startedAt = start, endedAt = start + 1000, durationMs = 1000, received = 100, sent = 10, valid = true))
                if (index < 2) repo.end(id, start + 1000)
                id
            }
            assertFalse(repo.deleteSession(ids[2]))
            assertEquals(1, db.traffic().samples(ids[2]).size)
            assertTrue(repo.deleteSession(ids[0]))
            assertNull(db.traffic().session(ids[0]))
            assertTrue(db.traffic().samples(ids[0]).isEmpty())
            assertTrue(db.traffic().aggregates(ids[0]).isEmpty())
            assertFalse(repo.deleteSession(ids[0]))
            assertNotNull(db.traffic().session(ids[1]))
            assertEquals(1, db.traffic().samples(ids[1]).size)
            assertEquals(1, db.traffic().aggregates(ids[1]).size)
        } finally { db.close() }
    }
    @Test fun providerRejectsFilesOutsideExportDirectory() {
        val privateFile = File(context.filesDir, "not-shareable.txt").apply { writeText("test") }
        try {
            assertThrows(IllegalArgumentException::class.java) {
                FileProvider.getUriForFile(context, "com.netline.app.exports", privateFile)
            }
        } finally { privateFile.delete() }
    }
    @Test fun failedSaveRemovesPendingMediaRow() = runBlocking {
        val sample = Sample(sessionId = 1, startedAt = 0, endedAt = 1000, durationMs = 1000, received = 1, sent = 0, valid = true)
        val data = ChartExportDataBuilder.build(Session(1, 0, 1000), listOf(sample), emptyList(), 0, 1000)
        val filename = "NETLINE_MISSING_SOURCE_TEST.png"
        val image = ExportImage(File(context.cacheDir, filename), data)
        val failed = runCatching { ImageSaveManager(context).save(image) }.isFailure
        assertTrue(failed)
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?", arrayOf(filename), null)!!.use { assertEquals(0, it.count) }
    }
    @Test fun migrationPreservesLegacyRowsAndUnknownExtrema() = runBlocking {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE sessions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER, interrupted INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE samples (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sessionId INTEGER NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER NOT NULL, durationMs INTEGER NOT NULL, received INTEGER NOT NULL, sent INTEGER NOT NULL, valid INTEGER NOT NULL, gapReason TEXT)")
            db.execSQL("CREATE INDEX index_samples_sessionId_endedAt ON samples(sessionId, endedAt)")
            db.execSQL("CREATE TABLE minute_aggregates (sessionId INTEGER NOT NULL, bucket INTEGER NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER NOT NULL, received INTEGER NOT NULL, sent INTEGER NOT NULL, validMs INTEGER NOT NULL, peakDown REAL NOT NULL, peakUp REAL NOT NULL, PRIMARY KEY(sessionId, bucket))")
            db.execSQL("INSERT INTO sessions VALUES (1, 1000, 61000, 0)")
            db.execSQL("INSERT INTO minute_aggregates VALUES (1, 0, 1000, 61000, 8000, 1000, 50000, 1000.0, 200.0)")
            db.version = 1
        }
        val db = Room.databaseBuilder(context, NetlineDatabase::class.java, name).addMigrations(NetlineDatabase.MIGRATION_1_2).build()
        try {
            assertEquals(1000L, db.traffic().session(1)!!.startedAt)
            val aggregate = db.traffic().aggregates(1).single()
            assertEquals(8000L, aggregate.received)
            assertNull(aggregate.lowestDown)
            assertNull(aggregate.peakDownAt)
            assertFalse(aggregate.coverageKnown)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun reportSnapshotPngMediaStoreAndSecureProvider() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, NetlineDatabase::class.java).build()
        var image: ExportImage? = null
        try {
            val repo = TrafficRepository(db)
            val started = 1_792_114_200_000L
            val id = repo.start(started)
            // Explicit test fixtures only; these are never inserted into the user's real history.
            for (i in 0 until 360) {
                repo.append(Sample(sessionId = id, startedAt = started + i * 1000L, endedAt = started + (i + 1) * 1000L,
                    durationMs = 1000, received = if (i == 45) 9_000_000 else if (i % 9 == 0) 0 else 32_000,
                    sent = if (i == 90) 900_000 else 1_000, valid = i !in 120..130, gapReason = if (i in 120..130) "test gap" else null))
            }
            val live = repo.report(id, null, null)
            assertNull(db.traffic().session(id)!!.endedAt)
            assertEquals(9_000_000.0, live.statistics.peakDown.value, 0.0)
            assertEquals(0.0, live.statistics.lowestDown!!.value, 0.0)
            assertEquals(349_000L, live.statistics.validMs)
            assertEquals(2, ChartExportDataBuilder.segments(live, 852).size)
            assertEquals(60L, repo.report(id, null, null, 60_000).statistics.count)
            assertEquals(289L, repo.report(id, null, null, 300_000).statistics.count)
            image = ChartExportRenderer(context).render(live)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(image.file.absolutePath, options)
            assertEquals(1080, options.outWidth); assertEquals(1920, options.outHeight)
            val shareUri = FileProvider.getUriForFile(context, "com.netline.app.exports", image.file)
            assertEquals("content", shareUri.scheme)
            context.contentResolver.openInputStream(shareUri)!!.use { assertTrue(it.readBytes().size > 10000) }
            val saved = ImageSaveManager(context).save(image)
            try {
                context.contentResolver.query(saved, arrayOf(MediaStore.Images.Media.IS_PENDING, MediaStore.Images.Media.RELATIVE_PATH), null, null, null)!!.use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals("Pictures/NETLINE/", it.getString(1))
                }
                context.contentResolver.openInputStream(saved)!!.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)!!
                    assertEquals(1080, bitmap.width); bitmap.recycle()
                }
                val another = ImageSaveManager(context).save(image)
                assertNotEquals(saved, another)
                context.contentResolver.delete(another, null, null)
            } finally { context.contentResolver.delete(saved, null, null) }
            repo.append(Sample(sessionId = id, startedAt = started + 360_000, endedAt = started + 361_000, durationMs = 1000, received = 100, sent = 10, valid = true))
            assertEquals(350L, repo.report(id, null, null).statistics.count)
            repo.end(id, started + 361_000)
            assertEquals(started + 361_000, db.traffic().session(id)!!.endedAt)
            // Keep a clearly named fixture artifact for visual inspection, outside production history.
            image.file.copyTo(File(context.getExternalFilesDir(null), "TEST_FIXTURE_REPORT.png"), overwrite = true)
            Unit
        } finally { image?.file?.delete(); db.close() }
    }
}
