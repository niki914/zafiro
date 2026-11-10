package com.tricreta.zafiro.autonomy

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.tricreta.zafiro.autonomy.data.AutonomyDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Users already have tasks in schema v1. A bad migration would crash the app on open and silently stop every schedule, so
 * this builds a real v1 file (the exact DDL Room generated for v1 entities), opens it with the v2 database, and checks that
 * Room accepts the schema and the old rows survive with sane defaults.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private val v1Ddl = listOf(
        "CREATE TABLE IF NOT EXISTS `task` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `prompt` TEXT NOT NULL, `enabled` INTEGER NOT NULL, " +
            "`triggerType` TEXT NOT NULL, `onceAt` TEXT, `intervalMinutes` INTEGER, `anchorMs` INTEGER, `timeOfDay` TEXT, " +
            "`daysMask` INTEGER NOT NULL, `dayOfMonth` INTEGER NOT NULL, `zoneId` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, " +
            "`updatedAtMs` INTEGER NOT NULL, `misfireGraceMs` INTEGER NOT NULL, `overlap` TEXT NOT NULL, `maxSteps` INTEGER NOT NULL, " +
            "`maxDurationMs` INTEGER NOT NULL, `configId` TEXT NOT NULL, `approvalTimeoutMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `run` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `taskId` TEXT NOT NULL, `taskName` TEXT NOT NULL, " +
            "`scheduledForMs` INTEGER NOT NULL, `state` TEXT NOT NULL, `reason` TEXT, `missedCount` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, " +
            "`startedAtMs` INTEGER, `finishedAtMs` INTEGER, `summary` TEXT, `error` TEXT, `conversationId` TEXT, `steps` INTEGER NOT NULL, " +
            "`heartbeatMs` INTEGER, `manual` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_run_taskId_scheduledForMs` ON `run` (`taskId`, `scheduledForMs`)",
        "CREATE INDEX IF NOT EXISTS `index_run_state` ON `run` (`state`)",
        "CREATE TABLE IF NOT EXISTS `cursor` (`taskId` TEXT NOT NULL, `lastHandledMs` INTEGER, PRIMARY KEY(`taskId`))",
        "CREATE TABLE IF NOT EXISTS `run_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `runId` INTEGER NOT NULL, `tsMs` INTEGER NOT NULL, " +
            "`kind` TEXT NOT NULL, `text` TEXT NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_run_event_runId` ON `run_event` (`runId`)",
    )

    @Test
    fun `v1 database with tasks and runs opens as v2 and keeps its data`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath(AutonomyDatabase.NAME)
        file.parentFile?.mkdirs()
        file.delete()

        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        v1Ddl.forEach { raw.execSQL(it) }
        raw.execSQL(
            "INSERT INTO task VALUES ('t1','Morning brief','summarise news',1,'DAILY',NULL,NULL,NULL,'08:00',0,1,'',1000,1000,7200000,'SKIP',30,600000,'cfg-9',300000)",
        )
        raw.execSQL(
            "INSERT INTO run (taskId,taskName,scheduledForMs,state,missedCount,createdAtMs,steps,manual) VALUES ('t1','Morning brief',5000,'SUCCEEDED',0,5000,4,0)",
        )
        raw.version = 1
        raw.close()

        val db = AutonomyDatabase.builder(context).allowMainThreadQueries().build()
        try {
            runBlocking {
                val task = assertNotNull(db.dao().task("t1"))
                assertEquals("Morning brief", task.name)
                assertEquals("cfg-9", task.configId)
                assertEquals(30, task.maxSteps)
                assertEquals(-1L, task.maxTokens)
                assertEquals("", task.successCriteria)
                assertEquals(2, task.recoveries)
                assertEquals(1, db.dao().runsForTask("t1", 10).size)
                assertEquals(0, db.dao().runsForTask("t1", 10).single().recoveries)
                assertEquals(4, db.dao().runsForTask("t1", 10).single().steps)
            }
        } finally {
            db.close()
            file.delete()
        }
    }
}
