package app.myplayer.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import app.myplayer.core.database.MediaDatabase
import app.myplayer.core.database.RoomLibraryRepository
import app.myplayer.core.datastore.*
import app.myplayer.core.model.LibraryRepository
import app.myplayer.core.network.HttpClientFactory
import app.myplayer.core.network.NetworkConfig
import app.myplayer.core.security.CredentialStorage
import app.myplayer.core.security.KeystoreSecretCipher
import app.myplayer.core.security.PatternCredentialStore
import app.myplayer.core.security.SecretCipher
import app.myplayer.core.security.SecretHasher
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
}
