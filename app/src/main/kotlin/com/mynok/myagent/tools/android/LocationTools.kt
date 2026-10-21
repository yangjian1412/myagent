package com.mynok.myagent.tools.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import com.mynok.myagent.core.model.ToolSpec
import com.mynok.myagent.core.tools.Tool
import com.mynok.myagent.core.tools.ToolContext
import com.mynok.myagent.core.tools.ToolResult
import com.mynok.myagent.core.tools.ToolRisk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume

/**
 * 一次性定位。优先 GPS，失败后退到网络定位，最后退到最近一次已知位置。
 */
object LocationTools {

    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun location(context: Context): Tool = object : Tool {
        override val spec = ToolSpec(
            name = "location",
            description = "获取手机当前的大致位置（纬度、经度、精度）。" +
                "只在用户明确询问位置或附近信息时使用；需要定位权限，定位服务关闭时会返回错误。",
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { })
                put("required", buildJsonArray { })
            },
        )

        override val risk = ToolRisk.READ_ONLY

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
            withContext(Dispatchers.IO) {
                val fine = context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                val coarse = context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (!fine && !coarse) {
                    return@withContext ToolResult.error(permissionHint("定位"))
                }
                val manager = context.getSystemService(LocationManager::class.java)
                    ?: return@withContext ToolResult.error("无法获取定位服务")

                val enabled = listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER,
                ).filter { provider ->
                    runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
                }

                val lastKnown = enabled.mapNotNull { provider ->
                    runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                }.maxByOrNull { it.time }

                val providers = buildList {
                    if (fine && LocationManager.GPS_PROVIDER in enabled) {
                        add(LocationManager.GPS_PROVIDER)
                    }
                    if (LocationManager.NETWORK_PROVIDER in enabled) {
                        add(LocationManager.NETWORK_PROVIDER)
                    }
                }
                if (providers.isEmpty()) {
                    return@withContext lastKnown?.let { format(it, "最近一次已知位置") }
                        ?: ToolResult.error(
                            "定位服务已关闭，或设备没有可用的定位提供方。" +
                                "请让用户在系统设置里打开位置信息后重试。",
                        )
                }

                var location: Location? = null
                for (provider in providers) {
                    location = withTimeoutOrNull(8_000) { awaitLocation(context, manager, provider) }
                    if (location != null) break
                }
                val result = location ?: lastKnown
                if (result == null) {
                    return@withContext ToolResult.error(
                        "16 秒内没有拿到新的定位结果，也没有可用的历史位置。" +
                            "请到室外或窗边重试，或检查系统定位开关。",
                    )
                }
                format(result, if (location == null) "最近一次已知位置" else "实时定位")
            }
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitLocation(
        context: Context,
        manager: LocationManager,
        provider: String,
    ): Location? = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        LocationManagerCompat.getCurrentLocation(
            manager,
            provider,
            signal,
            ContextCompat.getMainExecutor(context),
        ) { location ->
            if (!cont.isCompleted) cont.resume(location)
        }
        cont.invokeOnCancellation { signal.cancel() }
    }

    private fun format(location: Location, source: String): ToolResult {
        val time = Instant.ofEpochMilli(location.time)
            .atZone(ZoneId.systemDefault())
            .format(timeFormatter)
        val lat = "%.6f".format(location.latitude)
        val lon = "%.6f".format(location.longitude)
        return ToolResult.ok(
            buildString {
                appendLine("位置（$source）：")
                appendLine("纬度：$lat")
                appendLine("经度：$lon")
                appendLine("精度：约 ${location.accuracy.toInt()} 米")
                appendLine("来源：${location.provider ?: "未知"}")
                appendLine("时间：$time")
                append("地图：https://maps.google.com/?q=$lat,$lon")
            },
        )
    }
}
