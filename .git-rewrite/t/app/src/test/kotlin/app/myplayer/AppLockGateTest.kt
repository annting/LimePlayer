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
}
