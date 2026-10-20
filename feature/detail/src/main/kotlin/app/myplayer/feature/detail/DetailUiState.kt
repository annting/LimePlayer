package app.myplayer.feature.detail

import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.SeasonGroup
import app.myplayer.core.model.StreamKind
import app.myplayer.core.model.Ticks
import app.myplayer.core.model.formatDuration
import app.myplayer.core.model.formatFileSize

/**
 * 详情页状态（`03` §6.3）：加载中 / 失败可重试 / 内容。
 * [Content.seasons] 对电影等非剧集条目为空列表（此时不渲染分季区）。
 */
sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Error(val message: String) : DetailUiState
    data class Content(
        val item: MediaItemDto,
        val seasons: List<SeasonGroup> = emptyList(),
    ) : DetailUiState
}

/**
 * 续播行数据（`03` §6.3）：进度条占比 + 剩余时长文案。
 *
 * 只在**能算出来**时返回非 null —— 时长未知、已到末尾都不显示进度条，
 * 所以文案是「继续播放」时不一定有进度条（时长缺失的源就是这种情况）。
 */
data class ResumeState(val fraction: Float, val remaining: String)

/**
 * 主按钮文案（`03` §6.3）：
 * - 服务端**没有**续播位置 → 「播放」（没看过，或看完后服务端把位置清零）；
 * - 服务端**有**续播位置 → 「继续播放」，剩余时长改为按钮下方的续播行显示
 *   （不再拼进文案，2026-10-19 用户要求）。
 */
fun primaryActionLabel(item: MediaItemDto): String =
    if (item.isResuming()) "继续播放" else "播放"

/**
 * 有续播位置（**只看服务端位置，不看 `isPlayed` 标记**）。
 *
 * 2026-10-19 用户反馈后放宽：Emby 判定「看完」时会把 `PlaybackPositionTicks` 清零，
 * 所以真正看完的条目天然没有续播行；反过来只要服务端仍留着位置（如看完后重看了一部分），
 * 就该给出进度与剩余时长 —— 之前多加的 `!isPlayed` 条件会把这种情况误判成「播放」。
 */
fun MediaItemDto.isResuming(): Boolean = userData.resumePositionTicks.value > 0L

/** 续播进度条 + 剩余时长（`03` §6.3）；位置为 0 / 时长未知 / 已到末尾时返回 null。 */
fun MediaItemDto.resumeState(): ResumeState? {
    if (!isResuming()) return null
    val total = durationTicks.value
    val resume = userData.resumePositionTicks.value
    if (total <= 0L || resume >= total) return null
    val remaining = formatDuration(Ticks(total - resume)) ?: return null
    return ResumeState(
        fraction = (resume.toFloat() / total).coerceIn(0f, 1f),
        remaining = remaining,
    )
}

/** 次级按钮文案：已看「取消已播放」/ 未看「标记已播放」（`03` §6.3）。 */
fun markPlayedLabel(item: MediaItemDto): String =
    if (item.userData.isPlayed) "取消已播放" else "标记已播放"

private fun fmtMbps(bitrate: Int): String =
    String.format(java.util.Locale.US, "%.1f Mbps", bitrate / 1_000_000.0)

private fun fmtHeight(width: Int?, height: Int?, fallbackLabel: String?): String? = when {
    width != null && height != null -> "$width × $height"
    // 飞牛只给档位（"480p"），没有精确像素 —— 有档位也展示
    !fallbackLabel.isNullOrBlank() -> fallbackLabel.uppercase()
    else -> null
}

/**
 * 「文件信息」分区（`03` §6.3）：容器 / 文件大小 / 总码率；有值的才进列表，全空时不渲染分区。
 * 体积格式与库页卡片共用 `formatFileSize`（`03` §6.5）。
 */
fun MediaItemDto.fileInfoRows(): List<Pair<String, String>> = buildList {
    container?.takeIf { it.isNotBlank() }?.let { add("容器" to it.uppercase()) }
    sizeBytes?.let(::formatFileSize)?.let { add("文件大小" to it) }
    formatDuration(durationTicks)?.let { add("时长" to it) }        // 2026-10-19 用户需求
    bitrate?.takeIf { it > 0 }?.let { add("总码率" to fmtMbps(it)) }
    dateCreated?.let { add("添加日期" to fmtDate(it)) }            // 2026-10-19 用户需求
    // 完整路径（2026-10-19 用户需求）：排障时要能确认播的是哪个文件、外挂字幕命名是否匹配
    path?.takeIf { it.isNotBlank() }?.let { add("路径" to it) }
}

