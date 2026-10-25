package app.myplayer.feature.library.home

import app.myplayer.core.datastore.HomeModuleSettings
import app.myplayer.core.datastore.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 首页设置的读 / 写入口（`docs/03` §7 首页设置）。
 *
 * **只是语义层封装，不持有任何存储**：数据真正落在 `SettingsStore`（DataStore Preferences
 * 单 key JSON）的 `embyHomeSettings` / `smbHomeSettings` 两份 [HomeModuleSettings] 里 ——
 * 与设置页「首页设置」二级页共用**同一份真相**，任何一端写入，另一端的 Flow 立刻收到。
 *
 * 为什么不建独立的 DataStore 文件：两份配置和其它 PlayerSettings 一样「读多写少、字段少」，
 * 拆文件只会引入双写不一致的风险（设置页写 A 文件、首页读 B 文件）。
 *
 * Emby 与 SMB 各存一份：两种源的模块语义不同（库入口 vs 扫描文件夹），
 * 用户经常一边全开、另一边精简。
 */
class HomeSettingsRepository(private val settingsStore: SettingsStore) {

    /**
     * 按**当前源形态**取对应那份设置的流。
     *
     * [sourceType]（首页当前形态，Emby / SMB）变化时，Flow 自动切到另一份 ——
     * 订阅方（`HomeViewModel`）无需关心「现在是哪份」。
     */
    fun observe(sourceType: Flow<HomeSourceType>): Flow<HomeModuleSettings> =
        combine(settingsStore.settings, sourceType) { ps, type ->
            if (type == HomeSourceType.SMB) ps.smbHomeSettings else ps.embyHomeSettings
        }.distinctUntilChanged()

    /** 写回指定形态的那份设置（设置页「首页设置」二级页与测试共用）。 */
    suspend fun update(
        sourceType: HomeSourceType,
        transform: (HomeModuleSettings) -> HomeModuleSettings,
    ) {
        settingsStore.update {
            if (sourceType == HomeSourceType.SMB) {
                it.copy(smbHomeSettings = transform(it.smbHomeSettings))
            } else {
                it.copy(embyHomeSettings = transform(it.embyHomeSettings))
            }
        }
    }
}
