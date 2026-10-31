package app.myplayer.feature.lock

import app.myplayer.core.security.VerifyResult
import app.myplayer.core.security.WeakPatternRules

/**
 * 设置图案的三个阶段：
 * - [VERIFY] 已有图案时先验证旧的（防止拿着已解锁的手机直接改掉锁屏）；
 * - [FIRST] 画新图案；
 * - [CONFIRM] 再画一遍确认。
 *
 * 首次录入没有旧图案，直接从 [FIRST] 开始。
 */
enum class PatternSetupStage { VERIFY, FIRST, CONFIRM }

/** 一次「抬手」之后的判定结果。 */
sealed interface PatternSetupOutcome {
    /** 旧图案验证通过，进入「画新图案」阶段。 */
    data class Verified(val message: String) : PatternSetupOutcome

    /** 第一次通过校验，进入确认阶段。 */
    data class Confirm(val message: String) : PatternSetupOutcome

    /** 不合格：太短 / 太简单 / 两次不一致 / 旧图案错误。停在当前阶段并给出提示。 */
    data class Reject(val message: String) : PatternSetupOutcome

    /** 两次一致，可以落库。 */
    data class Save(val pattern: List<Int>) : PatternSetupOutcome
}

/**
 * 图案设置的状态机（纯逻辑，可单测）。
 *
 * 规则与 `PatternCredentialStore.savePattern` 的前置条件保持一致：
 * 至少 4 点（[minPoints]）、不能是弱图案（一行 / 一列 / 一条对角线）。
 * 两次不一致时**整体重来**（而不是停在确认阶段）—— 否则用户会分不清哪一遍画错了。
 *
 * @param verifier 旧图案校验器（通常直接传 `credentials::verifyPattern`）。
 *   非 null 表示「已经录过图案」，必须先过 [PatternSetupStage.VERIFY]；
 *   重试次数与冷却由 [PatternCredentialStore] 自己管（5 次后 30s / 1min / 5min 递增），
 *   这里只负责把结果翻译成文案。
 */
class PatternSetupController(
    private val minPoints: Int = 4,
    private val verifier: ((List<Int>) -> VerifyResult)? = null,
) {

    var stage: PatternSetupStage = if (verifier != null) PatternSetupStage.VERIFY else PatternSetupStage.FIRST
        private set

    private var first: List<Int>? = null

    fun submit(points: List<Int>): PatternSetupOutcome {
        if (points.size < minPoints) return reject("至少连接 $minPoints 个点")

        if (stage == PatternSetupStage.VERIFY) return verify(points)

        return when (stage) {
            PatternSetupStage.FIRST -> {
                if (WeakPatternRules.isWeak(points)) {
                    reject("图案太简单：别只用一行 / 一列 / 一条对角线")
                } else {
                    first = points
                    stage = PatternSetupStage.CONFIRM
                    PatternSetupOutcome.Confirm("请再次绘制以确认")
                }
            }

            PatternSetupStage.CONFIRM -> {
                if (points == first) PatternSetupOutcome.Save(points)
                else reject("两次绘制不一致，请重新设置")
            }

            // 已在上面的 if 里处理
            PatternSetupStage.VERIFY -> reject("请先验证原有图案")
        }
    }

    /**
     * 旧图案校验。失败时**停在 VERIFY**（不像新图案那样整体重来）：
     * 用户改主意或手滑不该丢掉「已通过验证」以外的东西，重试次数由 store 记账。
     */
    private fun verify(points: List<Int>): PatternSetupOutcome {
        val check = verifier ?: return reject("请先验证原有图案")
        return when (val result = check(points)) {
            is VerifyResult.Success -> {
                stage = PatternSetupStage.FIRST
                PatternSetupOutcome.Verified("验证通过，请绘制新的解锁图案")
            }
            is VerifyResult.WrongPattern ->
                PatternSetupOutcome.Reject("图案错误，还可尝试 ${result.attemptsLeft} 次")
            is VerifyResult.Cooldown ->
                PatternSetupOutcome.Reject("尝试过多，请 ${result.remainingMillis / 1000} 秒后再试")
            is VerifyResult.WrongPin -> PatternSetupOutcome.Reject("图案错误")
            // 没配置过还能走到这里，说明凭据被外部清掉了：直接放行到设置阶段
            is VerifyResult.NotConfigured -> {
                stage = PatternSetupStage.FIRST
                PatternSetupOutcome.Verified("尚未设置图案，请绘制新的解锁图案")
            }
        }
    }

    private fun reject(message: String): PatternSetupOutcome {
        // 只在「画新图案」阶段回退；验证阶段保持原样，否则会绕过旧图案校验
        if (stage != PatternSetupStage.VERIFY) {
            stage = PatternSetupStage.FIRST
            first = null
        }
        return PatternSetupOutcome.Reject(message)
    }
}
