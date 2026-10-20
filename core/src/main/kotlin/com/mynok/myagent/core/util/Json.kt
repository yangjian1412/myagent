package com.mynok.myagent.core.util

import kotlinx.serialization.json.Json

/** 全局共享的 JSON 配置。 */
val MyAgentJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

/** 宽松解析：失败时返回 null 而不是抛异常。 */
inline fun <reified T> String.decodeLenientOrNull(): T? =
    runCatching { MyAgentJson.decodeFromString<T>(this) }.getOrNull()
