package app.myplayer.feature.lock

import app.myplayer.core.security.InMemoryCredentialStorage
import app.myplayer.core.security.PatternCredentialStore
import app.myplayer.core.security.SecretHasher
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LockViewModelTest {
    private fun vm(): LockViewModel {
        val store = PatternCredentialStore(SecretHasher(iterations = 1_000), InMemoryCredentialStorage())
        store.savePattern(listOf(0, 1, 2, 4), backupPin = "1234")
        return LockViewModel(store)
    }

    private fun LockViewModel.drawWrong() {
        onPointTouched(8); onPointTouched(6); onPointTouched(3); onPointTouched(0)
        onDrawEnd()
    }

    @Test fun `drawing then correct pattern reaches unlocked`() = runTest {
        val vm = vm()
        vm.onPointTouched(0); vm.onPointTouched(1); vm.onPointTouched(2); vm.onPointTouched(4)
        vm.onDrawEnd()
        assertThat(vm.state.value.status).isEqualTo(LockStatus.UNLOCKED)
    }

    @Test fun `wrong pattern shows error then resets`() = runTest {
        val vm = vm()
        vm.drawWrong()
        assertThat(vm.state.value.status).isEqualTo(LockStatus.ERROR)
        assertThat(vm.state.value.message).contains("还可尝试 4 次")
        vm.onErrorAnimationFinished()
        assertThat(vm.state.value.status).isEqualTo(LockStatus.IDLE)
    }

    @Test fun `fewer than four points does not verify`() = runTest {
        val vm = vm()
        vm.onPointTouched(0); vm.onPointTouched(1)
        vm.onDrawEnd()
        assertThat(vm.state.value.status).isEqualTo(LockStatus.IDLE)
    }

    @Test fun `连续五次错误后进入冷却且触摸被忽略`() = runTest {
        val vm = vm()
        repeat(4) {
            vm.drawWrong()
            vm.onErrorAnimationFinished()
            assertThat(vm.state.value.status).isEqualTo(LockStatus.IDLE)
        }
        vm.drawWrong()   // 第 5 次失败 → 冷却
        assertThat(vm.state.value.status).isEqualTo(LockStatus.COOLDOWN)
        assertThat(vm.state.value.cooldownMillis).isGreaterThan(0L)

        vm.onPointTouched(0)   // 冷却期间输入被忽略
        assertThat(vm.state.value.selected).isEmpty()
    }
}
