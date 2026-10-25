package app.myplayer.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * 设置的唯一写入口是 [update]，其它 setter 都是它的语法糖。
 * 好处：新增设置项时只需在 [PlayerSettings] 加字段 + 加一行 setter，
 * 不会出现「模型有字段但 Store 没有 setter」的缺口。
 */
interface SettingsStore {
    val settings: Flow<PlayerSettings>
    suspend fun update(transform: (PlayerSettings) -> PlayerSettings)

    suspend fun setThemeMode(v: ThemeMode) = update { it.copy(themeMode = v) }
    suspend fun setDefaultSpeed(v: Float) = update { it.copy(defaultSpeed = v) }
    suspend fun setKeepBackgroundPlayback(v: Boolean) = update { it.copy(keepBackgroundPlayback = v) }
    suspend fun setDoubleTapSeekMillis(v: Long) = update { it.copy(doubleTapSeekMillis = v) }
    suspend fun setAutoPlayNext(v: Boolean) = update { it.copy(autoPlayNext = v) }
    suspend fun setScreenOrientation(v: ScreenOrientation) = update { it.copy(screenOrientation = v) }
    suspend fun setDefaultAspectRatio(v: AspectRatio) = update { it.copy(defaultAspectRatio = v) }
    suspend fun setSubtitleStyle(v: SubtitleStyle) = update { it.copy(subtitleStyle = v) }
    suspend fun setAppLockEnabled(v: Boolean) = update { it.copy(appLockEnabled = v) }
    suspend fun setUnlockMethod(v: UnlockMethod) = update { it.copy(unlockMethod = v) }
    suspend fun setUnlockGraceMillis(v: Long) = update { it.copy(unlockGraceMillis = v) }
    suspend fun setHideRecentsSnapshot(v: Boolean) = update { it.copy(hideRecentsSnapshot = v) }
    suspend fun setAllowCleartextHttp(v: Boolean) = update { it.copy(allowCleartextHttp = v) }
    suspend fun setTrustSelfSignedCert(v: Boolean) = update { it.copy(trustSelfSignedCert = v) }
    suspend fun setLocalThumbnails(v: Boolean) = update { it.copy(localThumbnails = v) }
    suspend fun setSearchFuzzyPinyin(v: Boolean) = update { it.copy(searchFuzzyPinyin = v) }
    suspend fun setSearchTraditionalSimplified(v: Boolean) = update { it.copy(searchTraditionalSimplified = v) }
    suspend fun setEmbyHomeSettings(v: HomeModuleSettings) = update { it.copy(embyHomeSettings = v) }
    suspend fun setSmbHomeSettings(v: HomeModuleSettings) = update { it.copy(smbHomeSettings = v) }
    suspend fun setActiveServerId(v: String?) = update { it.copy(activeServerId = v) }
}

class InMemorySettingsStore : SettingsStore {
    private val state = MutableStateFlow(PlayerSettings())
    override val settings: Flow<PlayerSettings> get() = state
    override suspend fun update(transform: (PlayerSettings) -> PlayerSettings) {
        state.value = transform(state.value)
    }
}

/**
 * 整份设置存成一个 JSON（单 key `player_settings_v1`）：纯数据、字段少、读多写少，
 * 单 key 比逐字段 key 更不容易漏，加字段也不用写迁移。若后续需要严格跨版本迁移再换 proto DataStore。
 */
class DataStoreSettingsStore(private val dataStore: DataStore<Preferences>) : SettingsStore {
    private val key = stringPreferencesKey("player_settings_v1")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override val settings: Flow<PlayerSettings> = dataStore.data
        // 读取失败 / 文件损坏时退回默认值，不能让整条 Flow 抛异常
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[key]?.let(::decode) ?: PlayerSettings() }

    override suspend fun update(transform: (PlayerSettings) -> PlayerSettings) {
        dataStore.edit { prefs ->
            val current = prefs[key]?.let(::decode) ?: PlayerSettings()
            prefs[key] = json.encodeToString(transform(current))
        }
    }

    private fun decode(raw: String): PlayerSettings =
        runCatching { json.decodeFromString<PlayerSettings>(raw) }.getOrElse { PlayerSettings() }
}
