package app.myplayer.di

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import app.myplayer.core.database.MIGRATION_1_2
import app.myplayer.core.database.MIGRATION_2_3
import app.myplayer.core.database.MIGRATION_3_4
import app.myplayer.core.database.MediaDatabase
import app.myplayer.core.database.RoomFileCatalog
import app.myplayer.core.database.RoomLibraryRepository
import app.myplayer.core.database.RoomPlaybackStore
import app.myplayer.core.datastore.*
import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.FileCatalogStore
import app.myplayer.core.model.LibraryRepository
import app.myplayer.core.model.MutablePrivacyVisibilityPolicy
import app.myplayer.core.model.PlaybackStore
import app.myplayer.core.model.PrivacyVisibilityPolicy
import app.myplayer.core.network.HttpClientFactory
import app.myplayer.NetworkPolicy
import app.myplayer.cleartextGuard
import app.myplayer.core.network.NetworkConfig
import app.myplayer.core.security.CredentialStorage
import app.myplayer.core.security.KeystoreSecretCipher
import app.myplayer.core.security.PatternCredentialStore
import app.myplayer.core.security.SecretCipher
import app.myplayer.core.security.SecretHasher
import app.myplayer.core.security.privacy.PrivacySecretVault
import app.myplayer.core.security.privacy.PrivacyUnlocker
import app.myplayer.core.security.privacy.PrivacyVaultStore
import app.myplayer.feature.privacy.PrivacySessionManager
import app.myplayer.feature.server.SourceFactory
import app.myplayer.playback.PlaybackEngineHolder
import app.myplayer.playback.PlaybackService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

