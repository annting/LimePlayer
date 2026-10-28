package app.myplayer.source.smb

import android.media.MediaExtractor
import android.media.MediaFormat
import app.myplayer.core.model.MediaProbeInfo
import app.myplayer.core.model.MediaStreamInfo
import app.myplayer.core.model.StreamKind
import app.myplayer.core.model.Ticks

/**
 * 现场解析一个媒体文件的轨道信息（SMB 没有元数据服务，只能读文件头）。
 *
 * ## 为什么走 HTTP 桥
 * `MediaExtractor` 只认 http / file / fd，不认 `smb://`。这里传给它的就是
 * [SmbStreamServer] 的本机地址（支持 Range，MP4 找 moov 时要跳到文件尾也能满足）。
 *
 * ## 能力边界（真机实测）
 * - 常规容器（mp4 / mkv / webm / ts / mov）能拿到编码、分辨率、帧率、流码率、
 *   声道、采样率、语言、内嵌字幕；
 * - 拿不到的字段**直接不出现在 UI 里**（详情页按「有值才渲染」实现），不会显示 0 或占位符；
 * - AVI 这类 `MediaExtractor` 不支持的容器整条链路失败 → 返回 null，详情页回到
 *   「服务器未下发技术信息」的弱化说明（与探测前完全一致，不会崩、不会卡在转圈）。
 */
internal fun probeMediaInfo(url: String): MediaProbeInfo? {
    val extractor = MediaExtractor()
    return try {
        // 空 Map 而不是 null：平台实现内部直接 `headers.size()`，传 null 会 NPE（缩略图抽帧踩过同一个坑）
        extractor.setDataSource(url, emptyMap())

        val streams = (0 until extractor.trackCount).mapNotNull { i ->
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)?.lowercase() ?: return@mapNotNull null
            val kind = kindOf(mime) ?: return@mapNotNull null
            format.toStreamInfo(kind, mime)
        }
        val durationUs = (0 until extractor.trackCount)
            .map { extractor.getTrackFormat(it).longOrZero(MediaFormat.KEY_DURATION) }
            .maxOrNull() ?: 0L

        if (streams.isEmpty()) {
            null
        } else {
            // 微秒 → 100ns tick（`Ticks` 全仓统一口径）
            MediaProbeInfo(streams = streams, durationTicks = Ticks(durationUs * 10L))
        }
    } catch (t: Throwable) {
        android.util.Log.w("LimeSMB", "探测轨道失败 $url: ${t.javaClass.simpleName}: ${t.message}")
        null
    } finally {
        runCatching { extractor.release() }
    }
}

/** MIME → 流类型；不是音视频/字幕轨（例如时间码轨）返回 null，直接跳过。 */internal fun kindOf(mime: String): StreamKind? = when {
    mime.startsWith("video/") -> StreamKind.VIDEO
    mime.startsWith("audio/") -> StreamKind.AUDIO
    mime.startsWith("text/") ||
        mime == "application/x-subrip" ||
        mime == "application/x-ass" ||
        mime == "application/x-ssa" ||
        mime.startsWith("application/ttml") -> StreamKind.SUBTITLE
    else -> null
}

/**
 * MIME → 展示用编码名（尽量与 Emby 的写法对齐，UI 会统一转大写）。
 *
 * 认不出来的直接取 `/` 后面那段（例如 `video/x-msvideo` → `x-msvideo`），
 * 比显示 "UNKNOWN" 有用。
 */
internal fun codecNameOf(mime: String): String = when (mime) {
    "video/avc" -> "h264"
    "video/hevc" -> "hevc"
    "video/x-vnd.on2.vp8" -> "vp8"
    "video/x-vnd.on2.vp9" -> "vp9"
    "video/av01" -> "av1"
    "video/mp4v-es" -> "mpeg4"
    "video/mpeg2" -> "mpeg2"
    "video/3gpp" -> "h263"
    "video/dolby-vision" -> "dovi"
    "audio/mp4a-latm" -> "aac"
    "audio/mpeg" -> "mp3"
    "audio/ac3" -> "ac3"
    "audio/eac3" -> "eac3"
    "audio/eac3-joc" -> "eac3"
    "audio/opus" -> "opus"
    "audio/vorbis" -> "vorbis"
    "audio/flac" -> "flac"
    "audio/raw" -> "pcm"
    "audio/true-hd" -> "truehd"
    "audio/vnd.dts" -> "dts"
    "audio/vnd.dts.hd" -> "dts-hd"
    "text/x-ssa" -> "ass"
    "text/x-ass" -> "ass"
    "application/x-subrip" -> "srt"
    "text/vtt" -> "vtt"
    else -> mime.substringAfter('/', mime)
}

