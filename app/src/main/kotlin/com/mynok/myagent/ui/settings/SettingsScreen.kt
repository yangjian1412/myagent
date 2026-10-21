package com.mynok.myagent.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mynok.myagent.BuildConfig
import com.mynok.myagent.data.repo.ThemeColor
import com.mynok.myagent.data.repo.ThemeMode

/** 当前版本号。 */
const val APP_VERSION = "0.1.0beta3"

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onThemeMode: (ThemeMode) -> Unit,
    onThemeColor: (ThemeColor) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onAutoTitle: (Boolean) -> Unit,
    onHideTabLabels: (Boolean) -> Unit,
    onAgentName: (String) -> Unit,
    onAgentPreferences: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = state.settings

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "设置",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = 8.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionHeader("Agent") }
            item {
                SettingsCard {
                    SettingLabel("Agent 名字", "写进系统提示，让它知道自己叫什么")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = settings.agentName,
                        onValueChange = onAgentName,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("MyAgent") },
                    )
                    Spacer(Modifier.height(16.dp))
                    SettingLabel("偏好", "长期生效的回答习惯，会附加到每次请求的系统提示")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = settings.agentPreferences,
                        onValueChange = onAgentPreferences,
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 8,
                        placeholder = { Text("例如：默认用中文回答；先给结论再解释；不要主动调用工具") },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "名字留空时仍按 MyAgent 处理；修改后对下一次发送生效。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { SectionHeader("外观") }
            item {
                SettingsCard {
                    SettingLabel("主题模式", "白天 / 夜间 / 跟随系统")
                    Spacer(Modifier.height(10.dp))
                    ThemeModeSelector(current = settings.themeMode, onSelect = onThemeMode)

                    Spacer(Modifier.height(18.dp))
                    SettingLabel(
                        "主题颜色",
                        if (settings.dynamicColor) "已开启跟随壁纸，此项暂不生效" else "选一个主色调",
                    )
                    Spacer(Modifier.height(10.dp))
                    ThemeColorRow(
                        current = settings.themeColor,
                        enabled = !settings.dynamicColor,
                        onSelect = onThemeColor,
                    )

                    Spacer(Modifier.height(16.dp))
                    SwitchRow(
                        title = "跟随壁纸取色",
                        subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            "Android 12+ 使用系统的动态配色"
                        } else {
                            "需要 Android 12 及以上"
                        },
                        checked = settings.dynamicColor,
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                        onCheckedChange = onDynamicColor,
                    )
                    Spacer(Modifier.height(16.dp))
                    SwitchRow(
                        title = "隐藏标签栏文字",
                        subtitle = "底部标签栏只显示图标，更省空间",
                        checked = settings.hideTabLabels,
                        onCheckedChange = onHideTabLabels,
                    )
                }
            }

            item { SectionHeader("通用") }
            item {
                SettingsCard {
                    SwitchRow(
                        title = "自动命名会话",
                        subtitle = "用第一条消息生成标题",
                        checked = settings.autoTitle,
                        onCheckedChange = onAutoTitle,
                    )
                }
            }

            item { SectionHeader("关于") }
            item {
                SettingsCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "MyAgent",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            APP_VERSION,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "手机端 AI Agent harness：本地模型与在线模型自由切换，" +
                            "支持工具调用、MCP 接入、会话分支与多模态识图。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    KeyValue("构建号", "${BuildConfig.VERSION_CODE}")
                    KeyValue("包名", BuildConfig.APPLICATION_ID)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp, start = 2.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun SettingLabel(title: String, subtitle: String? = null) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            key,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(width = 64.dp, height = 18.dp),
        )
        Text(value, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ThemeModeSelector(
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val options = ThemeMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = current == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(mode.label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ThemeColorRow(
    current: ThemeColor,
    enabled: Boolean,
    onSelect: (ThemeColor) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ThemeColor.entries.forEach { color ->
            val selected = color == current
            val swatch = Color(color.seed)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(swatch, CircleShape)
                    .border(
                        width = if (selected) 3.dp else 0.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        },
                        shape = CircleShape,
                    )
                    .clickable(enabled = enabled) { onSelect(color) },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = color.label,
                        tint = Color.White,
                    )
                }
            }
        }
    }
}
