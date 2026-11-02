package app.myplayer.source.local

import app.myplayer.core.model.displayNameOfAlias

/**
 * 本机视频源的纯函数helper（可 JVM 单测）：
 * 扩展名白名单、路径 ↔ id、库 id 编码、多根别名 → 库名。
 *
 * 与 `:source:smb` 的口径保持一致（14 种视频扩展名、URL 安全 Base64 作为条目 id）。
 */

/** 认作「视频」的扩展名（与 SMB / Emby 容器口径对齐；其余文件直接忽略）。 */
internal val VideoExtensions = setOf(
    "mkv", "mp4", "m4v", "avi", "mov", "wmv", "flv", "webm", "ts", "m2ts", "mpg", "mpeg", "iso", "rmvb",
)

internal fun String.extensionLower(): String = substringAfterLast('.', "").lowercase()

internal fun String.isVideoFile(): Boolean = extensionLower() in VideoExtensions

/** 相对路径 → 路由安全 id（真实路径含 `/`、空格、中文，不能直接进 route）。 */
internal fun encodeLocalId(path: String): String =
    java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(path.toByteArray(Charsets.UTF_8))

internal fun decodeLocalId(id: String): String =
    runCatching { String(java.util.Base64.getUrlDecoder().decode(id), Charsets.UTF_8) }
        .getOrDefault(id)

/** 库 id ↔ 相对文件夹：`local:Movies` ↔ `Movies`；`local:` ↔ 根目录。 */
internal fun localLibraryId(folder: String): String = "local:$folder"

internal fun localFolderOf(libraryId: String?): String? =
    libraryId?.removePrefix("local:")?.takeIf { it != libraryId }

/**
 * 拼接相对路径（父为空 = 根）。
 *
 * ⚠️ `name` 为空时必须原样返回 `parent`：多根下「根目录自身」也当库
 * （`joinRelative("Movies", "")`），老实现会拼出 `Movies/` 这个**带尾斜杠**的路径，
 * 再和自己下面的文件拼一次就变成 `Movies//a.mp4` —— `locate()` 解析不到，表现为
 * 「列表有条目但预览图抽不出、播放报文件不存在」（2026-11-01 真机踩到）。
 */
internal fun joinRelative(parent: String, name: String): String = when {
    parent.isBlank() -> name
    name.isBlank() -> parent
    else -> "$parent/$name"
}

/**
 * 某根**自身**（根目录直接放视频）作为库时的展示名。
 *
 * 空前缀 = 内部存储根，沿用历史口径「本机根目录」；否则用别名末段（`Movies` / `video`）。
 */
internal fun rootLibraryNameOf(alias: String): String =
    if (alias.isBlank()) "本机根目录" else displayNameOfAlias(alias)

/** 文件名 → 展示标题（去掉扩展名）。 */
internal fun titleOf(name: String): String = name.substringBeforeLast('.')

/** 扩展名 → MIME（ExoPlayer 主要靠容器嗅探，这里只做兜底）。 */
internal fun localMimeTypeOf(path: String): String = when (path.extensionLower()) {
    "mp4", "m4v" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"
    "mov" -> "video/quicktime"
    "ts", "m2ts" -> "video/mp2t"
    "mpg", "mpeg" -> "video/mpeg"
    "wmv" -> "video/x-ms-wmv"
    "flv" -> "video/x-flv"
    else -> "application/octet-stream"
}
