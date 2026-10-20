package com.mynok.myagent.core.tools

import kotlinx.serialization.json.JsonObject

/**
 * 工具注册表。
 *
 * 内置工具与 MCP 工具共用一份注册表；MCP 工具的命名空间形式为 `server__tool`，
 * 与内置工具冲突时自动加前缀。
 */
class ToolRegistry {

    private val tools = linkedMapOf<String, Tool>()

    /** 冲突时改名（返回最终使用的名字）。 */
    fun register(tool: Tool): String {
        val name = uniqueName(tool.spec.name)
        tools[name] = tool
        return name
    }

    fun registerAll(list: Iterable<Tool>): List<String> = list.map { register(it) }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun get(name: String): Tool? = tools[name]

    fun all(): List<Tool> = tools.values.toList()

    fun specs(): List<com.mynok.myagent.core.model.ToolSpec> = tools.values.map { it.spec }

    fun isEmpty(): Boolean = tools.isEmpty()

    fun size(): Int = tools.size

    /** 找出唯一可用名，冲突时追加后缀。 */
    private fun uniqueName(desired: String): String {
        if (desired !in tools) return desired
        var i = 2
        while ("$desired$i" in tools) i++
        return "$desired$i"
    }
}

/** 解析工具参数：优先按 schema 字段名，其次忽略大小写。 */
fun JsonObject.stringArg(vararg names: String): String? {
    for (n in names) {
        val v = this[n] ?: continue
        val primitive = v as? kotlinx.serialization.json.JsonPrimitive
        if (primitive != null && !primitive.isString) continue
        val s = primitive?.content ?: continue
        if (s.isNotBlank()) return s
    }
    return null
}

/** 解析整型参数。 */
fun JsonObject.intArg(vararg names: String): Int? {
    for (n in names) {
        val v = (this[n] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
        v.toIntOrNull()?.let { return it }
    }
    return null
}

/** 解析布尔参数。 */
fun JsonObject.boolArg(vararg names: String, default: Boolean = false): Boolean {
    for (n in names) {
        val v = (this[n] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: continue
        when (v.lowercase()) {
            "true", "1", "yes" -> return true
            "false", "0", "no" -> return false
        }
    }
    return default
}
