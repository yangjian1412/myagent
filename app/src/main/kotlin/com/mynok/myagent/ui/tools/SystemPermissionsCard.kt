package com.mynok.myagent.ui.tools

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private data class PermissionGroup(
    val label: String,
    val permissions: List<String>,
)

private val permissionGroups = listOf(
    PermissionGroup(
        "日历",
        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
    ),
    PermissionGroup("联系人", listOf(Manifest.permission.READ_CONTACTS)),
    PermissionGroup(
        "定位",
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ),
    ),
    PermissionGroup("电话", listOf(Manifest.permission.CALL_PHONE)),
    PermissionGroup("麦克风", listOf(Manifest.permission.RECORD_AUDIO)),
)

/**
 * 系统权限集中授权卡片。
 *
 * 权限申请放在 Compose 里，工具层只负责检查并返回「去这里授权」的提示。
 */
@Composable
internal fun SystemPermissionsCard() {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { revision++ }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val grantedCounts = remember(revision, context) {
        permissionGroups.associateWith { group -> group.grantedCount(context) }
    }
    val missing = permissionGroups.flatMap { group ->
        group.permissions.filterNot { context.isGranted(it) }
    }.distinct()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "日历、联系人、定位、电话、麦克风按需授权。未授权时工具会返回提示；" +
                    "日历新建会自动降级成系统日历的预填页面。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))

            permissionGroups.forEach { group ->
                val granted = grantedCounts[group] ?: 0
                val status = when {
                    granted >= group.permissions.size -> "已授权"
                    granted > 0 -> "部分授权"
                    else -> "未授权"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        group.label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (granted > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { launcher.launch(missing.toTypedArray()) },
                    enabled = missing.isNotEmpty(),
                ) {
                    Text(if (missing.isEmpty()) "已全部授权" else "授予缺失权限")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null),
                            ),
                        )
                    },
                ) {
                    Text("系统设置")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "日历、联系人和位置信息会作为工具结果发送给当前模型，请确认你接受这一点后再授权。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun PermissionGroup.grantedCount(context: android.content.Context): Int {
    // 定位：拿到精确位置就视为完整授权，不需要再要求 coarse
    if (label == "定位" && context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
        return permissions.size
    }
    return permissions.count { context.isGranted(it) }
}

private fun android.content.Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
