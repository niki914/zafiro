package com.tricreta.zafiro.autonomy.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Separate from upstream's ConversationDatabase on purpose: upstream is free to change its schema (ADR-0001). */
@Database(
    entities = [
        TaskEntity::class, RunEntity::class, CursorEntity::class, RunEventEntity::class, AgentProfileEntity::class,
        WorkspaceEntity::class, BoardItemEntity::class, ActionLogEntity::class, ExampleEntity::class, TierEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AutonomyDatabase : RoomDatabase() {
    abstract fun dao(): AutonomyDao

    companion object {
        const val NAME = "autonomy.db"

        fun create(context: Context): AutonomyDatabase =
            builder(context).build()

        fun builder(context: Context): RoomDatabase.Builder<AutonomyDatabase> =
            Room.databaseBuilder(context.applicationContext, AutonomyDatabase::class.java, NAME).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        /** v4: workspaces, shared task board, action log, review examples, tiered autonomy. */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `task` ADD COLUMN `workspaceId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `agent_profile` ADD COLUMN `workspaceId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS `workspace` (`id` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `installedVersion` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, `quotas` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`id`))")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `board_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workspaceId` TEXT NOT NULL, `parentId` INTEGER, " +
                        "`title` TEXT NOT NULL, `detail` TEXT NOT NULL, `ownerAgent` TEXT NOT NULL, `createdBy` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                        "`result` TEXT NOT NULL, `depth` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_board_item_workspaceId` ON `board_item` (`workspaceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_board_item_status` ON `board_item` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_board_item_parentId` ON `board_item` (`parentId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `action_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workspaceId` TEXT NOT NULL, `platform` TEXT NOT NULL, " +
                        "`action` TEXT NOT NULL, `target` TEXT NOT NULL, `text` TEXT NOT NULL, `dayKey` TEXT NOT NULL, `status` TEXT NOT NULL, `agent` TEXT NOT NULL, " +
                        "`reason` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_action_log_dayKey` ON `action_log` (`dayKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_action_log_workspaceId` ON `action_log` (`workspaceId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `example` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `platform` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, `verdict` TEXT NOT NULL, `reason` TEXT NOT NULL, `createdAtMs` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_example_platform` ON `example` (`platform`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `tier` (`workspaceId` TEXT NOT NULL, `action` TEXT NOT NULL, `streak` INTEGER NOT NULL, `auto` INTEGER NOT NULL, PRIMARY KEY(`workspaceId`, `action`))")
            }
        }

        /** v3: agent profiles, per-task agent and learning switch. */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent_profile` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `instructions` TEXT NOT NULL, " +
                        "`configId` TEXT NOT NULL, `tools` TEXT NOT NULL, `skills` TEXT NOT NULL, `usesScreen` INTEGER NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `updatedAtMs` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("ALTER TABLE `task` ADD COLUMN `agentId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `task` ADD COLUMN `learn` INTEGER NOT NULL DEFAULT 1")
            }
        }

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
