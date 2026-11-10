package com.tricreta.zafiro.autonomy.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Separate from upstream's ConversationDatabase on purpose: upstream is free to change its schema (ADR-0001). */
@Database(
    entities = [TaskEntity::class, RunEntity::class, CursorEntity::class, RunEventEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AutonomyDatabase : RoomDatabase() {
    abstract fun dao(): AutonomyDao

    companion object {
        const val NAME = "autonomy.db"

        fun create(context: Context): AutonomyDatabase =
            Room.databaseBuilder(context.applicationContext, AutonomyDatabase::class.java, NAME).build()
    }
}
