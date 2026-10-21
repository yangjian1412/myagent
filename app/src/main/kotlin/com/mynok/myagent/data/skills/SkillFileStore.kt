package com.mynok.myagent.data.skills

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.mynok.myagent.core.skills.SkillDefinition
import com.mynok.myagent.core.skills.SkillParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 技能文件仓库。
 *
 * 技能是**文件**：一个目录一个技能，里面必须有 `SKILL.md`。
 * 目录通过 SAF 授权，用户可以在手机文件管理器里直接增删改，
 * 也可以让模型用工具写文件。
 *
 * 目录结构（在授权的根目录下）：
 * ```
 * <root>/
 *   my-skill/
 *     SKILL.md
 *     references/...
 * ```
 */
class SkillFileStore(
    private val context: Context,
) {

    /** 授权的技能根目录。 */
    @Volatile
    private var rootUri: Uri? = null

    /** 授权的技能根目录下的 `skills` 子目录。 */
    @Volatile
    private var skillsDir: Uri? = null

    /** 根目录的显示名。 */
    @Volatile
    var rootName: String = ""
        private set

    fun isReady(): Boolean = skillsDir != null

    fun rootUri(): Uri? = rootUri

    /**
     * 绑定授权目录，并在其中创建 `skills` 子目录。
     */
    suspend fun bind(treeUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext false
        if (!root.isDirectory) return@withContext false
        val skills = root.findFile(SKILLS_SUBDIR) ?: root.createDirectory(SKILLS_SUBDIR)
        if (skills == null || !skills.isDirectory) return@withContext false
        rootUri = treeUri
        skillsDir = skills.uri
        rootName = root.name ?: "技能目录"
        true
    }

    /** 解绑（用户换了目录或撤销授权）。 */
    fun unbind() {
        rootUri = null
        skillsDir = null
        rootName = ""
    }

    /**
     * 扫描技能目录，返回所有带 SKILL.md 的技能。
     *
     * @param depthLimit 最多往下找几层（ClawHub 允许分组目录）
     */
    suspend fun scan(depthLimit: Int = 3): List<ScannedSkill> = withContext(Dispatchers.IO) {
        val dir = skillsDir?.let { DocumentFile.fromTreeUri(context, it) } ?: return@withContext emptyList()
        val result = mutableListOf<ScannedSkill>()
        scanDirectory(dir, "", result, depthLimit)
        result
    }

    private fun scanDirectory(
        dir: DocumentFile,
        prefix: String,
        out: MutableList<ScannedSkill>,
        depthLimit: Int,
    ) {
        if (depthLimit < 0) return
        val children = dir.listFiles()
        for (child in children) {
            if (!child.isDirectory) continue
            val name = child.name ?: continue
            if (name.startsWith(".")) continue
            val skillFile = child.findFile(SKILL_FILE)
            if (skillFile != null && skillFile.isFile) {
                val text = runCatching {
                    context.contentResolver.openInputStream(skillFile.uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.getOrNull()
                if (text != null) {
                    val definition = SkillParser.parse(text, fallbackName = name)
                    if (definition != null) {
                        out += ScannedSkill(
                            definition = definition,
                            dirName = name,
                            relativePath = if (prefix.isEmpty()) name else "$prefix/$name",
                            fileSize = skillFile.length(),
                        )
                    }
                }
                // 找到 SKILL.md 就不再往下找（和 ClawHub 的分组发现规则一致）
                continue
            }
            scanDirectory(child, if (prefix.isEmpty()) name else "$prefix/$name", out, depthLimit - 1)
        }
    }

    /**
     * 读取某个技能的完整 SKILL.md 文本。
     */
    suspend fun readSkillFile(relativePath: String): String? = withContext(Dispatchers.IO) {
        val dir = skillsDir?.let { DocumentFile.fromTreeUri(context, it) } ?: return@withContext null
        val skillFile = findSkillFile(dir, relativePath) ?: return@withContext null
        runCatching {
            context.contentResolver.openInputStream(skillFile.uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    /**
     * 写一个技能文件（新建或覆盖）。
     *
     * @param relativePath 技能目录名
     * @param content SKILL.md 全文
     */
    suspend fun writeSkillFile(relativePath: String, content: String): Boolean =
        withContext(Dispatchers.IO) {
            val dir = skillsDir?.let { DocumentFile.fromTreeUri(context, it) } ?: return@withContext false
            val skillDir = dir.findFile(relativePath) ?: dir.createDirectory(relativePath)
            if (skillDir == null || !skillDir.isDirectory) return@withContext false
            val existing = skillDir.findFile(SKILL_FILE)
            if (existing != null && existing.isFile) {
                runCatching {
                    context.contentResolver.openOutputStream(existing.uri, "wt")?.use {
                        it.write(content.toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess
            } else {
                val created = skillDir.createFile("text/markdown", SKILL_FILE) ?: return@withContext false
                runCatching {
                    context.contentResolver.openOutputStream(created.uri, "wt")?.use {
                        it.write(content.toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess
            }
        }

    /**
     * 删除一个技能目录。
     */
    suspend fun deleteSkill(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        val dir = skillsDir?.let { DocumentFile.fromTreeUri(context, it) } ?: return@withContext false
        val skillDir = dir.findFile(relativePath) ?: return@withContext false
        skillDir.delete()
    }

    /**
     * 列出某个技能目录下的附属文件（references / assets 等）。
     */
    suspend fun listSkillFiles(relativePath: String): List<SkillFileEntry> =
        withContext(Dispatchers.IO) {
            val dir = skillsDir?.let { DocumentFile.fromTreeUri(context, it) } ?: return@withContext emptyList()
            val skillDir = dir.findFile(relativePath) ?: return@withContext emptyList()
            skillDir.listFiles().mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                SkillFileEntry(
                    name = name,
                    isDirectory = file.isDirectory,
                    size = if (file.isFile) file.length() else 0,
                )
            }.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
        }

    /**
     * 读取技能目录下的一个附属文件。
     */
    suspend fun readSkillFileAt(relativePath: String, fileName: String): String? =
        withContext(Dispatchers.IO) {
            val dir = skillsDir?.let { DocumentFile.fromTreeUri(context, it) } ?: return@withContext null
            val skillDir = dir.findFile(relativePath) ?: return@withContext null
            val file = skillDir.findFile(fileName) ?: return@withContext null
            if (!file.isFile) return@withContext null
            runCatching {
                context.contentResolver.openInputStream(file.uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrNull()
        }

    private fun findSkillFile(root: DocumentFile, relativePath: String): DocumentFile? {
        var current: DocumentFile? = root
        for (segment in relativePath.split('/').filter { it.isNotEmpty() }) {
            current = current?.findFile(segment) ?: return null
        }
        return current?.findFile(SKILL_FILE)
    }

    companion object {
        const val SKILL_FILE = "SKILL.md"
        const val SKILLS_SUBDIR = "skills"
    }
}

/** 扫描到的一个技能。 */
data class ScannedSkill(
    val definition: SkillDefinition,
    /** 技能目录名。 */
    val dirName: String,
    /** 相对技能根的路径（可能带分组层级）。 */
    val relativePath: String,
    val fileSize: Long = 0,
)

/** 技能目录下的一个文件。 */
data class SkillFileEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
)
