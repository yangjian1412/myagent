package com.mynok.myagent.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 主题模式。 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("白天"),
    DARK("夜间"),
    ;

    companion object {
        fun from(raw: String?): ThemeMode =
            entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}

/** 主题颜色。每个值对应一个种子色，由主题层展开成完整的配色方案。 */
enum class ThemeColor(val label: String, val seed: Long) {
    PURPLE("紫罗兰", 0xFF6C4BE0),
    BLUE("海洋蓝", 0xFF1565C0),
    GREEN("松林绿", 0xFF2E7D32),
    ORANGE("暖阳橙", 0xFFE65100),
    ROSE("山茶红", 0xFFC2185B),
    ;

    companion object {
        fun from(raw: String?): ThemeColor =
            entries.firstOrNull { it.name == raw } ?: PURPLE
    }
}

/** 外观等常规设置。 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val themeColor: ThemeColor = ThemeColor.PURPLE,
    /** Android 12+ 的「跟随壁纸取色」。开启后忽略 [themeColor]。 */
    val dynamicColor: Boolean = false,
    /** 发送消息后自动用第一句话命名会话。 */
    val autoTitle: Boolean = true,
    /** 隐藏底部标签栏的文字，只留图标。默认隐藏，把空间留给对话。 */
    val hideTabLabels: Boolean = true,
)

/** 常规设置存储（DataStore Preferences）。 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val THEME_COLOR = stringPreferencesKey("theme_color")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val AUTO_TITLE = booleanPreferencesKey("auto_title")
        val HIDE_TAB_LABELS = booleanPreferencesKey("hide_tab_labels")
    }

    val settings: Flow<AppSettings> = context.appDataStore.data.map { prefs ->
        AppSettings(
            themeMode = ThemeMode.from(prefs[Keys.THEME_MODE]),
            themeColor = ThemeColor.from(prefs[Keys.THEME_COLOR]),
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: false,
            autoTitle = prefs[Keys.AUTO_TITLE] ?: true,
            hideTabLabels = prefs[Keys.HIDE_TAB_LABELS] ?: true,
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.appDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setThemeColor(color: ThemeColor) {
        context.appDataStore.edit { it[Keys.THEME_COLOR] = color.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.appDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setAutoTitle(enabled: Boolean) {
        context.appDataStore.edit { it[Keys.AUTO_TITLE] = enabled }
    }

    suspend fun setHideTabLabels(enabled: Boolean) {
        context.appDataStore.edit { it[Keys.HIDE_TAB_LABELS] = enabled }
    }
}
