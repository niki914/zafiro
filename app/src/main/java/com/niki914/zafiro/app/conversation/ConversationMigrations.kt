package com.niki914.zafiro.app.conversation

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversation ADD COLUMN config_id TEXT NOT NULL DEFAULT ''")
    }
}
