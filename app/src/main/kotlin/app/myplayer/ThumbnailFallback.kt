package app.myplayer

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.core.net.toUri
import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaSource
import app.myplayer.core.model.PlaybackPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 本地缩略图降级（`03` §6.1 / 任务 3-7）。
 *
 * 服务端没有海报（`hasPoster=false`）时，用 `MediaMetadataRetriever` 从**直放地址**抓一帧，
 * 落盘缓存后按 [ImageSource.Local] 交给 Coil。转码流（HLS）不抽帧 —— 片段时间轴不连续，抽帧代价高且易失败。
 *
 * 开关：`PlayerSettings.localThumbnails`（默认开）；关闭时调用方根本不构造本对象。
 *
 * ## SMB 为什么也能用
 * `MediaMetadataRetriever` 读不了 `smb://`，但 `:source:smb` 的 `planPlayback` 给的是
 * `http://127.0.0.1:<port>/<token>` 本机桥（支持 Range），对它来说就是一个普通 HTTP 视频源。
 * 所以 SMB 的「文件夹预览图」走的完全是这里，**不需要源模块引入任何 Android 媒体依赖**。
 *
 * ## 三条硬约束（都是在真机上踩出来的）
 * 1. **只抽一次**：成功写 jpg；失败写一个 0 字节文件当「负缓存」，
 *    否则每次滚动回来都会把同一个坏文件（编码不支持 / 文件残缺）重试一遍。
 * 2. **并发限 2**：一屏会同时可见 3~6 个格子，全放出去等于同时开 6 路 SMB 读；
 *    机械盘/弱 Wi-Fi 上会把首帧拖到十几秒。`LazyRow` / `LazyVerticalGrid` 本身只组合可见项，
 *    再加上这道闸门，滚动时是一条一条来。
 * 3. **失败也要留痕**：见第 1 条。注意**不要**按扩展名预先挡掉 `.avi` ——
 *    OnePlus 这类机型的 `MediaExtractor` 带厂商扩展，AVI 既能播也能抽帧（2026-10-25 真机实测）；
 *    在别的机型上失败也只是多一次毫秒级探测，负缓存会兜住。
 */
