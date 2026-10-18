package app.myplayer.core.security

import java.util.concurrent.TimeUnit

sealed interface VerifyResult {
    data object Success : VerifyResult
    data class WrongPattern(val attemptsLeft: Int) : VerifyResult
    data object WrongPin : VerifyResult
    data class Cooldown(val remainingMillis: Long) : VerifyResult
    data object NotConfigured : VerifyResult
}

/**
 * 图案 / PIN 凭据的状态机（docs/02-技术规范.md §6 应用锁）：
 * - 保存时拒绝弱图案；PIN 限定 4–8 位；
 * - 连续失败 5 次起进入递增冷却：30s → 1min → 5min（失败状态持久化在记录里）；
 * - 成功即清零失败计数。
 */
class PatternCredentialStore(
    private val hasher: SecretHasher,
    private val storage: CredentialStorage,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    fun isConfigured(): Boolean = storage.read() != null

    fun savePattern(pattern: List<Int>, backupPin: String?) {
        require(!WeakPatternRules.isWeak(pattern)) { "图案过于简单" }
        require(backupPin == null || backupPin.length in 4..8) { "PIN 需为 4–8 位" }
        val salt = hasher.newSalt()
        val pinSalt = backupPin?.let { hasher.newSalt() }
        storage.write(
            CredentialRecord(
                patternHash = hasher.derive(PatternCodec.encode(pattern), salt).toBase64(),
                patternSalt = salt.toBase64(),
                backupPinHash = backupPin?.let { hasher.derive(it, pinSalt!!).toBase64() },
                backupPinSalt = pinSalt?.toBase64(),
            )
        )
    }

    fun verifyPattern(pattern: List<Int>): VerifyResult = verify(PatternCodec.encode(pattern), isPin = false)

    fun verifyPin(pin: String): VerifyResult = verify(pin, isPin = true)

    private fun verify(secret: String, isPin: Boolean): VerifyResult {
        val record = storage.read() ?: return VerifyResult.NotConfigured
        cooldownOf(record)?.let { return it }
        val saltB64 = if (isPin) record.backupPinSalt else record.patternSalt
        val hashB64 = if (isPin) record.backupPinHash else record.patternHash
        if (saltB64 == null || hashB64 == null) return VerifyResult.WrongPin

        return if (hasher.verify(secret, saltB64.fromBase64(), hashB64.fromBase64())) {
            storage.write(record.copy(failedAttempts = 0, lastFailedAtMillis = 0L))
            VerifyResult.Success
        } else {
            val attempts = record.failedAttempts + 1
            storage.write(record.copy(failedAttempts = attempts, lastFailedAtMillis = nowMillis()))
            if (attempts >= MAX_ATTEMPTS) {
                VerifyResult.Cooldown(cooldownMillis(attempts))
            } else if (isPin) {
                VerifyResult.WrongPin
            } else {
                VerifyResult.WrongPattern(attemptsLeft = MAX_ATTEMPTS - attempts)
            }
        }
    }

    private fun cooldownOf(record: CredentialRecord): VerifyResult.Cooldown? {
        if (record.failedAttempts < MAX_ATTEMPTS) return null
        val elapsed = nowMillis() - record.lastFailedAtMillis
        val remaining = cooldownMillis(record.failedAttempts) - elapsed
        return if (remaining > 0) VerifyResult.Cooldown(remaining) else null
    }

    private fun cooldownMillis(attempts: Int): Long = when {
        attempts >= 8 -> TimeUnit.MINUTES.toMillis(5)
        attempts >= 7 -> TimeUnit.MINUTES.toMillis(1)
        else -> TimeUnit.SECONDS.toMillis(30)
    }

    companion object {
        const val MAX_ATTEMPTS = 5
    }
}
