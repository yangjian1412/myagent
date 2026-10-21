package com.mynok.myagent.tools.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.mynok.myagent.core.model.ToolSpec
import com.mynok.myagent.core.tools.Tool
import com.mynok.myagent.core.tools.ToolContext
import com.mynok.myagent.core.tools.ToolResult
import com.mynok.myagent.core.tools.ToolRisk
import com.mynok.myagent.core.tools.stringArg
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * App 操作工具集。
 *
 * 拆成多个小工具而不是一个带 action 参数的大工具——模型选择工具的准确率更高。
 */
object AppTools {

    fun listApps(context: Context): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "list_apps",
            description = "列出手机上已安装且可启动的应用（名称、包名、版本）。用于找到要打开的应用。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "按名称或包名过滤，留空则返回全部")
                    })
                })
                put("required", buildJsonArray { })
            },
        )

        override val risk = ToolRisk.READ_ONLY

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val query = args.stringArg("query", "keyword", "name")?.lowercase()
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { app ->
                    runCatching {
                        pm.getLaunchIntentForPackage(app.packageName) != null
                    }.getOrDefault(false)
                }
                .filter { app ->
                    query.isNullOrBlank() ||
                        app.packageName.lowercase().contains(query) ||
                        pm.getApplicationLabel(app).toString().lowercase().contains(query)
                }
                .sortedBy { pm.getApplicationLabel(it).toString() }
                .take(200)

            if (apps.isEmpty()) {
                return ToolResult.ok("没有匹配的应用。")
            }
            val sb = StringBuilder("共 ${apps.size} 个应用：\n")
            apps.forEach { app ->
                val label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
                val version = runCatching { pm.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
                sb.append("- $label (${app.packageName})").append(version?.let { " v$it" } ?: "").append('\n')
            }
            return ToolResult.ok(sb.toString())
        }
    }

    fun launchApp(context: Context): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "launch_app",
            description = "启动手机上的一个应用。需要包名，可用 list_apps 查询。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("package", buildJsonObject {
                        put("type", "string")
                        put("description", "应用包名，例如 com.android.settings")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("package")) })
            },
        )

        override val risk = ToolRisk.WRITE

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val pkg = args.stringArg("package", "package_name", "app")
                ?: return ToolResult.error("缺少参数 package")
            val pm = context.packageManager
            val intent = pm.getLaunchIntentForPackage(pkg)
                ?: return ToolResult.error("未找到应用 $pkg，或它没有启动入口")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(intent)
                ToolResult.ok("已启动 $pkg。")
            } catch (e: Exception) {
                ToolResult.error("启动失败：${e.message}")
            }
        }
    }

    fun appInfo(context: Context): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "app_info",
            description = "查询应用的详细信息：版本、安装时间、权限数量、是否为系统应用。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("package", buildJsonObject {
                        put("type", "string")
                        put("description", "应用包名")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("package")) })
            },
        )

        override val risk = ToolRisk.READ_ONLY

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val pkg = args.stringArg("package", "package_name", "app")
                ?: return ToolResult.error("缺少参数 package")
            val pm = context.packageManager
            val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
                ?: return ToolResult.error("未找到应用 $pkg")
            val isSystem = (info.applicationInfo?.flags ?: 0) and
                android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
            val permissions = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS) }
                .getOrNull()?.requestedPermissions?.size ?: 0
            val label = runCatching { pm.getApplicationLabel(info.applicationInfo!!).toString() }
                .getOrDefault(pkg)
            return ToolResult.ok(
                buildString {
                    appendLine("应用名称: $label")
                    appendLine("包名: $pkg")
                    appendLine("版本: ${info.versionName} (${info.longVersionCode})")
                    appendLine("是否系统应用: ${if (isSystem) "是" else "否"}")
                    appendLine("申请权限数: $permissions")
                    appendLine("首次安装: ${info.firstInstallTime}")
                    appendLine("最近更新: ${info.lastUpdateTime}")
                },
            )
        }
    }

    fun openUrl(context: Context): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "open_url",
            description = "在手机浏览器里打开一个网址。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("url", buildJsonObject {
                        put("type", "string")
                        put("description", "完整 URL，必须以 http:// 或 https:// 开头")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("url")) })
            },
        )

        override val risk = ToolRisk.WRITE

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val url = args.stringArg("url", "link")
                ?: return ToolResult.error("缺少参数 url")
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return ToolResult.error("只支持 http/https 链接：$url")
            }
            return try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                ToolResult.ok("已在浏览器中打开 $url")
            } catch (e: Exception) {
                ToolResult.error("打开失败：${e.message}")
            }
        }
    }

    fun sendIntent(context: Context): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "send_intent",
            description = "发送一个 Android Intent。只在用户明确要求打开设置页或某个页面时使用，不要用它试探或绕过系统权限。常用 action：android.settings.SETTINGS（设置）、android.settings.APPLICATION_DETAILS_SETTINGS、android.intent.action.VIEW（带 url 打开）。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("description", "Intent action，例如 android.settings.SETTINGS")
                    })
                    put("data", buildJsonObject {
                        put("type", "string")
                        put("description", "可选，Intent data 的字符串")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("action")) })
            },
        )

        override val risk = ToolRisk.WRITE

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val action = args.stringArg("action") ?: return ToolResult.error("缺少参数 action")
            val data = args.stringArg("data", "url")
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!data.isNullOrBlank()) {
                intent.data = if (data.startsWith("http")) Uri.parse(data) else Uri.parse(data)
            }
            return try {
                context.startActivity(intent)
                ToolResult.ok("已发送 Intent：$action")
            } catch (e: Exception) {
                ToolResult.error("发送失败：${e.message}")
            }
        }
    }

    fun deviceInfo(context: Context): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "device_info",
            description = "获取设备状态：型号、Android 版本、屏幕分辨率、内存、存储剩余、电量与充电状态、当前网络。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { })
                put("required", buildJsonArray { })
            },
        )

        override val risk = ToolRisk.READ_ONLY

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val metrics = context.resources.displayMetrics
            val runtime = Runtime.getRuntime()
            val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
            val maxMb = runtime.maxMemory() / 1024 / 1024
            val stat = runCatching {
                context.filesDir.usableSpace / 1024 / 1024
            }.getOrDefault(0)
            val battery = context.getSystemService(android.os.BatteryManager::class.java)
            val batteryLevel = battery
                ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 }
            val charging = battery?.isCharging == true
            val network = describeNetwork(context)
            return ToolResult.ok(
                buildString {
                    appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                    appendLine("分辨率: ${metrics.widthPixels}x${metrics.heightPixels} @${metrics.densityDpi}dpi")
                    appendLine("内存: 已用 ${usedMb}MB / 上限 ${maxMb}MB")
                    appendLine("应用可用存储: ${stat}MB")
                    if (batteryLevel != null) {
                        appendLine("电量: $batteryLevel%（${if (charging) "充电中" else "未充电"}）")
                    } else {
                        appendLine("电量: 未知")
                    }
                    appendLine("网络: $network")
                },
            )
        }
    }

    private fun describeNetwork(context: Context): String {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
            ?: return "无法获取网络状态"
        val network = manager.activeNetwork ?: return "未连接"
        val caps = manager.getNetworkCapabilities(network) ?: return "未连接"
        val transport = when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝网络"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "其他网络"
        }
        val validated = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return "$transport（${if (validated) "可访问互联网" else "未验证联网"}）"
    }

    /** 打开"应用信息"系统页。 */
    fun openAppSettings(context: Context, packageName: String): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
}
