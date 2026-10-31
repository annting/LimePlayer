package app.myplayer.core.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import app.myplayer.core.model.FileSeen
import app.myplayer.core.model.ShowPrivate
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1 → v2 迁移的行为级验证（`docs/10-数据存储方案.md` §9）。
 *
 * 为什么不直接用 `MigrationTestHelper`：它要求 schema json 作为 **androidTest assets** 可读，
 * 而本项目的单测跑在 Robolectric（test source set）上，assets 路径不保证可达。
 * 这里手工造一个真正的 v1 数据库（SQL 与 `schemas/…/1.json` 逐字一致 + 正确的 identity_hash），
 * 再用带 [MIGRATION_1_2] 的 Room 打开 —— 迁移 SQL 与实体有任何偏差，Room 的
 * `validateMigration` 都会直接抛 `IllegalStateException`，测试即失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationTest {

    private lateinit var ctx: Context
    private val dbName = "migration-1-2.db"

    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase(dbName)
    }

    @After fun tearDown() {
        ctx.deleteDatabase(dbName)
    }

    @Test
    fun `v1 to v2 keeps old rows and makes the three new tables writable`() = runTest {
        createV1Database()

        // 当前库版本是 v4：1→4 走连续迁移（真机上的真实路径）
        val db = Room.databaseBuilder(ctx, MediaDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        try {
            // ① 老数据必须完好（迁移是纯增量，不允许动 media_items）
            val old = db.mediaItemDao().get("srv", "old-item")
            assertThat(old).isNotNull()
            assertThat(old!!.title).isEqualTo("老片")

            // ② 三张新表都可写可读
            val catalog = RoomFileCatalog(db) { FIXED_NOW }
            catalog.recordSeen(
                listOf(
                    FileSeen(
                        sourceId = "srv", path = "Movies/沙丘.mkv", name = "沙丘.mkv",
                        title = "沙丘", sizeBytes = 1_000L, fileModifiedAt = 900L,
                        libraryFolder = "Movies",
                    )
                )
            )
            assertThat(catalog.entryOf("srv", "Movies/沙丘.mkv")!!.firstSeenAt).isEqualTo(FIXED_NOW)

            val store = RoomPlaybackStore(db) { FIXED_NOW }
            store.recordPosition("srv", "Movies/沙丘.mkv", positionMs = 60_000, durationMs = 600_000)
            assertThat(store.progressOf("srv", "Movies/沙丘.mkv")!!.positionMs).isEqualTo(60_000)

            assertThat(db.mediaStreamDao().get("srv", "Movies/沙丘.mkv")).isEmpty()
        } finally {
            db.close()
        }
    }

    @Test
    fun `migration is incremental only - version becomes 2 with all v1 tables intact`() = runTest {
        createV1Database()
        val db = Room.databaseBuilder(ctx, MediaDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        try {
            // v1 里已有的库记录同样必须保留（迁移只加表，不动老表）
            val libs = db.libraryDao().observe("srv").first()
            assertThat(libs).hasSize(1)
            assertThat(libs.first().name).isEqualTo("电影")
            assertThat(db.mediaItemDao().count("srv", "lib1")).isEqualTo(1)
        } finally {
            db.close()
        }
    }

    @Test
    fun `v2 to v3 keeps progress rows and makes watch_history writable`() = runTest {
        createV2Database()

        val db = Room.databaseBuilder(ctx, MediaDatabase::class.java, dbName)
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        try {
            // ① v2 里已写入的断点必须原样保留
            val store = RoomPlaybackStore(db) { FIXED_NOW }
            val prev = store.progressOf("srv", "Movies/沙丘.mkv")!!
            assertThat(prev.positionMs).isEqualTo(60_000L)
            assertThat(prev.durationMs).isEqualTo(600_000L)

            // ② watch_history 可写可闭合（v3 的新表）
            val sessionId = store.beginSession(
                sourceId = "srv", mediaKey = "Movies/沙丘.mkv", title = "沙丘",
                startPositionMs = 60_000, durationMs = 600_000, playMethod = "PROXY",
            )
            store.endSession(
                sessionId, "srv", "Movies/沙丘.mkv",
                endPositionMs = 120_000, durationMs = 600_000,
                completed = false, reason = app.myplayer.core.model.SessionEndReason.USER_EXIT,
            )
            val recent = store.recentWatched("srv")
            assertThat(recent).hasSize(1)
            assertThat(recent.single().endPositionMs).isEqualTo(120_000L)
        } finally {
            db.close()
        }
    }

    /**
     * v3 → v4：只加一列 `isPrivate`（档二 · 文档级隐私）。
     *
     * 验收两点：① 旧库里已登记的文件**一条都不能丢**，且默认按可见（`isPrivate = 0`）处理；
     * ② 新列可写（标记隐私后普通模式查不到），索引也随之建好。
     */
    @Test
    fun `v3 to v4 adds isPrivate with old rows visible`() = runTest {
        createV3Database()

        val db = Room.databaseBuilder(ctx, MediaDatabase::class.java, dbName)
            .addMigrations(MIGRATION_3_4)
            .allowMainThreadQueries()
            .build()
        try {
            // ① 旧行保留，默认可见
            val catalog = RoomFileCatalog(db) { FIXED_NOW }
            val old = catalog.entryOf("srv", "Movies/旧片.mkv")
            assertThat(old).isNotNull()
            assertThat(old!!.isPrivate).isFalse()
            assertThat(old.firstSeenAt).isEqualTo(FIXED_NOW)

            // ② 新列可写：标隐私后普通模式查不到，隐私模式查得到
            assertThat(catalog.setPrivate("srv", listOf("Movies/旧片.mkv"), true)).isEqualTo(1)
            assertThat(catalog.entryOf("srv", "Movies/旧片.mkv")).isNull()
            assertThat(RoomFileCatalog(db, ShowPrivate) { FIXED_NOW }.entryOf("srv", "Movies/旧片.mkv"))
                .isNotNull()
        } finally {
            db.close()
        }
    }

    /**
     * 手工建 v2 库：v1 建表 SQL + `MIGRATION_1_2` 的建表 SQL（与 2.json 逐字一致），
     * 再插入 v2 的 identity_hash —— 模拟「测试机上已经跑着 v2」的真实升级场景。
     */
    private fun createV2Database() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    V1_SCHEMA.forEach(db::execSQL)
                    V2_NEW_TABLES.forEach(db::execSQL)
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS room_master_table " +
                            "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
                    )
                    db.execSQL(
                        "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                            "VALUES(42, '$V2_IDENTITY_HASH')"
                    )
                    db.execSQL(
                        "INSERT INTO media_items (serverId, itemId, sourceType, title, sortTitle, " +
                            "durationTicks, audioCodecs, playCount, isPlayed, resumePositionTicks, " +
                            "isFavorite, genres, cast, libraryId, hasPoster, trickplayAvailable) " +
                            "VALUES ('srv', 'old-item', 'EMBY', '老片', '老片', 0, '', 0, 0, 0, 0, '', '', " +
                            "'lib1', 0, 0)"
                    )
                    db.execSQL(
                        "INSERT INTO playback_progress (sourceId, mediaKey, positionMs, durationMs, " +
                            "completed, playCount, lastPlayedAt, updatedAt, lastSessionId) " +
                            "VALUES ('srv', 'Movies/沙丘.mkv', 60000, 600000, 0, 0, 1, 1, NULL)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { it.writableDatabase }
    }

    /**
     * 手工建 v3 库：v1 + v2 的全部建表 SQL + `MIGRATION_2_3` 的 watch_history（与 3.json 逐字一致），
     * 再插入 v3 的 identity_hash —— 模拟「测试机上已经跑着 v3」的真实升级场景。
     */
    private fun createV3Database() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    V1_SCHEMA.forEach(db::execSQL)
                    V2_NEW_TABLES.forEach(db::execSQL)
                    V3_NEW_TABLES.forEach(db::execSQL)
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS room_master_table " +
                            "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
                    )
                    db.execSQL(
                        "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                            "VALUES(42, '$V3_IDENTITY_HASH')"
                    )
                    // v3 形状的 file_media_items（**没有** isPrivate 列）
                    db.execSQL(
                        "INSERT INTO file_media_items (sourceId, path, kind, name, title, sortTitle, " +
                            "sizeBytes, fileModifiedAt, firstSeenAt, lastSeenAt, missingAt, libraryFolder, " +
                            "durationMs, width, height, videoCodec, audioCodec, bitrate, thumbPath, " +
                            "probeState, probedAt) VALUES ('srv', 'Movies/旧片.mkv', 'VIDEO', '旧片.mkv', " +
                            "'旧片', '旧片', 1000, 900, $FIXED_NOW, $FIXED_NOW, NULL, 'Movies', NULL, NULL, " +
                            "NULL, NULL, NULL, NULL, NULL, 'NONE', NULL)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { it.writableDatabase }
    }

    /** 手工建 v1 库：SQL 来自 `schemas/…/1.json`，identity_hash 必须与之一致。 */
    private fun createV1Database() {
        val config = SupportSQLiteOpenHelper.Configuration.builder(ctx)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    V1_SCHEMA.forEach(db::execSQL)
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS room_master_table " +
                            "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
                    )
                    db.execSQL(
                        "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                            "VALUES(42, '$V1_IDENTITY_HASH')"
                    )
                    db.execSQL(
                        "INSERT INTO media_items (serverId, itemId, sourceType, title, sortTitle, " +
                            "durationTicks, audioCodecs, playCount, isPlayed, resumePositionTicks, " +
                            "isFavorite, genres, cast, libraryId, hasPoster, trickplayAvailable) " +
                            "VALUES ('srv', 'old-item', 'EMBY', '老片', '老片', 0, '', 0, 0, 0, 0, '', '', " +
                            "'lib1', 0, 0)"
                    )
                    db.execSQL(
                        "INSERT INTO libraries (serverId, libraryId, name, collectionType, itemCount) " +
                            "VALUES ('srv', 'lib1', '电影', 'movies', 1)"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { it.writableDatabase }
    }

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
        const val V1_IDENTITY_HASH = "7382ee3b375234c12c8e4c57e8c51bba"
        const val V2_IDENTITY_HASH = "947899bf1b86acb78e65ced317c3fcf3"
        const val V3_IDENTITY_HASH = "4e8373b17273ed7dc4682424f9785517"

        /** 与 `core/database/schemas/app.myplayer.core.database.MediaDatabase/1.json` 逐字一致。 */
        val V1_SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS `libraries` (`serverId` TEXT NOT NULL, `libraryId` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, `collectionType` TEXT, `itemCount` INTEGER, " +
                "PRIMARY KEY(`serverId`, `libraryId`))",
            "CREATE TABLE IF NOT EXISTS `media_items` (`serverId` TEXT NOT NULL, `itemId` TEXT NOT NULL, " +
                "`sourceType` TEXT NOT NULL, `title` TEXT NOT NULL, `sortTitle` TEXT NOT NULL, " +
                "`seriesName` TEXT, `seasonNumber` INTEGER, `episodeNumber` INTEGER, " +
                "`durationTicks` INTEGER NOT NULL, `sizeBytes` INTEGER, `bitrate` INTEGER, " +
                "`width` INTEGER, `height` INTEGER, `videoCodec` TEXT, `audioCodecs` TEXT NOT NULL, " +
                "`container` TEXT, `dateCreated` INTEGER, `communityRating` REAL, " +
                "`officialRating` TEXT, `playCount` INTEGER NOT NULL, `isPlayed` INTEGER NOT NULL, " +
                "`resumePositionTicks` INTEGER NOT NULL, `isFavorite` INTEGER NOT NULL, " +
                "`genres` TEXT NOT NULL, `cast` TEXT NOT NULL, `libraryId` TEXT, " +
                "`hasPoster` INTEGER NOT NULL, `trickplayAvailable` INTEGER NOT NULL, " +
                "PRIMARY KEY(`serverId`, `itemId`))",
            "CREATE INDEX IF NOT EXISTS `index_media_items_serverId` ON `media_items` (`serverId`)",
            "CREATE INDEX IF NOT EXISTS `index_media_items_libraryId` ON `media_items` (`libraryId`)",
            "CREATE INDEX IF NOT EXISTS `index_media_items_sortTitle` ON `media_items` (`sortTitle`)",
        )

        /** v2 新增的三张表（与 `MIGRATION_1_2` 的 SQL 逐字一致 —— 手写库必须与 2.json 同指纹）。 */
        val V2_NEW_TABLES = listOf(
            "CREATE TABLE IF NOT EXISTS `file_media_items` (" +
                "`sourceId` TEXT NOT NULL, `path` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, `title` TEXT NOT NULL, `sortTitle` TEXT NOT NULL, " +
                "`sizeBytes` INTEGER NOT NULL, `fileModifiedAt` INTEGER NOT NULL, " +
                "`firstSeenAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, " +
                "`missingAt` INTEGER, `libraryFolder` TEXT, `durationMs` INTEGER, " +
                "`width` INTEGER, `height` INTEGER, `videoCodec` TEXT, `audioCodec` TEXT, " +
                "`bitrate` INTEGER, `thumbPath` TEXT, `probeState` TEXT NOT NULL, " +
                "`probedAt` INTEGER, PRIMARY KEY(`sourceId`, `path`))",
            "CREATE TABLE IF NOT EXISTS `playback_progress` (" +
                "`sourceId` TEXT NOT NULL, `mediaKey` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, " +
                "`durationMs` INTEGER NOT NULL, `completed` INTEGER NOT NULL, " +
                "`playCount` INTEGER NOT NULL, `lastPlayedAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `lastSessionId` TEXT, " +
                "PRIMARY KEY(`sourceId`, `mediaKey`))",
            "CREATE TABLE IF NOT EXISTS `media_streams` (" +
                "`sourceId` TEXT NOT NULL, `path` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`streamIndex` INTEGER NOT NULL, `codec` TEXT, `language` TEXT, " +
                "`displayTitle` TEXT, `width` INTEGER, `height` INTEGER, `frameRate` REAL, " +
                "`bitDepth` INTEGER, `channels` INTEGER, `sampleRate` INTEGER, `bitrate` INTEGER, " +
                "`isExternal` INTEGER NOT NULL, `isDefault` INTEGER NOT NULL, " +
                "PRIMARY KEY(`sourceId`, `path`, `kind`, `streamIndex`))",
            // 索引也是 schema 指纹的一部分：漏了任何一个 Room 都会报「didn't properly handle」
            "CREATE INDEX IF NOT EXISTS `index_file_media_items_sourceId_firstSeenAt` " +
                "ON `file_media_items` (`sourceId`, `firstSeenAt`)",
            "CREATE INDEX IF NOT EXISTS `index_file_media_items_sourceId_libraryFolder_sortTitle` " +
                "ON `file_media_items` (`sourceId`, `libraryFolder`, `sortTitle`)",
            "CREATE INDEX IF NOT EXISTS `index_file_media_items_probeState` " +
                "ON `file_media_items` (`probeState`)",
            "CREATE INDEX IF NOT EXISTS `index_file_media_items_missingAt` " +
                "ON `file_media_items` (`missingAt`)",
            "CREATE INDEX IF NOT EXISTS `index_playback_progress_completed_lastPlayedAt` " +
                "ON `playback_progress` (`completed`, `lastPlayedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_media_streams_sourceId_path` " +
                "ON `media_streams` (`sourceId`, `path`)",
        )

        /** v3 新增的 watch_history（与 `MIGRATION_2_3` 的 SQL 逐字一致 —— 手写库必须与 3.json 同指纹）。 */
        val V3_NEW_TABLES = listOf(
            "CREATE TABLE IF NOT EXISTS `watch_history` (" +
                "`sessionId` TEXT NOT NULL, `sourceId` TEXT NOT NULL, `mediaKey` TEXT NOT NULL, " +
                "`titleSnapshot` TEXT NOT NULL, `kind` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, " +
                "`endedAt` INTEGER, `startPositionMs` INTEGER NOT NULL, `endPositionMs` INTEGER, " +
                "`durationMs` INTEGER NOT NULL, `completed` INTEGER NOT NULL, " +
                "`playMethod` TEXT, `endedReason` TEXT, PRIMARY KEY(`sessionId`))",
            "CREATE INDEX IF NOT EXISTS `index_watch_history_startedAt` " +
                "ON `watch_history` (`startedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_watch_history_sourceId_mediaKey_startedAt` " +
                "ON `watch_history` (`sourceId`, `mediaKey`, `startedAt`)",
            "CREATE INDEX IF NOT EXISTS `index_watch_history_endedAt` " +
                "ON `watch_history` (`endedAt`)",
        )
    }
}
