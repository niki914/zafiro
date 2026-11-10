package com.tricreta.zafiro.autonomy.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Separate from upstream's ConversationDatabase on purpose: upstream is free to change its schema (ADR-0001). */
@Database(
    entities = [TaskEntity::class, RunEntity::class, CursorEntity::class, RunEventEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AutonomyDatabase : RoomDatabase() {
    abstract fun dao(): AutonomyDao

    companion object {
        const val NAME = "autonomy.db"

        fun create(context: Context): AutonomyDatabase =
            builder(context).build()

        fun builder(context: Context): RoomDatabase.Builder<AutonomyDatabase> =
            Room.databaseBuilder(context.applicationContext, AutonomyDatabase::class.java, NAME).addMigrations(MIGRATION_1_2)

        /** v2: per-task token limit, success criteria and recovery count; per-run recovery count. Existing rows keep working. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `task` ADD COLUMN `maxTokens` INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE `task` ADD COLUMN `successCriteria` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `task` ADD COLUMN `recoveries` INTEGER NOT NULL DEFAULT 2")
                db.execSQL("ALTER TABLE `run` ADD COLUMN `recoveries` INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
