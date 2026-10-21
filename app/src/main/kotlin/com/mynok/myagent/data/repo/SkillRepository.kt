package com.mynok.myagent.data.repo

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mynok.myagent.core.skills.SkillDefinition
import com.mynok.myagent.data.db.SkillEntity
import com.mynok.myagent.data.skills.SkillFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.skillDirectoryStore by preferencesDataStore(name = "myagent_skill_dir")

/**
 * 技能仓储。
 *
 * 技能内容以**文件**为准（`SKILL.md`），数据库只存索引和本地状态。
 * 每次启动 / 用户回到技能页时重新扫描一次目录，同步到索引。
 */
class SkillRepository(
    private val context: Context,
    private val db: com.mynok.myagent.data.db.MyAgentDatabase,
    private val fileStore: SkillFileStore,
) {

    private val dao = db.skillDao()

    // ------------------------------------------------------------ 目录授权

    /** 技能目录是否已经授权。 */
    suspend fun isDirectoryBound(): Boolean = fileStore.isReady()

    /**
     * 首次启动的引导提示是否已经弹过。
     *
     * 用户拒绝或忽略之后就不再打扰，但只要还没授权目录，
     * 技能页里还能再点「选择技能目录」。
     */
    suspend fun shouldShowOnboarding(): Boolean {
        if (fileStore.isReady()) return false
        val shown = context.skillDirectoryStore.data.first()[KEY_ONBOARDING_SHOWN] ?: false
        return !shown
    }
    /** 记下引导提示已经弹过。 */
    suspend fun markOnboardingShown() {
        context.skillDirectoryStore.edit { it[KEY_ONBOARDING_SHOWN] = true }
    }

    /** 技能目录的显示名。 */
    fun directoryName(): String = fileStore.rootName

    /**
     * 绑定用户选择的技能目录，并在里面建 `skills` 子目录。
     */
    suspend fun bindDirectory(treeUri: Uri): Boolean {
        val ok = fileStore.bind(treeUri)
        if (ok) {
            context.skillDirectoryStore.edit { it[KEY_SKILL_DIR] = treeUri.toString() }
            syncFromFiles()
        }
        return ok
    }

    /** 恢复上次的目录授权。 */
    suspend fun restoreDirectory() {
        val raw = context.skillDirectoryStore.data.first()[KEY_SKILL_DIR] ?: return
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return
        fileStore.bind(uri)
    }

    /** 解除目录绑定。 */
    suspend fun unbindDirectory() {
        fileStore.unbind()
        context.skillDirectoryStore.edit { it.remove(KEY_SKILL_DIR) }
        dao.deleteFileSkills()
    }

    // ------------------------------------------------------------ 索引同步

    /**
     * 扫描技能目录，同步到索引。
     *
     * - 文件里的技能：按目录名作为 id，upsert
     * - 索引里有但文件没了的：删掉
     * - 内置技能（source=BUILTIN）：原样保留
     */
    suspend fun syncFromFiles(): Int = withContext(Dispatchers.IO) {
        if (!fileStore.isReady()) return@withContext 0
        val scanned = fileStore.scan()
        val existingFileSkills = dao.getFileSkills()
        val scannedIds = scanned.map { it.definition.name }.toSet()

        var maxOrder = 0
        dao.observeAll().first().forEach { maxOrder = maxOf(maxOrder, it.sortOrder) }

        scanned.forEachIndexed { index, skill ->
            val definition = skill.definition
            dao.upsert(
                SkillEntity(
                    id = definition.name,
                    name = definition.name,
                    description = definition.description,
                    prompt = definition.body,
                    forceTools = definition.allowedTools.joinToString(","),
                    sortOrder = maxOrder + index + 1,
                    source = SkillEntity.SOURCE_FILE,
                    sourceDir = skill.relativePath,
                    version = definition.version.orEmpty(),
                    license = definition.license.orEmpty(),
                    emoji = definition.emoji.orEmpty(),
                    homepage = definition.homepage.orEmpty(),
                    requiredEnv = definition.requiredEnv.joinToString(","),
                    requiredBins = definition.requiredBins.joinToString(","),
                    requiredOs = definition.requiredOs.joinToString(","),
                    enabled = existingFileSkills
                        .firstOrNull { it.id == definition.name }
                        ?.enabled
                        ?: true,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }

        // 文件被用户删掉的，索引也要删
        existingFileSkills
            .filter { it.id !in scannedIds }
            .forEach { dao.delete(it.id) }

        scanned.size
    }

    // ------------------------------------------------------------ 读写

    fun observeAll(): Flow<List<SkillEntity>> = dao.observeAll()

    fun observeEnabled(): Flow<List<SkillEntity>> = dao.observeEnabled()

    suspend fun get(id: String): SkillEntity? = dao.get(id)

    suspend fun getEnabled(): List<SkillEntity> = dao.getEnabled()

    /** 已启用的技能定义，用于拼进系统提示。 */
    suspend fun enabledDefinitions(): List<SkillDefinition> = dao.getEnabled().map { it.toDefinition() }

    /** 把索引行还原成技能定义。 */
    fun toDefinition(entity: SkillEntity): SkillDefinition = entity.toDefinition()

    suspend fun upsert(skill: SkillEntity) = dao.upsert(skill)

    suspend fun delete(id: String) = dao.delete(id)

    /** 设置启用状态。 */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.get(id)?.let { dao.upsert(it.copy(enabled = enabled)) }
    }

    /** 调整排序。 */
    suspend fun reorder(ids: List<String>) {
        ids.forEachIndexed { index, id ->
            dao.get(id)?.let { dao.upsert(it.copy(sortOrder = index + 1)) }
        }
    }

    // ------------------------------------------------------------ 文件操作

    /**
     * 写一个技能文件（模型创建技能时用）。
     */
    suspend fun writeSkillFile(dirName: String, content: String): Boolean =
        fileStore.writeSkillFile(dirName, content)

    /**
     * 读技能原始文件内容。
     */
    suspend fun readSkillFile(relativePath: String): String? = fileStore.readSkillFile(relativePath)

    /**
     * 列出技能目录下的附属文件。
     */
    suspend fun listSkillFiles(relativePath: String) = fileStore.listSkillFiles(relativePath)

    /**
     * 读技能目录下的附属文件。
     */
    suspend fun readSkillFileAt(relativePath: String, fileName: String): String? =
        fileStore.readSkillFileAt(relativePath, fileName)

    /**
     * 删除技能文件目录，并同步索引。
     */
    suspend fun deleteSkillFile(relativePath: String): Boolean {
        val ok = fileStore.deleteSkill(relativePath)
        if (ok) syncFromFiles()
        return ok
    }

    /**
     * 技能名合法性检查（Agent Skills 规范）。
     */
    fun isValidName(name: String): Boolean = name.isNotEmpty() &&
        name.length <= 64 &&
        name.all { it.isLetterOrDigit() && it.code < 128 || it == '-' } &&
        !name.startsWith("-") &&
        !name.endsWith("-") &&
        !name.contains("--")

    companion object {
        private val KEY_SKILL_DIR = stringPreferencesKey("skill_dir")
        private val KEY_ONBOARDING_SHOWN = booleanPreferencesKey("onboarding_shown")
    }
}

/** 索引行 → 技能定义。 */
fun SkillEntity.toDefinition(): SkillDefinition = SkillDefinition(
    name = name,
    description = description,
    body = prompt,
    slug = id,
    license = license.takeIf { it.isNotBlank() },
    compatibility = null,
    allowedTools = forceTools.split(',').map { it.trim() }.filter { it.isNotEmpty() },
    version = version.takeIf { it.isNotBlank() },
    emoji = emoji.takeIf { it.isNotBlank() },
    homepage = homepage.takeIf { it.isNotBlank() },
    requiredOs = requiredOs.split(',').map { it.trim() }.filter { it.isNotEmpty() },
    requiredEnv = requiredEnv.split(',').map { it.trim() }.filter { it.isNotEmpty() },
    requiredBins = requiredBins.split(',').map { it.trim() }.filter { it.isNotEmpty() },
)
