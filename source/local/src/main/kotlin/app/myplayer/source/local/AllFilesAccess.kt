package app.myplayer.source.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 「所有文件访问权限」（`MANAGE_EXTERNAL_STORAGE` / All files access）的状态与跳转
 * （`docs/12-本机视频播放方案.md` §3.1）。
 *
 * 这是**特殊权限**：没有运行时权限弹窗，`requestPermissions` 对它无效，
 * 只能把用户送到系统设置页自己开。所以术语上用「去开启」而不是「授权」。
 */

/** 当前是否持有全盘访问权限。Android 10 及以下没有这个权限，视为可用。 */
fun hasAllFilesAccess(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    } else {
        true
    }

/**
 * 打开「所有文件访问权限」设置页。
 *
 * 优先跳本应用的专属页（部分 ROM 不支持，会抛异常）→ 回退到全量列表页。
 */
fun allFilesAccessIntent(context: Context): Intent {
    val perApp = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // 用 PackageManager 探一下能不能处理，不能处理就用兜底（避免 ActivityNotFound）
        if (context.packageManager.resolveActivity(perApp, 0) != null) perApp else fallback
    } else {
        // 10 及以下根本没有这个权限项 → 页面不存在，交给调用方忽略
        fallback
    }
}

/** 全盘权限只在 Android 11+ 才有意义（10 及以下靠 `READ_EXTERNAL_STORAGE`，本项目不申请）。 */
fun allFilesAccessSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
