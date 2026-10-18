package app.myplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.myplayer.core.datastore.PlayerSettings
import app.myplayer.core.datastore.ServerConfig
import app.myplayer.core.datastore.ServerConfigStore
import app.myplayer.core.datastore.SettingsStore
import app.myplayer.core.datastore.SubtitleStyle
import app.myplayer.core.datastore.ThemeMode
import app.myplayer.core.datastore.TokenStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 服务器列表行：[active] 为当前使用的服务器。 */
data class ServerRow(val config: ServerConfig, val active: Boolean)

data class SettingsUiState(
    val servers: List<ServerRow> = emptyList(),
    val settings: PlayerSettings = PlayerSettings(),
)

/**
 * 设置页（`01` §6 全部设置项 + 服务器管理）。
 *
 * 唯一写入口是 [SettingsStore.update] 的语法糖 —— 这里只做「状态流组装 + 转发」，
 * 不持有任何业务规则；服务器管理对 [ServerConfigStore] 与 [TokenStore] 成对操作。
 */
class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val configStore: ServerConfigStore,
    private val tokenStore: TokenStore,
) : ViewModel() {

    val state: StateFlow<SettingsUiState> =
        combine(settingsStore.settings, configStore.observeAll()) { st, servers ->
            // 当前服务器：activeServerId 优先；未设置时取列表第一个（与 MainActivity 的选择一致）
            val activeId = st.activeServerId ?: servers.firstOrNull()?.id
            SettingsUiState(
                servers = servers.map { ServerRow(it, it.id == activeId) },
                settings = st,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    /** 切换当前服务器；MainActivity 会用新 serverId 重建 MediaSource。 */
    fun setActiveServer(id: String) {
        viewModelScope.launch { settingsStore.setActiveServerId(id) }
    }

    /** 删除服务器 = 配置 + 令牌成对清除；若是当前服务器则交回自动选择。 */
    fun deleteServer(id: String) {
        viewModelScope.launch {
            configStore.remove(id)
            tokenStore.remove(id)
            if (state.value.settings.activeServerId == id) {
                settingsStore.setActiveServerId(null)
            }
        }
    }

    // ---- 播放 / 画面 ----
    fun setThemeMode(v: ThemeMode) = launch { settingsStore.setThemeMode(v) }
    fun setDefaultSpeed(v: Float) = launch { settingsStore.setDefaultSpeed(v) }
    fun setKeepBackgroundPlayback(v: Boolean) = launch { settingsStore.setKeepBackgroundPlayback(v) }
    fun setDoubleTapSeekMillis(v: Long) = launch { settingsStore.setDoubleTapSeekMillis(v) }
    fun setAutoPlayNext(v: Boolean) = launch { settingsStore.setAutoPlayNext(v) }
    fun setScreenOrientation(v: app.myplayer.core.datastore.ScreenOrientation) =
        launch { settingsStore.setScreenOrientation(v) }
    fun setDefaultAspectRatio(v: app.myplayer.core.datastore.AspectRatio) =
        launch { settingsStore.setDefaultAspectRatio(v) }

    // ---- 字幕 ----
    fun setSubtitleStyle(transform: (SubtitleStyle) -> SubtitleStyle) =
        launch { settingsStore.update { it.copy(subtitleStyle = transform(it.subtitleStyle)) } }

    // ---- 隐私 ----
    fun setAppLockEnabled(v: Boolean) = launch { settingsStore.setAppLockEnabled(v) }
    fun setUnlockGraceMillis(v: Long) = launch { settingsStore.setUnlockGraceMillis(v) }
    fun setHideRecentsSnapshot(v: Boolean) = launch { settingsStore.setHideRecentsSnapshot(v) }

    // ---- 预览 / 搜索 / 网络 / 界面 ----
    fun setLocalThumbnails(v: Boolean) = launch { settingsStore.setLocalThumbnails(v) }
    fun setSearchFuzzyPinyin(v: Boolean) = launch { settingsStore.setSearchFuzzyPinyin(v) }
    fun setSearchTraditionalSimplified(v: Boolean) = launch { settingsStore.setSearchTraditionalSimplified(v) }
    fun setAllowCleartextHttp(v: Boolean) = launch { settingsStore.setAllowCleartextHttp(v) }
    fun setTrustSelfSignedCert(v: Boolean) = launch { settingsStore.setTrustSelfSignedCert(v) }
    fun setLibraryDisplayMode(v: app.myplayer.core.datastore.LibraryDisplayMode) =
        launch { settingsStore.setLibraryDisplayMode(v) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
