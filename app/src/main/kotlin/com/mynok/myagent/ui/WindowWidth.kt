package com.mynok.myagent.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** 窗口宽度类别，沿用 Android 官方的自适应断点定义。 */
enum class WindowWidth {
    /** < 600dp，手机竖屏 */
    COMPACT,

    /** 600dp ~ 840dp，手机横屏 / 小平板 */
    MEDIUM,

    /** >= 840dp，平板 */
    EXPANDED,
}

/**
 * 当前窗口宽度类别。
 *
 * 用 [LocalConfiguration.screenWidthDp] 而不是 window-size-class：
 * 前者在分屏、折叠屏、多窗口下都会给出当前窗口的实际宽度，
 * 且不随 Compose 库版本变动而废弃。
 */
@Composable
fun rememberWindowWidth(): WindowWidth {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp < 600 -> WindowWidth.COMPACT
        widthDp < 840 -> WindowWidth.MEDIUM
        else -> WindowWidth.EXPANDED
    }
}
