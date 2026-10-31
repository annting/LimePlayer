package app.myplayer.feature.privacy

import app.myplayer.core.model.MutablePrivacyVisibilityPolicy
import app.myplayer.core.security.privacy.AuthorizedPrivacyKey
import app.myplayer.core.security.privacy.PrivacySession
import app.myplayer.core.security.privacy.PrivacyUnlocker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/** 隐私模式的三态。 */
sealed interface PrivacyLockState {
    /** 还没设过隐私 PIN —— 首次进入要走设置向导。 */
    data object Unconfigured : PrivacyLockState

    /** 已配置，当前不在隐私模式（普通模式）。 */
    data object Locked : PrivacyLockState

    /** 隐私会话已激活：隐私源/隐私文档可见。 */
    data object Unlocked : PrivacyLockState
}

/**
 * 隐私会话的唯一持有者：管 `PrivacySession` 的生命周期，并驱动可见性策略。
 *
 * 职责边界：
 * - **只做状态**，不弹任何 UI（生物识别/Cipher 编排在 [PrivacyBiometricGate]）；
 * - 进会话时 `policy.enter()`，出会话时**先** `policy.exit()` 再清 DEK ——
 *   顺序反了会有一帧「源列表已刷新但会话还在」的窗口；
 * - 会话结束回调 [onSessionEnded] 是方案 §五「强清理序列」的挂点（停播 → 清通知 →
 *   取消任务 → 关连接 → 清缩略图缓存），后续块往里接。
 *
 * 线程模型：由主线程（Compose / Activity 生命周期）调用；KDF 与文件读写是同步的，
 * 因此 `openWithPin` 等应在 IO 线程调用（UI 层用 `withContext(Dispatchers.IO)`）。
 */
class PrivacySessionManager(
    private val unlocker: PrivacyUnlocker,
    private val policy: MutablePrivacyVisibilityPolicy,
) {

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<PrivacyLockState> = _state.asStateFlow()

    private var session: PrivacySession? = null

    /**
     * 会话结束钩子（方案 §五「强清理序列」）。
     *
     * 播放器/通知/连接/缓存各自在自己的模块里注册，避免本模块反向依赖 feature 层：
     * `PrivacySessionManager` 只负责「按顺序通知」，具体清什么由持有资源的模块决定。
     */
    private val endListeners = CopyOnWriteArrayList<() -> Unit>()

    /** 注册退出清理；返回取消注册的句柄（模块销毁时记得调）。 */
    fun addEndListener(listener: () -> Unit): () -> Unit {
        endListeners += listener
        return { endListeners -= listener }
    }

    val isPrivate: Boolean get() = _state.value == PrivacyLockState.Unlocked

    /**
     * 当前打开的会话（仅供同进程的**加密区**读写使用，业务层不要持有它）。
     *
     * `PrivacySecretVault` 通过这个回调取 DEK：会话一关它就变成 null，
     * 于是加密区里的隐私源地址/凭据立刻读不出来 —— 这是「退出即失联」的底层保证。
     */
    fun currentSession(): PrivacySession? = session

    fun isConfigured(): Boolean = unlocker.isConfigured()

    /** 已绑定的恢复钥匙（`pin` / `bio` / `device`）。 */
    fun availableKeks(): Set<String> = runCatching { unlocker.availableKeks() }.getOrDefault(emptySet())

    fun hasKek(kekId: String): Boolean = kekId in availableKeks()

    /** 首次设置：生成 DEK，PIN +（可选）已授权的指纹/设备密码一起封存，并直接进入隐私模式。 */
    fun create(pin: CharArray, authorizedKeys: List<AuthorizedPrivacyKey> = emptyList()): Result<Unit> =
        runCatching {
            activate(unlocker.create(pin, authorizedKeys = authorizedKeys))
        }

    /** PIN 解锁。 */
    fun openWithPin(pin: CharArray): Result<Unit> = runCatching {
        activate(unlocker.unlockWithPin(pin))
    }

    /** 生物识别 / 设备密码解锁（Cipher 已在认证回调里被授权）。 */
    fun openWithAuthorized(key: AuthorizedPrivacyKey): Result<Unit> = runCatching {
        activate(unlocker.unlockWithAuthorized(key))
    }

    /** 忘 PIN 恢复：用设备密码（或指纹）解封 DEK 后换新 PIN，**数据不丢**。 */
    fun resetPinWithAuthorized(key: AuthorizedPrivacyKey, newPin: CharArray): Result<Unit> = runCatching {
        activate(unlocker.resetPinWithAuthorized(key, newPin))
    }

    /** 在已打开的会话里绑定/刷新指纹钥匙（首次没开、或换指纹后重绑）。 */
    fun bindAuthorizedKey(key: AuthorizedPrivacyKey): Result<Unit> = runCatching {
        val current = session ?: throw IllegalStateException("会话未打开")
        unlocker.bindAuthorizedKey(current, key)
    }

    /** 解绑某把恢复钥匙（拒绝移除最后一把）。 */
    fun unbindKek(kekId: String): Result<Unit> = runCatching { unlocker.unbindKek(kekId) }

    /**
     * 退出隐私模式：**先**翻可见性策略（让所有查询立刻改为过滤），再关会话清 DEK。
     * 幂等：重复调用无副作用。
     */
    fun lock() {
        if (_state.value != PrivacyLockState.Unlocked && session == null) {
            // 仍要保证策略是关的：进程重启/异常路径下 policy 可能残留
            runCatching { policy.exit() }
            return
        }
        // 关会话这一步**不允许把状态卡在 Unlocked**（2026-10-29 真机：退出后右上角
        // 「隐私」标志还在）。之前 policy.exit() / session.close() 都没有保护，
        // 任一抛异常就会跳过下面的状态赋值 —— 状态留在 Unlocked，UI 于是一直认为
        // 还在隐私模式，而策略其实已经翻成过滤（首页显示的是普通源）。
        runCatching { policy.exit() }
        runCatching { session?.close() }
        session = null
        _state.value = if (runCatching { unlocker.isConfigured() }.getOrDefault(false)) {
            PrivacyLockState.Locked
        } else {
            PrivacyLockState.Unconfigured
        }
        // 清理按「先断可见性、后清资源」的顺序：监听器里若还要读库，读到的已是过滤后的视图。
        // 逐个隔离异常 —— 一个清理失败不能阻断其余清理，也不能让状态卡在 Unlocked。
        endListeners.forEach { listener -> runCatching(listener) }
    }

    /** 配置被外部清除（例如测试/重置）时刷新状态。 */
    fun refresh() {
        if (session == null) {
            _state.value = if (unlocker.isConfigured()) PrivacyLockState.Locked else PrivacyLockState.Unconfigured
        }
    }

    private fun activate(opened: PrivacySession) {
        // 会话替换：旧的一定要先关（正常路径不会有，但失败重试时可能出现）
        session?.takeIf { it !== opened }?.close()
        session = opened
        policy.enter()
        _state.value = PrivacyLockState.Unlocked
    }

    private fun initialState(): PrivacyLockState =
        if (unlocker.isConfigured()) PrivacyLockState.Locked else PrivacyLockState.Unconfigured
}