/** 入库时间（epoch 毫秒）→ 本地时区的 `yyyy-MM-dd`。 */
private fun fmtDate(epochMillis: Long): String =
    java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .toString()

/**
 * 「视频信息」分区（`03` §6.3）：**label / value 两列** —— 编码 / 分辨率 / 帧率 / 位深 / 流码率。
 *
 * 用户要求「每项都要有 label」（2026-10-19）：原先是一行拼接的「MPEG4 · 720 × 404 · 30fps」，
 * 看不出每段是什么；现在逐项成对返回。多流时 label 追加「· 流 N」。
 *
 * 无 `MediaStreams` 时兜底到条目级字段（Emby 4.10 老版本与飞牛只有条目级数据；`05` §2.2 实测口径）。
 */
fun MediaItemDto.videoInfoPairs(): List<Pair<String, String>> {
    val streams = mediaStreams.filter { it.kind == StreamKind.VIDEO }
    if (streams.isNotEmpty()) {
        return buildList {
            streams.forEachIndexed { index, st ->
                val suffix = if (streams.size > 1) " · 流 ${index + 1}" else ""
                st.codec?.takeIf { it.isNotBlank() }?.let { add("编码$suffix" to it.uppercase()) }
                fmtHeight(st.width, st.height, null)?.let { add("分辨率$suffix" to it) }
                st.frameRate?.takeIf { it > 0f }?.let {
                    add("帧率$suffix" to String.format(java.util.Locale.US, "%.0ffps", it))
                }
                st.bitDepth?.let { add("位深$suffix" to "${it}bit") }
                st.bitrate?.takeIf { it > 0 }?.let { add("码率$suffix" to fmtMbps(it)) }
            }
        }
    }
    // 兜底：条目级编码 + 分辨率（优先精确像素，再档位）
    return buildList {
        videoCodec?.takeIf { it.isNotBlank() }?.let { add("编码" to it.uppercase()) }
        fmtHeight(width, height, resolutionLabel)?.let { add("分辨率" to it) }
    }
}

/**
 * 「音频信息」分区（`03` §6.3）：**label / value 两列** —— 编码 / 声道 / 采样率 / 语言 / 默认。
 * 无 `MediaStreams` 时兜底到条目级 `audioCodecs`。
 */
fun MediaItemDto.audioInfoPairs(): List<Pair<String, String>> {
    val streams = mediaStreams.filter { it.kind == StreamKind.AUDIO }
    if (streams.isNotEmpty()) {
        return buildList {
            streams.forEachIndexed { index, st ->
                val suffix = if (streams.size > 1) " · 流 ${index + 1}" else ""
                st.codec?.takeIf { it.isNotBlank() }?.let { add("编码$suffix" to it.uppercase()) }
                st.channels?.let { add("声道$suffix" to "$it 声道") }
                st.sampleRate?.let { add("采样率$suffix" to "${it / 1000}kHz") }
                st.language?.takeIf { it.isNotBlank() }?.let { add("语言$suffix" to it) }
                if (st.isDefault) add("默认$suffix" to "是")
            }
        }
    }
    if (audioCodecs.isEmpty()) return emptyList()
    return audioCodecs.mapIndexed { index, codec ->
        val label = if (audioCodecs.size > 1) "编码 · 流 ${index + 1}" else "编码"
        label to codec.uppercase()
    }
}

/**
 * 「字幕信息」分区（`03` §6.3）：**label / value 两列** —— 语言 / 格式 / 来源（外挂·内嵌）/ 默认。
 * 无外挂字幕流时不渲染分区。
 */
fun MediaItemDto.subtitleInfoPairs(): List<Pair<String, String>> {
    val streams = mediaStreams.filter { it.kind == StreamKind.SUBTITLE }
    return buildList {
        streams.forEachIndexed { index, st ->
            val suffix = if (streams.size > 1) " · 流 ${index + 1}" else ""
            st.language?.takeIf { it.isNotBlank() }?.let { add("语言$suffix" to it) }
            st.codec?.takeIf { it.isNotBlank() }?.let { add("格式$suffix" to it.uppercase()) }
            add("来源$suffix" to if (st.isExternal) "外挂" else "内嵌")
            if (st.isDefault) add("默认$suffix" to "是")
        }
    }
}
