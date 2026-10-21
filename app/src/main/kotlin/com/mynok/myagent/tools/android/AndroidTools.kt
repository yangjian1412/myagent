package com.mynok.myagent.tools.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.mynok.myagent.core.model.ToolSpec
import com.mynok.myagent.core.tools.FileWorkspace
import com.mynok.myagent.core.tools.Tool
import com.mynok.myagent.core.tools.ToolContext
import com.mynok.myagent.core.tools.ToolResult
import com.mynok.myagent.core.tools.ToolRisk
import com.mynok.myagent.core.tools.boolArg
import com.mynok.myagent.core.tools.intArg
import com.mynok.myagent.core.tools.stringArg
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 剪贴板工具。
 */
class ClipboardTool(private val context: Context) : Tool {

    override val spec = ToolSpec(
        name = "clipboard",
        description = "读取或写入手机剪贴板。action 为 read 或 write；write 需要 text 参数。",
        parameters = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("read"))
                        add(JsonPrimitive("write"))
                    })
                    put("description", "read 或 write")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "写入剪贴板的文本（action=write 时必填）")
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("action")) })
        },
    )

    override val risk = ToolRisk.WRITE

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val action = args.stringArg("action")?.lowercase() ?: "read"
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ToolResult.error("无法获取剪贴板服务")
        return when (action) {
            "write" -> {
                val text = args.stringArg("text", "content")
                    ?: return ToolResult.error("写入剪贴板需要 text 参数")
                manager.setPrimaryClip(ClipData.newPlainText("MyAgent", text))
                ToolResult.ok("已写入剪贴板（${text.length} 字符）。")
            }

            else -> {
                val clip = manager.primaryClip
                    ?: return ToolResult.ok("剪贴板是空的。")
                val text = (0 until clip.itemCount).joinToString("\n") { i ->
                    clip.getItemAt(i).coerceToText(context).toString()
                }
                ToolResult.ok(if (text.isBlank()) "剪贴板是空的。" else "剪贴板内容：\n$text")
            }
        }
    }
}

/**
 * 本地文件工具（走 SAF 授权目录）。
 */
class FileTools(private val workspace: FileWorkspace) {

    fun list(): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "list_files",
            description = "列出已授权目录下的文件与子目录。path 省略时列出根目录。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "目录路径，例如 / 或 /r1/子目录")
                    })
                })
                put("required", buildJsonArray { })
            },
        )

        override val risk = ToolRisk.READ_ONLY

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val path = args.stringArg("path", "dir", "directory") ?: "/"
            val entries = workspace.list(path)
            if (entries.isEmpty()) return ToolResult.ok("$path 下没有内容（或目录未授权）。")
            val sb = StringBuilder("$path 下的内容：\n")
            entries.forEach {
                sb.append(if (it.isDirectory) "[目录] " else "[文件] ")
                    .append(it.name)
                if (!it.isDirectory) sb.append("  (${it.size} 字节)")
                sb.append("  → ").append(it.path).append('\n')
            }
            return ToolResult.ok(sb.toString())
        }
    }

    fun read(): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "read_file",
            description = "读取已授权目录里的文本文件内容。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "文件完整路径，例如 /r1/notes/a.md")
                    })
                    put("max_bytes", buildJsonObject {
                        put("type", "integer")
                        put("description", "最大读取字节数，默认 200KB")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("path")) })
            },
        )

        override val risk = ToolRisk.READ_ONLY

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val path = args.stringArg("path", "file")
                ?: return ToolResult.error("缺少参数 path")
            val maxBytes = args.intArg("max_bytes", "maxBytes") ?: 200 * 1024
            return runCatching { workspace.readText(path, maxBytes) }
                .fold(
                    onSuccess = { ToolResult.ok("文件 $path 的内容：\n$it") },
                    onFailure = { ToolResult.error("读取失败：${it.message}") },
                )
        }
    }

    fun write(): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "write_file",
            description = "把文本写入已授权目录里的文件（文件不存在则创建）。会覆盖同名文件。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "文件完整路径，例如 /r1/notes/a.md")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "要写入的文本内容")
                    })
                })
                put("required", buildJsonArray {
                    add(JsonPrimitive("path"))
                    add(JsonPrimitive("content"))
                })
            },
        )

        override val risk = ToolRisk.WRITE

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val path = args.stringArg("path", "file")
                ?: return ToolResult.error("缺少参数 path")
            val content = args.stringArg("content", "text")
                ?: return ToolResult.error("缺少参数 content")
            return runCatching { workspace.writeText(path, content) }
                .fold(
                    onSuccess = { ToolResult.ok("已写入 $path（${content.length} 字符）。") },
                    onFailure = { ToolResult.error("写入失败：${it.message}") },
                )
        }
    }

    fun search(): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "search_files",
            description = "在已授权目录里按文件名关键字递归查找。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("keyword", buildJsonObject {
                        put("type", "string")
                        put("description", "文件名关键字")
                    })
                    put("root", buildJsonObject {
                        put("type", "string")
                        put("description", "起始目录，默认根目录")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("keyword")) })
            },
        )

        override val risk = ToolRisk.READ_ONLY

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val keyword = args.stringArg("keyword", "query", "name")
                ?: return ToolResult.error("缺少参数 keyword")
            val root = args.stringArg("root", "path") ?: "/"
            val found = mutableListOf<String>()

            suspend fun walk(path: String, depth: Int) {
                if (depth > 6 || found.size >= 50) return
                val entries = runCatching { workspace.list(path) }.getOrDefault(emptyList())
                for (e in entries) {
                    if (found.size >= 50) return
                    if (e.name.contains(keyword, ignoreCase = true)) {
                        found += "${if (e.isDirectory) "[目录] " else "[文件] "}${e.path}"
                    }
                    if (e.isDirectory) walk(e.path, depth + 1)
                }
            }
            walk(root, 0)
            if (found.isEmpty()) return ToolResult.ok("没有找到包含「$keyword」的文件。")
            return ToolResult.ok("找到 ${found.size} 项：\n" + found.joinToString("\n"))
        }
    }
}
