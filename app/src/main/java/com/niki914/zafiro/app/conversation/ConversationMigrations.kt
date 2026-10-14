package com.niki914.zafiro.app.conversation

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversation ADD COLUMN config_id TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE conversation ADD COLUMN has_unread INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * 重新生成分支族谱（本特性）：存量会话各自成组。
 * `root_id` 回填为自身 id，`parent_id` 与 `fork_turn` 设为 NULL。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversation ADD COLUMN root_id TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE conversation ADD COLUMN parent_id TEXT")
        db.execSQL("ALTER TABLE conversation ADD COLUMN fork_turn INTEGER")
        db.execSQL("UPDATE conversation SET root_id = id")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_root_id ON conversation (root_id)")
    }
}
