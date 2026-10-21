package com.mynok.myagent.tools.android

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import com.mynok.myagent.core.model.ToolSpec
import com.mynok.myagent.core.tools.Tool
import com.mynok.myagent.core.tools.ToolContext
import com.mynok.myagent.core.tools.ToolResult
import com.mynok.myagent.core.tools.ToolRisk
import com.mynok.myagent.core.tools.intArg
import com.mynok.myagent.core.tools.stringArg
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 联系人只读搜索。只返回匹配到的姓名与号码，不导出整个通讯录。
 */
object ContactTools {

    fun contacts(context: Context): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "contacts",
            description = "按姓名或号码搜索手机联系人，返回姓名和电话号码。" +
                "只读，不会修改通讯录。需要联系人权限。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "姓名或号码的一部分")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put("description", "最多返回多少个联系人，默认 10，最大 30")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("query")) })
            },
        )

        override val risk = ToolRisk.READ_ONLY

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
            withContext(Dispatchers.IO) {
                if (!context.hasPermission(Manifest.permission.READ_CONTACTS)) {
                    return@withContext ToolResult.error(permissionHint("联系人"))
                }
                val query = args.stringArg("query", "q", "name")?.trim()
                    ?: return@withContext ToolResult.error("缺少 query 参数")
                if (query.isEmpty()) {
                    return@withContext ToolResult.error("query 不能为空")
                }
                val limit = (args.intArg("limit") ?: 10).coerceIn(1, 30)
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL,
                )
                val selection =
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                        "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
                val like = "%$query%"
                val people = LinkedHashMap<Long, MutableList<String>>()
                val names = mutableMapOf<Long, String>()

                try {
                    context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        projection,
                        selection,
                        arrayOf(like, like),
                        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
                    )?.use { cursor ->
                        while (cursor.moveToNext() && people.size < limit) {
                            val contactId = cursor.getLong(0)
                            val name = cursor.getString(1) ?: "（未命名）"
                            val number = cursor.getString(2)?.trim().orEmpty()
                            if (number.isEmpty()) continue
                            val type = cursor.getInt(3)
                            val label = cursor.getString(4)
                            val typeLabel = ContactsContract.CommonDataKinds.Phone
                                .getTypeLabel(context.resources, type, label)
                                .toString()
                            names[contactId] = name
                            people.getOrPut(contactId) { mutableListOf() }.add("$number（$typeLabel）")
                        }
                    }
                } catch (e: SecurityException) {
                    return@withContext ToolResult.error(permissionHint("联系人"))
                } catch (e: Exception) {
                    return@withContext ToolResult.error(
                        "读取联系人失败：${e.message ?: e.javaClass.simpleName}",
                    )
                }

                if (people.isEmpty()) {
                    return@withContext ToolResult.ok("没有找到匹配「$query」的联系人。")
                }
                val text = people.entries.joinToString("\n") { (id, numbers) ->
                    "- ${names[id] ?: "（未命名）"}：${numbers.joinToString("、")}"
                }
                ToolResult.ok("找到 ${people.size} 个联系人：\n$text")
            }
    }
}
