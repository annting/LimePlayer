package app.myplayer.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.myplayer.core.datastore.PlayerSettings
import app.myplayer.core.datastore.PrivacyServerSecretStore
import app.myplayer.core.datastore.ServerConfig
import app.myplayer.core.datastore.ServerSecrets
import app.myplayer.core.datastore.ServerConfigStore
import app.myplayer.core.datastore.SettingsStore
import app.myplayer.core.datastore.SubtitleStyle
import app.myplayer.core.datastore.ThemeMode
import app.myplayer.core.datastore.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
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
    /** 一次性提示（如「移入隐私」失败的原因）；null = 不显示。 */
    val notice: String? = null,
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
    /** 私有源敏感字段的加密区；null = 未接（测试替身），「移入隐私」不可用。 */
    private val privateSecrets: PrivacyServerSecretStore? = null,
) : ViewModel() {

    private val _notice = MutableStateFlow<String?>(null)

    val state: StateFlow<SettingsUiState> =
        combine(settingsStore.settings, configStore.observeAll(), _notice) { st, servers, notice ->
            // 当前服务器：activeServerId 优先；未设置时取列表第一个（与 MainActivity 的选择一致）
            val activeId = st.activeServerId ?: servers.firstOrNull()?.id
            SettingsUiState(
                servers = servers.map { ServerRow(it, it.id == activeId) },
                settings = st,
                notice = notice,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun dismissNotice() { _notice.value = null }

    /** 切换当前服务器；MainActivity 会用新 serverId 重建 MediaSource。 */
    fun setActiveServer(id: String) {
        viewModelScope.launch { settingsStore.setActiveServerId(id) }
    }

    /**
     * 「移入隐私」（决策 22）：把已有普通源变成隐私源，**不重建、数据不丢**。
     *
     * 事务顺序（任一步失败都回滚到「仍是普通源」）：
     * 1. 从普通区读令牌（此时加密区还没有条目，读到的就是普通区那份）
     * 2. 写加密区（会话未开会在这一步抛错 → 什么都还没改）
     * 3. 配置改壳 + 标记 isPrivate
     * 4. 用令牌再写一次：此时加密区条目已存在，`put` 会落加密区并顺手删掉普通区的副本
     */
    fun moveToPrivacy(id: String) {
        val secrets = privateSecrets ?: run {
            _notice.value = "隐私加密区未就绪，无法移入"
            return
        }
        val config = state.value.servers.firstOrNull { it.config.id == id }?.config ?: return
        if (config.isPrivate) return
        viewModelScope.launch {
            val token = tokenStore.get(id)
            try {
                secrets.write(
                    id,
                    ServerSecrets(baseUrl = config.baseUrl, userId = config.userId, token = token),
                )
                configStore.upsert(config.copy(isPrivate = true))
                token?.let { tokenStore.put(id, it) }
                _notice.value = null
            } catch (t: Throwable) {
                // 回滚：删掉刚写的加密条目，配置仍是普通源（令牌也还在普通区）
                runCatching { secrets.delete(id) }
                _notice.value = "移入隐私失败：${t.message ?: "未知错误"}"
            }
        }
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

    // ---- 首页（Emby / SMB 各一套，首页设置二级页） ----
    fun setEmbyHomeSettings(v: app.myplayer.core.datastore.HomeModuleSettings) =
        launch { settingsStore.setEmbyHomeSettings(v) }
    fun setSmbHomeSettings(v: app.myplayer.core.datastore.HomeModuleSettings) =
        launch { settingsStore.setSmbHomeSettings(v) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
