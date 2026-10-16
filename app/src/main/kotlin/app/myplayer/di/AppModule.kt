package app.myplayer.di

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import app.myplayer.core.database.MediaDatabase
import app.myplayer.core.database.RoomLibraryRepository
import app.myplayer.core.datastore.*
import app.myplayer.core.model.ClientCapabilities
import app.myplayer.core.model.LibraryRepository
import app.myplayer.core.network.HttpClientFactory
import app.myplayer.core.network.NetworkConfig
import app.myplayer.core.security.CredentialStorage
import app.myplayer.core.security.KeystoreSecretCipher
import app.myplayer.core.security.PatternCredentialStore
import app.myplayer.core.security.SecretCipher
import app.myplayer.core.security.SecretHasher
import app.myplayer.feature.server.SourceFactory
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
        Room.databaseBuilder(ctx, MediaDatabase::class.java, "myplayer.db").build()

    @Provides @Singleton
    fun libraryRepository(db: MediaDatabase): LibraryRepository = RoomLibraryRepository(db)

    @Provides @Singleton
    fun settingsStore(@ApplicationContext ctx: Context): SettingsStore = DataStoreSettingsStore(ctx.dataStore)

    /** 应用私有目录：`servers.bin`、`token_*.bin`、`app_lock.bin` 都放这里，明文不落盘。 */
    @Provides @Singleton
    fun credentialDir(@ApplicationContext ctx: Context): File =
        File(ctx.filesDir, "secrets").apply { mkdirs() }

    @Provides @Singleton
    fun secretCipher(): SecretCipher = KeystoreSecretCipher()

    @Provides @Singleton
    fun serverConfigStore(dir: File, cipher: SecretCipher): ServerConfigStore =
        FileServerConfigStore(File(dir, "servers.bin"), cipher)

    @Provides @Singleton
    fun tokenStore(dir: File, cipher: SecretCipher): TokenStore {
        val storage = DirByteFileStorage(dir)
        return EncryptedTokenStore(cipher, storage::read, storage::write, storage::delete)
    }

    @Provides @Singleton
    fun credentialStorage(dir: File, cipher: SecretCipher): CredentialStorage =
        FileCredentialStorage(File(dir, "app_lock.bin"), cipher)

    @Provides @Singleton
    fun patternCredentialStore(storage: CredentialStorage): PatternCredentialStore =
        PatternCredentialStore(SecretHasher(), storage)

    @Provides @Singleton
    fun okHttpClient(): OkHttpClient = HttpClientFactory.create(NetworkConfig())

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
    ): SourceFactory = SourceFactory(client, serverConfigStore, tokenStore, deviceId)

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
