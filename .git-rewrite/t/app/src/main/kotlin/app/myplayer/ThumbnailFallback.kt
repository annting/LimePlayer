package app.myplayer

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.ImageSource
import app.myplayer.core.model.MediaItemDto
import app.myplayer.core.model.MediaSource
import app.myplayer.core.model.PlaybackPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 */
class LocalThumbnailProvider(
    private val context: Context,
    private val source: MediaSource?,
    private val caps: ClientCapabilities,
) {
    private val locks = mutableMapOf<String, Mutex>()

    private fun lockFor(id: String): Mutex = synchronized(locks) { locks.getOrPut(id) { Mutex() } }

    private fun cacheFile(item: MediaItemDto): File =
        File(File(context.cacheDir, "thumbs"), "${item.serverId}_${item.id}.jpg")

    suspend fun image(item: MediaItemDto): ImageSource = withContext(Dispatchers.IO) {
        val cached = cacheFile(item)
        if (cached.exists() && cached.length() > 0) {
            return@withContext ImageSource.Local(cached.toUri().toString())
        }
        // 同一张海报的并发请求串行化，避免重复解码
        lockFor(item.id).withLock {
            if (cached.exists()) return@withLock ImageSource.Local(cached.toUri().toString())
            extract(item, cached)
        }
    }

    private fun extract(item: MediaItemDto, target: File): ImageSource {
        val src = source ?: return ImageSource.None
        val plan = runCatching { kotlinx.coroutines.runBlocking { src.planPlayback(item, caps) } }.getOrNull()
        val direct = plan as? PlaybackPlan.Direct ?: return ImageSource.None
        val bitmap: Bitmap? = runCatching {
            MediaMetadataRetriever().let { r ->
                try {
                    r.setDataSource(direct.uri, direct.headers.ifEmpty { null })
                    // 取第 3 秒的画面：太靠前常是黑场 / 片头
                    r.getFrameAtTime(3_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    runCatching { r.release() }
                }
            }
        }.getOrNull() ?: return ImageSource.None
        val frame = bitmap ?: return ImageSource.None

        return runCatching {
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { out -> frame.compress(Bitmap.CompressFormat.JPEG, 82, out) }
            ImageSource.Local(target.toUri().toString())
        }.getOrElse { ImageSource.None }
    }
}