/**
 * 装配点：**所有落盘实现都在这里注入**（内存实现只允许出现在测试里）。
 * 令牌 / 服务器配置 / 应用锁凭据均在 `filesDir/secrets/` 下以密文保存。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun mediaDatabase(@ApplicationContext ctx: Context): MediaDatabase =
        Room.databaseBuilder(ctx, MediaDatabase::class.java, "myplayer.db")
            // v1→v2（三张新表）、v2→v3（watch_history）、v3→v4（file_media_items.isPrivate）
            // 都是纯增量。builder 没有 fallbackToDestructiveMigration，少了迁移覆盖安装会直接崩在启动。
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()

    /**
     * 隐私可见性**唯一开关**（`docs/隐私模式方案.md` §三 P0#2）。
     *
     * 单例：服务器配置、文件库存、进度/历史三处的过滤都读它，进/出隐私模式时只翻这一个。
     * 初始 false（普通模式）—— 隐私会话（生物识别/PIN）通过后才 `enter()`，退后台 `exit()`。
     *
     * 同时按具体类型暴露一份：隐私会话要调 `enter()/exit()`，而消费方只需要只读的策略接口。
     */
    @Provides @Singleton
    fun mutablePrivacyVisibilityPolicy(): MutablePrivacyVisibilityPolicy =
        MutablePrivacyVisibilityPolicy()

    @Provides @Singleton
    fun privacyVisibilityPolicy(policy: MutablePrivacyVisibilityPolicy): PrivacyVisibilityPolicy =
        policy

    @Provides @Singleton
    fun libraryRepository(db: MediaDatabase): LibraryRepository = RoomLibraryRepository(db)

    /** 本地进度（文件型源专用）；播放层写、源与页面读。 */
    @Provides @Singleton
    fun playbackStore(db: MediaDatabase, privacy: PrivacyVisibilityPolicy): PlaybackStore =
        RoomPlaybackStore(db, privacy)

    /** 文件型条目库存 + 探测结果（SMB 用它把「取一次」的结果落盘）。 */
    @Provides @Singleton
    fun fileCatalogStore(db: MediaDatabase, privacy: PrivacyVisibilityPolicy): FileCatalogStore =
        RoomFileCatalog(db, privacy)

    @Provides @Singleton
    fun settingsStore(@ApplicationContext ctx: Context): SettingsStore = DataStoreSettingsStore(ctx.dataStore)

    /** 应用私有目录：`servers.bin`、`token_*.bin`、`app_lock.bin` 都放这里，明文不落盘。 */
    @Provides @Singleton
    fun credentialDir(@ApplicationContext ctx: Context): File =
        File(ctx.filesDir, "secrets").apply { mkdirs() }

    @Provides @Singleton
    fun secretCipher(): SecretCipher = KeystoreSecretCipher()

    @Provides @Singleton
    fun serverConfigStore(
        dir: File,
        cipher: SecretCipher,
        privacy: PrivacyVisibilityPolicy,
        privateSecrets: PrivacyServerSecretStore,
    ): ServerConfigStore = FileServerConfigStore(File(dir, "servers.bin"), cipher, privacy, privateSecrets)

    /**
     * 私有源的敏感字段（地址 / 账号 / 令牌）加密区。
     *
     * 必须放 `noBackupFilesDir`：它跟保险箱一样，属于「换机迁移不该带走」的数据
     * （方案 §4.3）。会话未开时读不出任何明文，因此普通模式连私有源地址都拿不到。
     */
    @Provides @Singleton
    fun privacySecretVault(
        @ApplicationContext ctx: Context,
        manager: PrivacySessionManager,
    ): PrivacySecretVault =
        PrivacySecretVault(File(ctx.noBackupFilesDir, "privacy/secrets")) { manager.currentSession() }

    @Provides @Singleton
    fun privacyServerSecretStore(vault: PrivacySecretVault): PrivacyServerSecretStore =
        PrivacyServerSecretStore(vault)

    /**
     * 令牌分流：私有源进 DEK 加密区，普通源维持原 `token_<id>.bin`。
     *
     * 判定看「加密区里有没有这条服务器的条目」—— 写令牌前配置一定已经 upsert 过，
     * 所以顺序不能反（见 [PrivacyAwareTokenStore] 的注释）。
     */
    @Provides @Singleton
    fun tokenStore(
        dir: File,
        cipher: SecretCipher,
        privateSecrets: PrivacyServerSecretStore,
    ): TokenStore {
        val storage = DirByteFileStorage(dir)
        return PrivacyAwareTokenStore(
            delegate = EncryptedTokenStore(cipher, storage::read, storage::write, storage::delete),
            secrets = privateSecrets,
        )
    }

    @Provides @Singleton
    fun credentialStorage(dir: File, cipher: SecretCipher): CredentialStorage =
        FileCredentialStorage(File(dir, "app_lock.bin"), cipher)

    @Provides @Singleton
    fun patternCredentialStore(storage: CredentialStorage): PatternCredentialStore =
        PatternCredentialStore(SecretHasher(), storage)

    /**
     * 隐私保险箱：**必须**放 `noBackupFilesDir` ——
     * 它是全库唯一「丢了就彻底打不开隐私数据」的文件，
     * 放 `filesDir` 会被 Android 自动备份/设备迁移带走，违反方案 §A–F 的 D 条。
     */
    @Provides @Singleton
    fun privacyVaultStore(@ApplicationContext ctx: Context): PrivacyVaultStore =
        PrivacyVaultStore(File(ctx.noBackupFilesDir, "privacy/vault.bin"))

    /** 隐私 PIN 的封存/解封入口（不存 PIN 哈希：PIN 对不对由能否解封 DEK 判定）。 */
    @Provides @Singleton
    fun privacyUnlocker(store: PrivacyVaultStore): PrivacyUnlocker = PrivacyUnlocker(store)

    /**
     * 隐私会话单例：`MainActivity` 生命周期、关于页入口、设置页都读它。
     *
     * 装配在这里而不是模块内部，是为了让「进/出隐私模式」只有一个持有者 ——
     * 多实例会导致一处 unlock、另一处还按普通模式过滤。
     *
     * **会话结束的强清理序列**（方案 §五.2）也挂在这里：
     * ① 停播（释放当前媒体）② 停服务 → 释放 MediaSession 与媒体通知
     * ③ 清缩略图/封面缓存。取消费/下载任务与关连接在 `onSessionEnded` 之外由
     * 各源自己按「进程内无请求」自然结束（本 App 没有 WorkManager 下载器）。
     */
    @Provides @Singleton
    fun privacySessionManager(
        unlocker: PrivacyUnlocker,
        policy: MutablePrivacyVisibilityPolicy,
        @ApplicationContext ctx: Context,
        engineHolder: PlaybackEngineHolder,
    ): PrivacySessionManager = PrivacySessionManager(unlocker, policy).also { manager ->
        manager.addEndListener {
            runCatching { engineHolder.engine.stopSession() }
            runCatching { ctx.stopService(Intent(ctx, PlaybackService::class.java)) }
            // 内存缓存同步清（很快）；磁盘缓存放后台，避免退出隐私时卡一帧
            runCatching { coil3.SingletonImageLoader.get(ctx).memoryCache?.clear() }
            Thread {
                runCatching { coil3.SingletonImageLoader.get(ctx).diskCache?.clear() }
            }.start()
        }
    }

    @Provides @Singleton
    fun okHttpClient(policy: NetworkPolicy): OkHttpClient = HttpClientFactory.create(
        NetworkConfig(
            extraInterceptors = listOf(cleartextGuard(policy)),
            // 运行期读开关：设置页一改，下一次 TLS 握手就生效（不用重启 App）
            trustSelfSignedProvider = { policy.trustSelfSignedCert },
        )
    )

    /** 稳定的设备标识：Emby 用它区分会话，在重装之外保持稳定。 */
    @Provides @Singleton
    fun deviceId(@ApplicationContext ctx: Context): String =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    @Provides @Singleton
    fun sourceFactory(
        client: OkHttpClient,
        serverConfigStore: ServerConfigStore,
        tokenStore: TokenStore,
        deviceId: String,
        playbackStore: PlaybackStore,
        fileCatalogStore: FileCatalogStore,
        privacyPolicy: MutablePrivacyVisibilityPolicy,
    ): SourceFactory = SourceFactory(
        client = client,
        serverConfigStore = serverConfigStore,
        tokenStore = tokenStore,
        deviceId = deviceId,
        playbackStore = playbackStore,
        fileCatalog = fileCatalogStore,
        // 档二：SMB 列目录时按当前策略隐藏私有文件（同一份单例策略，会话开关即时生效）
        privacy = privacyPolicy,
    )

    /**
     * 本机播放能力声明（服务端 `DeviceProfile` 与三档协商的输入）。
     *
     * 编解码列表按「常见且 Media3 原生可解」取保守集合；后续可用 `MediaCodecList`
     * 逐项探测细化（M3 起的按需优化，当前不影响直放/转码决策的正确性 —— 协商失败会安全降级到转码）。
     */
    @Provides @Singleton
    fun clientCapabilities(deviceId: String): ClientCapabilities = ClientCapabilities(
        supportedVideoCodecs = setOf("h264", "hevc", "vp9", "av1", "mpeg4"),
        supportedAudioCodecs = setOf("aac", "mp3", "ac3", "eac3", "flac", "opus", "vorbis"),
        maxWidth = 3840,
        maxHeight = 2160,
        supportsHdr = true,
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
        deviceId = deviceId,
    )
}
