// 保护：Room 数据库从 v2 升级到 v4 时旧会话及 entry 记录完整保留，
// config_id / has_unread / root_id / parent_id / fork_turn 字段成功新增且默认值正确。
package com.niki914.zafiro.app.conversation

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val TEST_DB_NAME = "test-migration.db"

private const val V2_CREATE_CONVERSATION_SQL = """
CREATE TABLE IF NOT EXISTS `conversation` (
    `id` TEXT NOT NULL,
    `title` TEXT NOT NULL,
    `title_edited` INTEGER NOT NULL,
    `created_at` INTEGER NOT NULL,
    `updated_at` INTEGER NOT NULL,
    `last_message_preview` TEXT NOT NULL,
    `turn_count` INTEGER NOT NULL,
    `draft_text` TEXT NOT NULL,
    `leaf_id` TEXT,
    PRIMARY KEY(`id`)
)
"""

private const val V2_CREATE_CONVERSATION_ENTRY_SQL = """
CREATE TABLE IF NOT EXISTS `conversation_entry` (
    `conversation_id` TEXT NOT NULL,
    `id` TEXT NOT NULL,
    `parent_id` TEXT,
    `timestamp` INTEGER NOT NULL,
    `message_json` TEXT NOT NULL,
    PRIMARY KEY(`conversation_id`, `id`),
    FOREIGN KEY(`conversation_id`) REFERENCES `conversation`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
)
"""

private const val V2_CREATE_INDEX_SQL = """
CREATE INDEX IF NOT EXISTS `index_conversation_entry_conversation_id` ON `conversation_entry` (`conversation_id`)
"""

@RunWith(RobolectricTestRunner::class)
class ConversationMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB_NAME)
    }

    private fun createV2Database() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TEST_DB_NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(V2_CREATE_CONVERSATION_SQL)
                    db.execSQL(V2_CREATE_CONVERSATION_ENTRY_SQL)
                    db.execSQL(V2_CREATE_INDEX_SQL)
                }

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int
                ) {
                }
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val db = helper.writableDatabase
        db.execSQL("INSERT INTO conversation VALUES ('c1', 'Title 1', 0, 1000, 1000, 'preview 1', 1, '', 'e1')")
        db.execSQL("INSERT INTO conversation_entry VALUES ('c1', 'e1', NULL, 1000, '{\"text\":\"msg\"}')")
        db.close()
        helper.close()
    }

    @Test
    fun migration2To4_preservesDataAndBackfillsBranchColumns() = runTest {
        createV2Database()

        // 验证 Room 以 v4 打开迁移后的数据库成功（由 Room 依次执行 MIGRATION_2_3 / 3_4 并校验 schema）
        val roomDb = Room.databaseBuilder(context, ConversationDatabase::class.java, TEST_DB_NAME)
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
            .build()
        try {
            val entity = roomDb.conversationDao().getConversation("c1")
            assertNotNull(entity)
            assertEquals("c1", entity!!.id)
            assertEquals("Title 1", entity.title)
            assertEquals("", entity.configId)
            assertEquals(false, entity.hasUnread)
            // 存量会话各自成根，无父节点与分叉轮次
            assertEquals("c1", entity.rootId)
            assertEquals(null, entity.parentId)
            assertEquals(null, entity.forkTurn)

            val entries = roomDb.conversationDao().listEntries("c1")
            assertEquals(1, entries.size)
            assertEquals("e1", entries.first().id)
        } finally {
            roomDb.close()
        }
    }
}
