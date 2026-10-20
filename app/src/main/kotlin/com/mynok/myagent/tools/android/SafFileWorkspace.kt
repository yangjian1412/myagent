package com.mynok.myagent.tools.android

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.mynok.myagent.core.tools.FileWorkspace
import com.mynok.myagent.core.tools.WorkspaceEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 基于 SAF（Storage Access Framework）的工作区。
 *
 * Android 上没有"任意路径"的读写权限，只能由用户授权目录树（tree URI）。
 * 这里把多个已授权的目录抽象成一棵虚拟文件树：
 * - `/` 列出所有已授权的根目录
 * - `/<根id>/子目录/文件` 定位到具体文档
 */
class SafFileWorkspace(
    private val context: Context,
) : FileWorkspace {

    /** 一个已授权的目录根。 */
    data class Root(
        val id: String,
        val displayName: String,
        val treeUri: Uri,
        val document: DocumentFile?,
    )

    @Volatile
    private var roots: List<Root> = emptyList()

    fun setRoots(newRoots: List<Root>) {
        roots = newRoots
    }

    fun roots(): List<Root> = roots

    fun isEmpty(): Boolean = roots.isEmpty()

    override suspend fun list(path: String): List<WorkspaceEntry> = withContext(Dispatchers.IO) {
        val normalized = normalize(path)
        if (normalized == "/") {
            return@withContext roots.map { root ->
                WorkspaceEntry(
                    path = "/${root.id}",
                    name = root.displayName,
                    isDirectory = true,
                )
            }
        }
        val doc = resolve(normalized) ?: return@withContext emptyList()
        doc.listFiles().map { child ->
            WorkspaceEntry(
                path = childPath(normalized, child.name.orEmpty()),
                name = child.name.orEmpty(),
                isDirectory = child.isDirectory,
                size = if (child.isFile) child.length() else 0,
                lastModified = child.lastModified(),
            )
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
    }

    override suspend fun readText(path: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
        val doc = resolve(normalize(path))
            ?: throw IllegalArgumentException("路径不存在或未授权：$path")
        if (doc.isDirectory) throw IllegalArgumentException("$path 是目录，不是文件")
        val bytes = context.contentResolver.openInputStream(doc.uri)?.use { input ->
            val buffer = ByteArray(16 * 1024)
            val out = java.io.ByteArrayOutputStream()
            var total = 0
            while (total < maxBytes) {
                val read = input.read(buffer)
                if (read <= 0) break
                val toRead = minOf(buffer.size, maxBytes - total)
                out.write(buffer, 0, toRead)
                total += toRead
            }
            out.toByteArray()
        } ?: throw IllegalStateException("无法读取文件：$path")
        String(bytes, Charsets.UTF_8)
    }

    override suspend fun writeText(path: String, content: String): Unit = withContext(Dispatchers.IO) {
        val normalized = normalize(path)
        val parentPath = parentOf(normalized)
        val parentDoc = resolve(parentPath)
            ?: throw IllegalArgumentException("目标目录不存在或未授权：$parentPath")
        val name = nameOf(normalized)
        val existing = parentDoc.findFile(name)
        if (existing != null && existing.isFile) {
            context.contentResolver.openOutputStream(existing.uri, "wt")?.use {
                it.write(content.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("无法写入文件：$path")
        } else {
            val created = parentDoc.createFile(mimeOf(name), name)
                ?: throw IllegalStateException("无法创建文件：$path")
            context.contentResolver.openOutputStream(created.uri, "wt")?.use {
                it.write(content.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("无法写入文件：$path")
        }
    }

    override suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        resolve(normalize(path)) != null
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        val doc = resolve(normalize(path)) ?: return@withContext false
        doc.delete()
    }

    override suspend fun mkdir(path: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = normalize(path)
        val parentDoc = resolve(parentOf(normalized)) ?: return@withContext false
        parentDoc.createDirectory(nameOf(normalized)) != null
    }

    // ------------------------------------------------------------------ 内部

    private fun normalize(path: String): String {
        val p = path.trim().replace('\\', '/')
        val withSlash = if (p.startsWith("/")) p else "/$p"
        return withSlash.trimEnd('/').ifEmpty { "/" }
    }

    private fun resolve(path: String): DocumentFile? {
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        val root = roots.firstOrNull { it.id == segments[0] } ?: return null
        var current: DocumentFile? = root.document ?: return null
        for (i in 1 until segments.size) {
            current = current?.findFile(segments[i]) ?: return null
        }
        return current
    }

    private fun childPath(parent: String, name: String): String =
        if (parent == "/") "/$name" else "$parent/$name"

    private fun parentOf(path: String): String {
        val idx = path.lastIndexOf('/')
        if (idx <= 0) return "/"
        return path.substring(0, idx)
    }

    private fun nameOf(path: String): String = path.substringAfterLast('/')

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "md" -> "text/plain"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    companion object {
        /** 构造一个已授权的根。 */
        fun root(context: Context, id: String, treeUri: Uri, displayName: String? = null): Root =
            Root(
                id = id,
                displayName = displayName ?: displayNameOf(context, treeUri),
                treeUri = treeUri,
                document = DocumentFile.fromTreeUri(context, treeUri),
            )

        /** 从 tree URI 里取可读名称。 */
        fun displayNameOf(context: Context, treeUri: Uri): String {
            val doc = DocumentFile.fromTreeUri(context, treeUri)
            return doc?.name ?: treeUri.lastPathSegment?.substringAfterLast(':') ?: "已授权目录"
        }

        /** 从 tree URI 里取目录下的 documentId。 */
        fun documentIdOf(treeUri: Uri): String? =
            DocumentsContract.getTreeDocumentId(treeUri)
    }
}
