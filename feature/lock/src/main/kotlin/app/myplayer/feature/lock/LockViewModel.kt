package app.myplayer.feature.lock

import app.myplayer.core.security.PatternCredentialStore
import app.myplayer.core.security.VerifyResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class LockStatus { IDLE, DRAWING, VERIFYING, ERROR, UNLOCKED, COOLDOWN }

data class LockUiState(
    val status: LockStatus = LockStatus.IDLE,
    val selected: List<Int> = emptyList(),
    val message: String? = null,
    val cooldownMillis: Long = 0L,
)

class LockViewModel(private val credentials: PatternCredentialStore) {
    private val _state = MutableStateFlow(LockUiState())
    val state: StateFlow<LockUiState> = _state

    fun onPointTouched(index: Int) {
        val current = _state.value
        if (current.status == LockStatus.COOLDOWN || current.status == LockStatus.VERIFYING) return
        if (index in current.selected) return
        _state.value = current.copy(status = LockStatus.DRAWING, selected = current.selected + index, message = null)
    }

    fun onDrawEnd() {
        val selected = _state.value.selected
        if (selected.size < MIN_POINTS) {
            _state.value = LockUiState(status = LockStatus.IDLE)
            return
        }
        _state.value = _state.value.copy(status = LockStatus.VERIFYING)
        when (val result = credentials.verifyPattern(selected)) {
            is VerifyResult.Success -> _state.value = LockUiState(status = LockStatus.UNLOCKED)
            is VerifyResult.WrongPattern -> _state.value = LockUiState(
                status = LockStatus.ERROR, message = "图案错误，还可尝试 ${result.attemptsLeft} 次",
            )
            is VerifyResult.Cooldown -> _state.value = LockUiState(
                status = LockStatus.COOLDOWN, cooldownMillis = result.remainingMillis,
                message = "尝试过多，请稍后重试",
            )
            is VerifyResult.WrongPin -> _state.value = LockUiState(status = LockStatus.ERROR, message = "PIN 错误")
            is VerifyResult.NotConfigured -> _state.value = LockUiState(status = LockStatus.UNLOCKED)
        }
    }

    fun onErrorAnimationFinished() {
        if (_state.value.status == LockStatus.ERROR) _state.value = LockUiState(status = LockStatus.IDLE)
    }

    companion object { const val MIN_POINTS = 4 }
}
