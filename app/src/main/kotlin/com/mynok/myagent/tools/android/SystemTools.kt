package com.mynok.myagent.tools.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import com.mynok.myagent.core.model.ToolSpec
import com.mynok.myagent.core.tools.Tool
import com.mynok.myagent.core.tools.ToolApproval
import com.mynok.myagent.core.tools.ToolContext
import com.mynok.myagent.core.tools.ToolResult
import com.mynok.myagent.core.tools.ToolRisk
import com.mynok.myagent.core.tools.intArg
import com.mynok.myagent.core.tools.stringArg
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt
import java.util.Calendar

/**
 * 闹钟、音量、手电筒、拨号等系统能力。
 *
 * 闹钟使用时钟应用声明的普通权限；拨号在缺少电话权限时会降级到拨号界面。
 */
object SystemTools {

    // ------------------------------------------------------------ 闹钟 / 计时器

    fun alarm(context: Context): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "alarm",
            description = "设置手机闹钟或倒计时，也可以打开系统闹钟页面。" +
                "action=set_alarm（需要 hour、minute，可选 label、days）、" +
                "set_timer（需要 seconds，可选 label）、show_alarms。" +
                "会尽量静默设置；部分系统的时钟应用可能仍停在预填页面，需要用户点保存。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add(JsonPrimitive("set_alarm"))
                            add(JsonPrimitive("set_timer"))
                            add(JsonPrimitive("show_alarms"))
                        })
                    })
                    put("hour", buildJsonObject {
                        put("type", "integer")
                        put("description", "闹钟小时，0–23")
                    })
                    put("minute", buildJsonObject {
                        put("type", "integer")
                        put("description", "闹钟分钟，0–59")
                    })
                    put("seconds", buildJsonObject {
                        put("type", "integer")
                        put("description", "倒计时秒数，1–86400")
                    })
                    put("label", buildJsonObject {
                        put("type", "string")
                        put("description", "闹钟/计时器备注")
                    })
                    put("days", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "integer") })
                        put("description", "重复日，ISO 星期：1=周一 … 7=周日；省略表示只响一次")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("action")) })
            },
        )

        override val risk = ToolRisk.WRITE

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val action = args.stringArg("action")?.lowercase() ?: "set_alarm"
            val intent = when (action) {
                "set_alarm" -> {
                    val hour = args.intArg("hour") ?: return ToolResult.error("设置闹钟需要 hour 参数")
                    val minute = args.intArg("minute") ?: return ToolResult.error("设置闹钟需要 minute 参数")
                    if (hour !in 0..23) return ToolResult.error("hour 必须在 0–23 之间")
                    if (minute !in 0..59) return ToolResult.error("minute 必须在 0–59 之间")
                    val days = parseIsoDays(args)
                    Intent(AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(AlarmClock.EXTRA_HOUR, hour)
                        putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        args.stringArg("label", "message")?.let {
                            putExtra(AlarmClock.EXTRA_MESSAGE, it)
                        }
                        if (days.isNotEmpty()) {
                            putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map { isoToCalendarDay(it) }))
                        }
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    }
                }

                "set_timer" -> {
                    val seconds = args.intArg("seconds", "length", "duration")
                        ?: return ToolResult.error("设置倒计时需要 seconds 参数")
                    if (seconds !in 1..86_400) {
                        return ToolResult.error("seconds 必须在 1–86400 之间")
                    }
                    Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                        args.stringArg("label", "message")?.let {
                            putExtra(AlarmClock.EXTRA_MESSAGE, it)
                        }
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    }
                }

                "show_alarms" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)

                else -> return ToolResult.error("不支持的 action：$action")
            }

            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolResult.error("没有找到可用的系统时钟应用，无法完成该操作。")
            }
            return try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                when (action) {
                    "set_timer" -> ToolResult.ok(
                        "已向系统时钟发送 ${args.intArg("seconds", "length", "duration")} 秒倒计时设置请求。" +
                            "若时钟应用不支持静默设置，会停在预填页面，需要用户点保存。",
                    )
                    "show_alarms" -> ToolResult.ok("已打开系统闹钟页面。")
                    else -> ToolResult.ok(
                        "已向系统时钟发送闹钟设置请求。" +
                            "若时钟应用不支持静默设置，会停在预填页面，需要用户点保存。",
                    )
                }
            } catch (e: Exception) {
                ToolResult.error("打开时钟应用失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun parseIsoDays(args: JsonObject): List<Int> {
        val array = args["days"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
            .filter { it in 1..7 }
            .distinct()
    }

    /** ISO 星期（1=周一 … 7=周日）转 Calendar 常量（1=周日 … 7=周六）。 */
    private fun isoToCalendarDay(iso: Int): Int =
        if (iso == 7) Calendar.SUNDAY else iso + 1

    // ------------------------------------------------------------ 音量

    fun volume(context: Context): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "volume",
            description = "查询或调节手机音量。action=get / set / adjust；" +
                "stream 可选 music（默认）、ring、alarm、notification、call、system；" +
                "set 需要 percent（0–100），adjust 需要 direction=up/down。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add(JsonPrimitive("get"))
                            add(JsonPrimitive("set"))
                            add(JsonPrimitive("adjust"))
                        })
                    })
                    put("stream", buildJsonObject {
                        put("type", "string")
                        put("description", "music / ring / alarm / notification / call / system")
                    })
                    put("percent", buildJsonObject {
                        put("type", "integer")
                        put("description", "目标音量百分比，0–100")
                    })
                    put("direction", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add(JsonPrimitive("up"))
                            add(JsonPrimitive("down"))
                        })
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("action")) })
            },
        )

        override val risk = ToolRisk.READ_ONLY

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return ToolResult.error("无法获取音频服务")
            val streamName = args.stringArg("stream")?.lowercase() ?: "music"
            val stream = streamOf(streamName)
                ?: return ToolResult.error("不支持的 stream：$streamName")

            val action = args.stringArg("action")?.lowercase() ?: "get"
            if (action == "set" || action == "adjust") {
                val approved = ctx.approver.request(
                    ToolApproval(
                        toolName = spec.name,
                        reason = "调节手机 $streamName 音量",
                        risk = ToolRisk.WRITE,
                        detail = args.toString().take(300),
                    ),
                )
                if (!approved) return ToolResult.error("用户拒绝调节音量（volume $action）。")
            }
            return try {
                when (action) {
                    "get" -> ToolResult.ok(describe(manager, stream, streamName))

                    "set" -> {
                        val percent = args.intArg("percent", "level")
                            ?: return ToolResult.error("设置音量需要 percent 参数")
                        if (percent !in 0..100) {
                            return ToolResult.error("percent 必须在 0–100 之间")
                        }
                        val max = manager.getStreamMaxVolume(stream)
                        val target = (percent / 100f * max).roundToInt().coerceIn(0, max)
                        manager.setStreamVolume(stream, target, 0)
                        ToolResult.ok(
                            "已把 $streamName 音量调到 ${(target * 100f / max).roundToInt()}%。\n" +
                                describe(manager, stream, streamName),
                        )
                    }

                    "adjust" -> {
                        val direction = args.stringArg("direction")?.lowercase() ?: "up"
                        val adjust = if (direction == "down") {
                            AudioManager.ADJUST_LOWER
                        } else {
                            AudioManager.ADJUST_RAISE
                        }
                        manager.adjustStreamVolume(stream, adjust, 0)
                        ToolResult.ok(
                            "已${if (direction == "down") "调低" else "调高"} $streamName 音量。\n" +
                                describe(manager, stream, streamName),
                        )
                    }

                    else -> ToolResult.error("不支持的 action：$action")
                }
            } catch (e: SecurityException) {
                ToolResult.error(
                    "系统拒绝了音量修改（可能开启了勿扰模式）：${e.message ?: "SecurityException"}",
                )
            } catch (e: Exception) {
                ToolResult.error("调节音量失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun streamOf(name: String): Int? = when (name) {
        "music", "media" -> AudioManager.STREAM_MUSIC
        "ring", "ringtone" -> AudioManager.STREAM_RING
        "alarm" -> AudioManager.STREAM_ALARM
        "notification", "notify" -> AudioManager.STREAM_NOTIFICATION
        "call", "voice" -> AudioManager.STREAM_VOICE_CALL
        "system" -> AudioManager.STREAM_SYSTEM
        else -> null
    }

    private fun describe(manager: AudioManager, stream: Int, name: String): String {
        val current = manager.getStreamVolume(stream)
        val max = manager.getStreamMaxVolume(stream)
        val percent = if (max > 0) (current * 100f / max).roundToInt() else 0
        return "$name 音量：$current/$max（$percent%）。"
    }

    // ------------------------------------------------------------ 手电筒

    private var torchOn = false

    fun torch(context: Context): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "torch",
            description = "打开、关闭或切换手机手电筒。action=on / off / toggle。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            add(JsonPrimitive("on"))
                            add(JsonPrimitive("off"))
                            add(JsonPrimitive("toggle"))
                        })
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("action")) })
            },
        )

        override val risk = ToolRisk.WRITE

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return ToolResult.error("无法获取相机服务")
            val action = args.stringArg("action")?.lowercase() ?: "toggle"
            val target = when (action) {
                "on" -> true
                "off" -> false
                "toggle" -> !torchOn
                else -> return ToolResult.error("不支持的 action：$action")
            }

            val cameraId = runCatching {
                manager.cameraIdList.firstOrNull { id ->
                    manager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
            }.getOrNull() ?: return ToolResult.error("这台设备没有可用的闪光灯。")

            return try {
                manager.setTorchMode(cameraId, target)
                torchOn = target
                ToolResult.ok(if (target) "手电筒已打开。" else "手电筒已关闭。")
            } catch (e: CameraAccessException) {
                ToolResult.error("控制手电筒失败：${e.reason}")
            } catch (e: Exception) {
                ToolResult.error("控制手电筒失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ------------------------------------------------------------ 拨号

    fun phone(context: Context): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "call",
            description = "拨打一个电话号码。有电话权限时直接拨出，没有权限时只打开拨号界面。" +
                "只在用户明确要求打电话时使用。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("number", buildJsonObject {
                        put("type", "string")
                        put("description", "电话号码，可包含 + - 空格")
                    })
                })
                put("required", buildJsonArray { add(JsonPrimitive("number")) })
            },
        )

        override val risk = ToolRisk.WRITE

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
            val number = args.stringArg("number", "phone", "tel")
                ?: return ToolResult.error("缺少 number 参数")
            val clean = number.trim()
            if (clean.isBlank() || clean.none { it.isDigit() }) {
                return ToolResult.error("电话号码不合法：$number")
            }
            val canCall = context.hasPermission(Manifest.permission.CALL_PHONE)
            val intent = Intent(
                if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL,
                Uri.fromParts("tel", clean, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolResult.error("没有找到可用的拨号应用。")
            }
            return try {
                context.startActivity(intent)
                if (canCall) {
                    ToolResult.ok("正在拨打 $clean。")
                } else {
                    ToolResult.ok(
                        "已打开拨号界面并填入 $clean；因为没有电话权限，需要用户手动点拨号。\n" +
                            permissionHint("电话"),
                    )
                }
            } catch (e: SecurityException) {
                ToolResult.error("拨号被系统拒绝：${e.message ?: "SecurityException"}")
            } catch (e: Exception) {
                ToolResult.error("打开拨号界面失败：${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
