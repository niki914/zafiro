package com.niki914.nexus.agentic.app.conversation

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * T3 推倒重来（D1）：version 2，不写迁移（fallbackToDestructiveMigration），
 * 旧 conversation_turn 数据直接丢弃。
 */
@Database(
    entities = [
        ConversationEntity::class,
        ConversationEntryEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class ConversationDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
}

fun buildConversationDatabase(context: Context): ConversationDatabase {
    return Room.databaseBuilder(
        context.applicationContext,
        ConversationDatabase::class.java,
        "conversation_history.db",
    )
        .fallbackToDestructiveMigration()
        .build()
}
