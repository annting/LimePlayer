package app.myplayer.feature.lock

import app.myplayer.core.security.VerifyResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PatternSetupControllerTest {

    @Test
    fun `少于四点直接拒绝且仍在第一阶段`() {
        val c = PatternSetupController()
        val r = c.submit(listOf(0, 1, 2))

        assertThat(r).isInstanceOf(PatternSetupOutcome.Reject::class.java)
        assertThat((r as PatternSetupOutcome.Reject).message).contains("至少")
        assertThat(c.stage).isEqualTo(PatternSetupStage.FIRST)
    }

    @Test
    fun `第一遍合格进入确认阶段`() {
        val c = PatternSetupController()
        val r = c.submit(listOf(0, 1, 2, 4))

        assertThat(r).isInstanceOf(PatternSetupOutcome.Confirm::class.java)
        assertThat(c.stage).isEqualTo(PatternSetupStage.CONFIRM)
    }

    @Test
    fun `两遍一致才允许保存`() {
        val c = PatternSetupController()
        c.submit(listOf(0, 1, 2, 4))
        val r = c.submit(listOf(0, 1, 2, 4))

        assertThat(r).isInstanceOf(PatternSetupOutcome.Save::class.java)
        assertThat((r as PatternSetupOutcome.Save).pattern).containsExactly(0, 1, 2, 4).inOrder()
    }

    @Test
    fun `两遍不一致整体重来`() {
        val c = PatternSetupController()
        c.submit(listOf(0, 1, 2, 4))
        val r = c.submit(listOf(0, 1, 5, 7))

        assertThat(r).isInstanceOf(PatternSetupOutcome.Reject::class.java)
        assertThat((r as PatternSetupOutcome.Reject).message).contains("不一致")
        assertThat(c.stage).isEqualTo(PatternSetupStage.FIRST)

        // 重来后必须再经过一次「确认」，不能拿上次的第一遍当基准
        val again = c.submit(listOf(0, 1, 5, 7))
        assertThat(again).isInstanceOf(PatternSetupOutcome.Confirm::class.java)
    }

    @Test
    fun `确认阶段画太短也退回第一阶段`() {
        val c = PatternSetupController()
        c.submit(listOf(0, 1, 2, 4))
        val r = c.submit(listOf(0, 1))

        assertThat(r).isInstanceOf(PatternSetupOutcome.Reject::class.java)
        assertThat(c.stage).isEqualTo(PatternSetupStage.FIRST)
    }

    // ---- 已有图案：先验证旧的（重置流程） ----

    @Test
    fun `已录过图案时初始阶段是验证`() {
        val c = PatternSetupController(verifier = { VerifyResult.Success })
        assertThat(c.stage).isEqualTo(PatternSetupStage.VERIFY)
    }

    @Test
    fun `旧图案错误时停在验证阶段并提示剩余次数`() {
        val c = PatternSetupController(verifier = { VerifyResult.WrongPattern(attemptsLeft = 4) })
        val r = c.submit(listOf(0, 1, 2, 4))

        assertThat(r).isInstanceOf(PatternSetupOutcome.Reject::class.java)
        assertThat((r as PatternSetupOutcome.Reject).message).contains("还可尝试 4 次")
        assertThat(c.stage).isEqualTo(PatternSetupStage.VERIFY)
    }

    @Test
    fun `验证阶段画太短不能绕过旧图案校验`() {
        val c = PatternSetupController(verifier = { VerifyResult.Success })
        val r = c.submit(listOf(0, 1))

        assertThat(r).isInstanceOf(PatternSetupOutcome.Reject::class.java)
        assertThat(c.stage).isEqualTo(PatternSetupStage.VERIFY)
    }

    @Test
    fun `旧图案验证通过后才进入设置新图案`() {
        var expected = listOf(0, 1, 2, 4)
        val c = PatternSetupController(
            verifier = { points ->
                if (points == expected) VerifyResult.Success else VerifyResult.WrongPattern(attemptsLeft = 3)
            },
        )

        // 先画错 → 拒绝
        assertThat(c.submit(listOf(0, 3, 6, 7))).isInstanceOf(PatternSetupOutcome.Reject::class.java)
        // 再画对 → 放行
        val ok = c.submit(expected)
        assertThat(ok).isInstanceOf(PatternSetupOutcome.Verified::class.java)
        assertThat((ok as PatternSetupOutcome.Verified).message).contains("请绘制新的解锁图案")
        assertThat(c.stage).isEqualTo(PatternSetupStage.FIRST)

        // 之后才是正常的「画两遍」
        expected = listOf(6, 7, 8, 5)
        assertThat(c.submit(listOf(6, 7, 8, 5))).isInstanceOf(PatternSetupOutcome.Confirm::class.java)
        assertThat(c.submit(listOf(6, 7, 8, 5))).isInstanceOf(PatternSetupOutcome.Save::class.java)
    }

    @Test
    fun `冷却中给出剩余秒数`() {
        val c = PatternSetupController(verifier = { VerifyResult.Cooldown(remainingMillis = 30_000) })
        val r = c.submit(listOf(0, 1, 2, 4))

        assertThat((r as PatternSetupOutcome.Reject).message).contains("30 秒")
        assertThat(c.stage).isEqualTo(PatternSetupStage.VERIFY)
    }
}
