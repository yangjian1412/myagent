package com.mynok.myagent.data.db

import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v6 → v7 迁移的验证。
 *
 * 重点：会话 / 消息这些老数据必须原样保留，
 * 技能表按约定重建（不迁移旧行），定时任务表建出来。
 *
 * 这里手工用 SQL 造一个真正的 v6 库（Room 会按当前实体建表，
 * 没法直接造出旧结构的库）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MyAgentDatabaseMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbName = "migration-test.db"

    @Before
    fun setUp() {
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /** 造一个 v6 结构的库，并塞一条会话数据。 */
    private fun createV6Database() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        createV6Schema(db)
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )
        helper.writableDatabase.use { db ->
            createV6Schema(db)
            db.execSQL(
                """
                INSERT INTO conversations
                (id, title, createdAt, updatedAt, modelProfileId, systemPrompt, pinned, archived,
                 contextWindowOverride, activeLeafId, reasoningEffort, showReasoning, showToolCalls, maxSteps)
                VALUES ('conv-1', '旧会话', 1000, 2000, NULL, NULL, 0, 0, NULL, NULL, 'OFF', 1, 1, 16)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO skills (id, name, description, prompt, forceTools, sortOrder)
                VALUES ('old', '旧技能', '旧描述', '旧正文', '', 0)
                """.trimIndent(),
            )
        }
        helper.close()
    }

    private fun createV6Schema(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `conversations` (
                `id` TEXT NOT NULL, `title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL, `modelProfileId` TEXT, `systemPrompt` TEXT,
                `pinned` INTEGER NOT NULL DEFAULT 0, `archived` INTEGER NOT NULL DEFAULT 0,
                `contextWindowOverride` INTEGER, `activeLeafId` TEXT,
                `reasoningEffort` TEXT NOT NULL DEFAULT 'OFF',
                `showReasoning` INTEGER NOT NULL DEFAULT 1,
                `showToolCalls` INTEGER NOT NULL DEFAULT 1,
                `maxSteps` INTEGER NOT NULL DEFAULT 8,
                PRIMARY KEY(`id`))
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `messages` (
                `id` TEXT NOT NULL, `conversationId` TEXT NOT NULL, `parentId` TEXT,
                `role` TEXT NOT NULL, `contentJson` TEXT NOT NULL, `toolCallsJson` TEXT,
                `toolCallId` TEXT, `toolName` TEXT, `pinned` INTEGER NOT NULL DEFAULT 0,
                `kind` TEXT NOT NULL DEFAULT 'MESSAGE', `provider` TEXT, `modelId` TEXT,
                `createdAt` INTEGER NOT NULL, `tokenEstimate` INTEGER NOT NULL DEFAULT 0,
                `reasoning` TEXT NOT NULL DEFAULT '',
                PRIMARY KEY(`id`))
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_conversationId` ON `messages` (`conversationId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_parentId` ON `messages` (`parentId`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_messages_conversationId_createdAt` ON `messages` (`conversationId`, `createdAt`)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `model_profiles` (
                `id` TEXT NOT NULL, `name` TEXT NOT NULL, `providerType` TEXT NOT NULL,
                `baseUrl` TEXT NOT NULL, `modelId` TEXT NOT NULL, `secretId` TEXT,
                `headersJson` TEXT NOT NULL DEFAULT '{}', `supportsTools` INTEGER NOT NULL DEFAULT 1,
                `supportsVision` INTEGER NOT NULL DEFAULT 0, `supportsStreaming` INTEGER NOT NULL DEFAULT 1,
                `contextWindow` INTEGER NOT NULL DEFAULT 32768,
                `maxOutputTokens` INTEGER NOT NULL DEFAULT 4096, `temperature` REAL, `topP` REAL,
                `reasoningLevels` TEXT NOT NULL DEFAULT '', `extraBodyJson` TEXT NOT NULL DEFAULT '{}',
                `builtin` INTEGER NOT NULL DEFAULT 0, `sortOrder` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`id`))
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `mcp_servers` (
                `id` TEXT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `secretId` TEXT,
                `headersJson` TEXT NOT NULL DEFAULT '{}', `enabled` INTEGER NOT NULL DEFAULT 1,
                `lastConnectedAt` INTEGER, `lastError` TEXT, `toolCount` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`id`))
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `tool_policies` (
                `toolName` TEXT NOT NULL, `enabled` INTEGER NOT NULL DEFAULT 1,
                `requireApproval` INTEGER NOT NULL DEFAULT 0, `isMcp` INTEGER NOT NULL DEFAULT 0,
                `serverId` TEXT, PRIMARY KEY(`toolName`))
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `skills` (
                `id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL DEFAULT '',
                `prompt` TEXT NOT NULL, `forceTools` TEXT NOT NULL DEFAULT '',
                `sortOrder` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))
            """.trimIndent(),
        )
    }

    @Test
    fun `v6 database migrates to v7 keeping conversation data`() = runBlocking {
        createV6Database()

        val v7 = Room.databaseBuilder(context, MyAgentDatabase::class.java, dbName)
            .addMigrations(MyAgentDatabase.MIGRATION_6_7)
            .allowMainThreadQueries()
            .build()

        // 老会话原样保留，包括步数
        val conversations = v7.conversationDao().getAll()
        assertEquals(1, conversations.size)
        val kept = conversations.first()
        assertEquals("conv-1", kept.id)
        assertEquals("旧会话", kept.title)
        assertEquals(16, kept.maxSteps)

        // 旧技能按约定不迁移
        assertNull(v7.skillDao().get("old"))

        // 新表能写
        v7.scheduledTaskDao().upsert(
            ScheduledTaskEntity(
                id = "task-1",
                name = "每天早报",
                cron = "0 8 * * *",
                actionType = ScheduledTaskEntity.ACTION_NOTIFY,
                createdAt = 100L,
            ),
        )
        assertEquals(1, v7.scheduledTaskDao().getEnabled().size)

        // 技能表是新结构
        v7.skillDao().upsert(
            SkillEntity(
                id = "s1",
                name = "s1",
                prompt = "正文",
                source = SkillEntity.SOURCE_FILE,
                sourceDir = "s1",
            ),
        )
        val skill = v7.skillDao().get("s1")
        assertNotNull(skill)
        assertEquals(SkillEntity.SOURCE_FILE, skill!!.source)

        v7.close()
    }

    @Test
    fun `messages survive the migration`() = runBlocking {
        createV6Database()
        val v7 = Room.databaseBuilder(context, MyAgentDatabase::class.java, dbName)
            .addMigrations(MyAgentDatabase.MIGRATION_6_7)
            .allowMainThreadQueries()
            .build()
        v7.messageDao().upsert(
            MessageEntity(
                id = "m1",
                conversationId = "conv-1",
                parentId = null,
                role = "user",
                contentJson = "[]",
                createdAt = 100L,
            ),
        )
        assertEquals(1, v7.messageDao().getAll("conv-1").size)
        v7.close()
    }
}
