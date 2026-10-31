package app.myplayer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppLockGateTest {
    @Test fun `locked on cold start when lock enabled`() {
        assertThat(shouldLock(lockEnabled = true, lastUnlockAtMillis = null, nowMillis = 1000, graceMillis = 0)).isTrue()
    }
    @Test fun `within grace period stays unlocked`() {
        assertThat(shouldLock(lockEnabled = true, lastUnlockAtMillis = 1000, nowMillis = 1500, graceMillis = 1000)).isFalse()
    }
    @Test fun `past grace period locks`() {
        assertThat(shouldLock(lockEnabled = true, lastUnlockAtMillis = 1000, nowMillis = 2500, graceMillis = 1000)).isTrue()
    }
    @Test fun `never locks when disabled`() {
        assertThat(shouldLock(lockEnabled = false, lastUnlockAtMillis = null, nowMillis = 0, graceMillis = 0)).isFalse()
    }

    // ---- 冷启动「闪一下解锁界面」回归（用户 2026-10-29 反馈）----

    @Test fun `settings 未读出时不盖解锁页`() {
        // appLockEnabled == null = 设置还在读盘。以前按「锁着」处理 → 关着开关也闪一屏锁屏
        assertThat(shouldShowAppLock(appLockEnabled = null, patternConfigured = true, unlocked = false)).isFalse()
    }

    @Test fun `没开应用锁时不盖解锁页`() {
        assertThat(shouldShowAppLock(appLockEnabled = false, patternConfigured = true, unlocked = false)).isFalse()
    }

    @Test fun `开了应用锁且有图案且锁定时才盖`() {
        assertThat(shouldShowAppLock(appLockEnabled = true, patternConfigured = true, unlocked = false)).isTrue()
    }

    @Test fun `开了应用锁但没录图案不盖`() {
        assertThat(shouldShowAppLock(appLockEnabled = true, patternConfigured = false, unlocked = false)).isFalse()
    }

    @Test fun `已解锁不盖`() {
        assertThat(shouldShowAppLock(appLockEnabled = true, patternConfigured = true, unlocked = true)).isFalse()
    }
}