/**
 * 语言标签是否值得显示。
 *
 * 真机踩到：手机拍的 MP4 把 `KEY_LANGUAGE` 写成 ``` ``` ```（三个反引号），
 * 直接摊到详情页就是一行「语言 ```」。这里只放行「像语言标签」的值：
 * 纯字母 / 连字符（`chi`、`eng`、`zh-CN`、`English`），`und` 视为未标注直接丢弃。
 */
internal fun isPlausibleLanguage(tag: String?): Boolean {
    if (tag.isNullOrBlank()) return false
    if (tag.equals("und", ignoreCase = true)) return false
    return tag.length in 2..20 && tag.all { it.isLetter() || it == '-' || it == '_' }
}

private fun MediaFormat.toStreamInfo(kind: StreamKind, mime: String): MediaStreamInfo {
    val language = getString(MediaFormat.KEY_LANGUAGE)?.takeIf(::isPlausibleLanguage)
    // `MediaFormat.KEY_IS_DEFAULT` 是 API 28 才有的常量，用字面量避免为这一个字段做版本分支
    val isDefault = longOrZero("is-default") == 1L

    return when (kind) {
        StreamKind.VIDEO -> {
            val width = intOrNull(MediaFormat.KEY_WIDTH)
            val height = intOrNull(MediaFormat.KEY_HEIGHT)
            // 竖拍视频的 coded size 是横的，靠 rotation 换回来才是「看到的分辨率」
            val rotated = longOrZero(MediaFormat.KEY_ROTATION).let { it == 90L || it == 270L }
            MediaStreamInfo(
                kind = kind,
                codec = codecNameOf(mime),
                width = if (rotated) height else width,
                height = if (rotated) width else height,
                frameRate = floatOrNull(MediaFormat.KEY_FRAME_RATE),
                bitrate = intOrNull(MediaFormat.KEY_BIT_RATE)?.takeIf { it > 0 },
                isDefault = isDefault,
            )
        }
        StreamKind.AUDIO -> MediaStreamInfo(
            kind = kind,
            codec = codecNameOf(mime),
            language = language,
            channels = intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
            sampleRate = intOrNull(MediaFormat.KEY_SAMPLE_RATE),
            bitrate = intOrNull(MediaFormat.KEY_BIT_RATE)?.takeIf { it > 0 },
            isDefault = isDefault,
        )
        StreamKind.SUBTITLE -> MediaStreamInfo(
            kind = kind,
            codec = codecNameOf(mime),
            language = language,
            isDefault = isDefault,
        )
    }
}

// MediaFormat.getXxx 在 key 不存在时抛异常；有的流把 int 存成 long、把帧率存成 int，
// 所以每个取值都「先看有没有、再兼容两种类型」。
private fun MediaFormat.intOrNull(key: String): Int? {
    if (!containsKey(key)) return null
    return runCatching { getInteger(key) }.getOrNull()
        ?: runCatching { getLong(key).toInt() }.getOrNull()
}

private fun MediaFormat.longOrZero(key: String): Long {
    if (!containsKey(key)) return 0L
    return runCatching { getLong(key) }.getOrNull()
        ?: runCatching { getInteger(key).toLong() }.getOrNull()
        ?: 0L
}

private fun MediaFormat.floatOrNull(key: String): Float? {
    if (!containsKey(key)) return null
    return runCatching { getFloat(key) }.getOrNull()
        ?: runCatching { getInteger(key).toFloat() }.getOrNull()
}
