package com.mynok.myagent.tools.android

import android.content.Context
import android.content.pm.PackageManager

/** 运行时权限是否已授予。 */
internal fun Context.hasPermission(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/** 统一的「去授权」提示，模型会把它转述给用户。 */
internal fun permissionHint(label: String): String =
    "缺少${label}权限。请让用户打开「模型 → 工具 → 系统权限」，授予${label}权限后重试。"