class LocalThumbnailProvider(
    private val context: Context,
    private val source: MediaSource?,
    private val caps: ClientCapabilities,
) {
    private val locks = mutableMapOf<String, Mutex>()

    /** 全局并发闸门：同一时刻最多两路抽帧（见类注释第 2 条）。 */
    private val gate = Semaphore(permits = MAX_PARALLEL_EXTRACTIONS)

    private fun lockFor(id: String): Mutex = synchronized(locks) { locks.getOrPut(id) { Mutex() } }

    private fun cacheFile(item: MediaItemDto): File =
        File(File(context.cacheDir, "thumbs"), "${item.serverId}_${item.id}.jpg")

    /**
     * 取缩略图：命中缓存直接返回；否则抽帧（同一 item 的并发请求串行化）。
     *
     * 返回 [ImageSource.None] 表示「这个文件抽不出图」，调用方按无图渲染即可。
     */
    suspend fun image(item: MediaItemDto): ImageSource = withContext(Dispatchers.IO) {
        val cached = cacheFile(item)
        // 负缓存：0 字节 = 之前试过、抽不出来
        if (cached.exists()) return@withContext readCache(cached)

        lockFor(item.id).withLock {
            if (cached.exists()) return@withLock readCache(cached)
            gate.withPermit { extract(item, cached) }
        }
    }

    private fun readCache(file: File): ImageSource =
        if (file.length() > 0L) ImageSource.Local(file.toUri().toString()) else ImageSource.None

    private suspend fun extract(item: MediaItemDto, target: File): ImageSource {
        val src = source ?: return ImageSource.None
        // 飞牛影视只有 HLS 出口，且协商（play/play）会在服务端真起转码任务 —— 直接放弃抽帧，
        // 用服务端海报 + 占位兜底（实测：列表逐条协商会把 NAS 转码进程堆满）。
        if (src.type == app.myplayer.core.model.SourceType.FNOS) return ImageSource.None
        val plan = runCatching { src.planPlayback(item, caps) }
            .getOrElse { e ->
                android.util.Log.w("LimeThumb", "协商播放地址失败 ${item.title}: ${e.message}")
                null
            }
        val direct = plan as? PlaybackPlan.Direct ?: run {
            android.util.Log.w("LimeThumb", "不是直放地址，跳过抽帧 ${item.title}: ${plan?.javaClass?.simpleName}")
            return ImageSource.None
        }
        // HLS（.m3u8）不抽帧：片段时间轴不连续，**且有的源（飞牛影视）协商 HLS 会在服务端真起一个
        // 转码会话** —— 列表里每张缺图都协商一次会把 NAS 的转码进程堆满（2026-10-18 真机实测拖死服务端）。
        if (direct.uri.contains(".m3u8", ignoreCase = true)) return ImageSource.None

        val frame = runCatching {
            MediaMetadataRetriever().let { r ->
                try {
                    // ⚠️ headers 必须给空 Map 而不是 null：`setDataSource(String, Map)` 内部直接
                    // `headers.size()`，传 null 会抛 NPE（2026-10-25 真机实测，SMB 没有请求头所以必踩）。
                    r.setDataSource(direct.uri, direct.headers)
                    // 取哪一帧：固定 3 秒在长片上经常刚好是片头标题卡（黑底白字，抽出来像没图）。
                    // 有时长就按比例取，短片取 1/3 处、长片取 10% 且最多 45 秒。
                    val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    val atMs = when {
                        durationMs <= 0L -> 3_000L
                        durationMs < 10_000L -> durationMs / 3
                        else -> (durationMs / 10).coerceIn(5_000L, 45_000L)
                    }
                    grabFrame(r, atMs * 1_000L)
                } finally {
                    runCatching { r.release() }
                }
            }
        }.getOrElse { e ->
            android.util.Log.w("LimeThumb", "抽帧失败 ${item.title}: ${direct.uri} -> ${e.javaClass.simpleName}: ${e.message}")
            null
        }

        if (frame == null) {
            android.util.Log.w("LimeThumb", "抽帧为空（容器=${item.container}）${item.title}: ${direct.uri}")
            writeMarker(target)
            return ImageSource.None
        }
        return writeJpeg(frame, target) ?: ImageSource.None
    }

    /**
     * 抽帧并缩到长边 [THUMB_MAX_EDGE_PX]。
     *
     * `getFrameAtTime` 返回的是**原始分辨率**帧（4K 一张 ≈33MB），并发 2 路就够把低端机顶到 OOM。
     * API 27+ 用 `getScaledFrameAtTime` 在解码阶段直接缩放；API 26 退回整帧 + `createScaledBitmap`。
     */
    private fun grabFrame(r: MediaMetadataRetriever, atUs: Long): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (w > 0 && h > 0) {
                val scale = minOf(1f, THUMB_MAX_EDGE_PX.toFloat() / maxOf(w, h))
                val dstW = (w * scale).toInt().coerceAtLeast(1)
                val dstH = (h * scale).toInt().coerceAtLeast(1)
                return r.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dstW, dstH)
                    ?: r.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, dstW, dstH)
            }
        }
        // API 26 / 拿不到视频尺寸：先取整帧再缩，仍会短暂持有大 Bitmap，但至少不落盘
        val raw = r.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: return null
        val scale = minOf(1f, THUMB_MAX_EDGE_PX.toFloat() / maxOf(raw.width, raw.height))
        if (scale >= 1f) return raw
        val scaled = Bitmap.createScaledBitmap(
            raw,
            (raw.width * scale).toInt().coerceAtLeast(1),
            (raw.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== raw) raw.recycle()
        return scaled
    }

    /** 先写 `.part` 再改名：进程被杀时不会留下半张图被当成有效缓存。 */
    private fun writeJpeg(bitmap: Bitmap, target: File): ImageSource? = runCatching {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.part")
        FileOutputStream(tmp).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out) }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return null
        }
        ImageSource.Local(target.toUri().toString())
    }.getOrNull()

    /** 负缓存：0 字节文件，表示「试过了，抽不出来」。 */
    private fun writeMarker(target: File) {
        runCatching {
            target.parentFile?.mkdirs()
            target.createNewFile()
        }
    }

    private companion object {
        /** 同时抽帧上限（见类注释第 2 条）。 */
        const val MAX_PARALLEL_EXTRACTIONS = 2

        /** 缩略图长边上限：640px 对 3x 屏格子足够，又把 4K 帧的内存压到约 1/36。 */
        const val THUMB_MAX_EDGE_PX = 640
    }
}
