package app.myplayer.source.httpapi

/**
 * `tiles.m3u8` 解析（`05` §6.2）。
 *
 * Emby / Jellyfin 的 trickplay 清单形如：
 * ```
 * #EXTM3U
 * #EXT-X-VERSION:3
 * #EXT-X-TARGETDURATION:10
 * #EXT-X-TILES:RESOLUTION=320x180,LAYOUT=5x4,INTERVAL=10.000
 * #EXTINF:10.000,
 * 0.jpg
 * #EXTINF:10.000,
 * 1.jpg
 * ```
 * 需要拿到：切片分辨率、网格、时间间隔、分片数量（供 `03` §5.4 的进度条预览浮层换算）。
 */
data class ParsedTrickplay(
    val tileWidth: Int,
    val tileHeight: Int,
    /** 每片横向格数。 */
    val columns: Int,
    /** 每片纵向格数。 */
    val rows: Int,
    val intervalMs: Long,
    /** 分片（图片）数量 —— 播放列表里的媒体条目数。 */
    val segmentCount: Int,
)

object TrickplayPlaylist {

    /** 解析失败返回 null（调用方据此降级为无预览）。 */
    fun parse(text: String): ParsedTrickplay? {
        val tilesLine = text.lineSequence()
            .firstOrNull { it.startsWith("#EXT-X-TILES:") }
            ?: return null
        val attrs = tilesLine.removePrefix("#EXT-X-TILES:")
            .split(',')
            .mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
            }
            .toMap()

        val resolution = attrs["RESOLUTION"] ?: return null
        val (tileW, tileH) = parseSize(resolution) ?: return null
        val (cols, rows) = parseSize(attrs["LAYOUT"] ?: return null) ?: return null
        val intervalMs = attrs["INTERVAL"]?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: return null
        if (tileW <= 0 || tileH <= 0 || cols <= 0 || rows <= 0 || intervalMs <= 0) return null

        val segmentCount = text.lineSequence().count { it.isNotBlank() && !it.startsWith("#") }
        if (segmentCount <= 0) return null

        return ParsedTrickplay(
            tileWidth = tileW,
            tileHeight = tileH,
            columns = cols,
            rows = rows,
            intervalMs = intervalMs,
            segmentCount = segmentCount,
        )
    }

    private fun parseSize(value: String): Pair<Int, Int>? {
        val parts = value.lowercase().split('x')
        if (parts.size != 2) return null
        val w = parts[0].trim().toIntOrNull() ?: return null
        val h = parts[1].trim().toIntOrNull() ?: return null
        return w to h
    }
}
