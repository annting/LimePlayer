package app.myplayer.core.database

import androidx.room.withTransaction
import app.myplayer.core.model.HidePrivate
import app.myplayer.core.model.PlaybackProgress
import app.myplayer.core.model.PlaybackStore
import app.myplayer.core.model.PrivacyVisibilityPolicy
import app.myplayer.core.model.SessionEndReason
import app.myplayer.core.model.WatchRecord
import java.util.UUID

/**
 * [PlaybackStore] 的 Room 实现（唯一实现）。
 *
 * 写入策略：**先 UPDATE、0 行再 INSERT** —— 因为
 * ① `@Upsert` 整行覆盖会把 `playCount` 清零；
 * ② `ON CONFLICT DO UPDATE` 要 SQLite 3.24+，而 minSdk 26 的 SQLite 是 3.18。
 *
 * **隐私**：两个**跨条目**的读（[continueWatching] / [recentWatched]）按 [privacy] 过滤
 * 档二私有文档 —— 这两张表只有 `mediaKey`，所以过滤是 SQL 层关联 `file_media_items`
 * 完成的（`docs/隐私模式方案.md` §三 P0#2）。按精确 mediaKey 的读（`progressOf`）与写
 * 不参与过滤：调用方能拿到那个 key，说明它已经通过了上游的可见性检查。
 *
 * [now] 可注入，单测里固定时间做「排序 / 跨日」断言。
 */
class RoomPlaybackStore(
    private val db: MediaDatabase,
    private val privacy: PrivacyVisibilityPolicy = HidePrivate,
    private val now: () -> Long = System::currentTimeMillis,
) : PlaybackStore {

    override suspend fun recordPosition(
        sourceId: String,
        mediaKey: String,
        positionMs: Long,
        durationMs: Long,
        completed: Boolean,
    ) {
        val dao = db.playbackProgressDao()
        val t = now()
        // 不变量：completed ⇒ position = 0（看完清零，否则「继续观看」永远挂着它）
        val pos = if (completed) 0L else positionMs.coerceAtLeast(0L)
        val prev = dao.get(sourceId, mediaKey)
        if (prev == null) {
            dao.insertIgnore(
                PlaybackProgressEntity(
                    sourceId = sourceId,
                    mediaKey = mediaKey,
                    positionMs = pos,
                    durationMs = durationMs,
                    completed = completed,
                    playCount = 0,
                    lastPlayedAt = t,
                    updatedAt = t,
                    lastSessionId = null,
                )
            )
            // 并发兜底：另一个协程刚插入了同一行时，insert 被 IGNORE 掉，这里补一次 UPDATE
            dao.update(sourceId, mediaKey, pos, durationMs, completed, t)
        } else {
            dao.update(sourceId, mediaKey, pos, durationMs, completed, t)
        }
        // 只在「从未看完 → 看完」这一跃迁上计数，避免退页补写把 playCount 打高
        if (completed && prev?.completed != true) {
            dao.bumpPlayCount(sourceId, mediaKey, t)
        }
    }

    override suspend fun progressOf(sourceId: String, mediaKey: String): PlaybackProgress? =
        db.playbackProgressDao().get(sourceId, mediaKey)?.toModel()

    override suspend fun progressOf(
        sourceId: String,
        mediaKeys: Collection<String>,
    ): Map<String, PlaybackProgress> {
        if (mediaKeys.isEmpty()) return emptyMap()
        return db.playbackProgressDao()
            .getMany(sourceId, mediaKeys.toList())
            .associate { it.mediaKey to it.toModel() }
    }

    override suspend fun continueWatching(sourceId: String, limit: Int): List<PlaybackProgress> =
        db.playbackProgressDao().continueWatching(sourceId, limit, privacy.includePrivate())
            .map { it.toModel() }

    override suspend fun clear(sourceId: String, mediaKey: String) {
        db.playbackProgressDao().delete(sourceId, mediaKey)
    }

    // ---- 观看历史 ----

    override suspend fun beginSession(
        sourceId: String,
        mediaKey: String,
        title: String,
        startPositionMs: Long,
        durationMs: Long,
        playMethod: String?,
    ): String {
        val id = UUID.randomUUID().toString()
        db.watchHistoryDao().insert(
            WatchHistoryEntity(
                sessionId = id,
                sourceId = sourceId,
                mediaKey = mediaKey,
                titleSnapshot = title,
                // D7：本期只处理视频
                kind = "VIDEO",
                startedAt = now(),
                endedAt = null,
                startPositionMs = startPositionMs,
                endPositionMs = null,
                durationMs = durationMs,
                completed = false,
                playMethod = playMethod,
                endedReason = null,
            )
        )
        return id
    }

    override suspend fun endSession(
        sessionId: String?,
        sourceId: String,
        mediaKey: String,
        endPositionMs: Long,
        durationMs: Long,
        completed: Boolean,
        reason: SessionEndReason,
    ) {
        // 一个事务里「闭合历史 + 更新断点」：否则会出现「历史闭合了但断点还是旧的」（§7.3）
        db.withTransaction {
            if (sessionId != null) {
                db.watchHistoryDao().close(
                    sessionId = sessionId,
                    endedAt = now(),
                    endPositionMs = endPositionMs,
                    durationMs = durationMs,
                    completed = completed,
                    endedReason = reason.name,
                )
            }
            recordPosition(sourceId, mediaKey, endPositionMs, durationMs, completed)
        }
    }

    override suspend fun recentWatched(sourceId: String, limit: Int): List<WatchRecord> =
        db.watchHistoryDao().recentByMedia(sourceId, limit, privacy.includePrivate())
            .map { it.toModel() }

    override suspend fun repairOpenSessions(nowMs: Long, withinMs: Long): Int = db.withTransaction {
        val cutoff = nowMs - withinMs
        var fixed = 0
        db.watchHistoryDao().openSessions(cutoff).forEach { row ->
            // 用该条目最新断点补：断点的 lastPlayedAt 比 now 更接近真实退出时刻
            val p = db.playbackProgressDao().get(row.sourceId, row.mediaKey)
            fixed += db.watchHistoryDao().close(
                sessionId = row.sessionId,
                endedAt = p?.lastPlayedAt ?: nowMs,
                endPositionMs = p?.positionMs ?: row.startPositionMs,
                durationMs = if ((p?.durationMs ?: 0) > 0) p!!.durationMs else row.durationMs,
                completed = p?.completed ?: false,
                endedReason = SessionEndReason.APP_KILLED.name,
            )
        }
        // 太老的（超过 7 天）不逐条修，统一标 UNKNOWN
        db.watchHistoryDao().closeStaleSessions(cutoff, nowMs)
        fixed
    }
}

private fun WatchHistoryEntity.toModel() = WatchRecord(
    sessionId = sessionId,
    sourceId = sourceId,
    mediaKey = mediaKey,
    title = titleSnapshot,
    startedAt = startedAt,
    endedAt = endedAt,
    startPositionMs = startPositionMs,
    endPositionMs = endPositionMs,
    durationMs = durationMs,
    completed = completed,
    endedReason = endedReason?.let { runCatching { SessionEndReason.valueOf(it) }.getOrNull() },
)

private fun PlaybackProgressEntity.toModel() = PlaybackProgress(
    sourceId = sourceId,
    mediaKey = mediaKey,
    positionMs = positionMs,
    durationMs = durationMs,
    completed = completed,
    playCount = playCount,
    lastPlayedAt = lastPlayedAt,
)
