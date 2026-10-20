package com.mynok.myagent.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ModelProfileEntity::class,
        McpServerEntity::class,
        ToolPolicyEntity::class,
        SkillEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class MyAgentDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun modelProfileDao(): ModelProfileDao
    abstract fun mcpServerDao(): McpServerDao
    abstract fun toolPolicyDao(): ToolPolicyDao
    abstract fun skillDao(): SkillDao

    companion object {
        @Volatile
        private var instance: MyAgentDatabase? = null

        fun get(context: Context): MyAgentDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MyAgentDatabase::class.java,
                    "myagent.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }

        /** v1 → v2：新增 skills 表。 */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `skills` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `description` TEXT NOT NULL DEFAULT '',
                        `prompt` TEXT NOT NULL,
                        `forceTools` TEXT NOT NULL DEFAULT '',
                        `sortOrder` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /** v2 → v3：会话增加思考强度 / 展示开关，消息增加思考内容。 */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `conversations` ADD COLUMN `reasoningEffort` TEXT NOT NULL DEFAULT 'OFF'",
                )
                db.execSQL(
                    "ALTER TABLE `conversations` ADD COLUMN `showReasoning` INTEGER NOT NULL DEFAULT 1",
                )
                db.execSQL(
                    "ALTER TABLE `messages` ADD COLUMN `reasoning` TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE `model_profiles` ADD COLUMN `reasoningLevels` TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        /**
         * v3 → v4：模型档案增加"支持的思考强度"。
         *
         * v3 已经装到测试机上但还没有这一列，所以这里做幂等判断：
         * 从 v2 升上来的库里 [MIGRATION_2_3] 已经加过了，跳过即可。
         */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                if (!db.hasColumn("model_profiles", "reasoningLevels")) {
                    db.execSQL(
                        "ALTER TABLE `model_profiles` ADD COLUMN `reasoningLevels` TEXT NOT NULL DEFAULT ''",
                    )
                }
            }
        }

        /**
         * v4 → v5：会话增加「显示工具调用」开关。
         *
         * 默认显示，老会话行为不变；同样做幂等判断，防止重复升级时列已存在。
         */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                if (!db.hasColumn("conversations", "showToolCalls")) {
                    db.execSQL(
                        "ALTER TABLE `conversations` ADD COLUMN `showToolCalls` INTEGER NOT NULL DEFAULT 1",
                    )
                }
            }
        }

        private fun androidx.sqlite.db.SupportSQLiteDatabase.hasColumn(
            table: String,
            column: String,
        ): Boolean = query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && cursor.getString(nameIndex) == column) {
                    found = true
                    break
                }
            }
            found
        }
    }
}
