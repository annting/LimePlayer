package app.myplayer.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * 隐私可见性策略 —— **所有数据入口的唯一过滤依据**
 * （`docs/隐私模式方案.md` §三 铁律 1 / P0#2「统一可见性策略」）。
 *
 * 为什么要有这一层：隐私内容散落在 DataStore（`ServerConfig.isPrivate`）与 Room
 * （`file_media_items.isPrivate` + 关联它的进度/历史）两套存储、十几个查询入口。
 * 若靠每个查询各自写 `WHERE isPrivate = 0`，漏一处就是一次泄露。这里把「当前能不能看隐私」
 * 收敛成一个可注入的开关，由**实现方**（`core:datastore` / `core:database`）在每个读入口取一次，
 * 调用方（UI/源/播放层）**不需要也无法**绕过 —— 它们在普通模式下根本拿不到隐私数据。
 *
 * 默认实现是 [HidePrivate]（普通模式）：任何忘了接策略的实现都会「宁可少显示」，
 * 而不是把隐私内容漏出去。这个方向性是刻意的 —— 漏显示是可见的 bug，漏数据是不可逆的事故。
 */
interface PrivacyVisibilityPolicy {

    /** true = 当前处于**隐私模式**，可见 `isPrivate` 的源与文档。 */
    fun includePrivate(): Boolean

    /**
     * 模式变化流：进/出隐私模式时重新发值，供 Flow 型查询（如服务器列表）重新过滤。
     *
     * 默认只发一次当前值 —— 恒定策略（[HidePrivate] / [ShowPrivate]）与测试替身够用；
     * 需要响应式切换的实现重写此方法（见 [MutablePrivacyVisibilityPolicy]）。
     */
    fun observeIncludePrivate(): Flow<Boolean> = flowOf(includePrivate())
}

/** 普通模式：隐私源/隐私文档一律不可见。**默认值**，也是「忘了注入」时的安全兜底。 */
object HidePrivate : PrivacyVisibilityPolicy {
    override fun includePrivate(): Boolean = false
    override fun toString(): String = "HidePrivate"
}

/** 隐私模式：可见隐私源/隐私文档（会话激活期间由 UI 层注入真实策略，测试里直接用它）。 */
object ShowPrivate : PrivacyVisibilityPolicy {
    override fun includePrivate(): Boolean = true
    override fun toString(): String = "ShowPrivate"
}

/**
 * 可在运行时切换的策略（进/出隐私模式）。
 *
 * 生产装配只构造这一个实例（`AppModule`），由隐私会话（生物识别/PIN 通过 ⇄ 退后台）
 * 调 [enter] / [exit] 翻转；所有注入方持有的是同一个对象，因此切换是全局一致、立即生效的。
 */
class MutablePrivacyVisibilityPolicy(initialVisible: Boolean = false) : PrivacyVisibilityPolicy {

    private val state = MutableStateFlow(initialVisible)

    override fun includePrivate(): Boolean = state.value

    override fun observeIncludePrivate(): Flow<Boolean> = state.asStateFlow()

    /** 进入隐私模式（会话已通过第二重验证）。 */
    fun enter() {
        state.value = true
    }

    /** 退出隐私模式（退后台 / 手动退出）：**先**翻这里，再清会话与缓存。 */
    fun exit() {
        state.value = false
    }
}
