package app.myplayer.core.security

import app.myplayer.core.security.VerifyResult.*
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PatternCredentialStoreTest {
    private val store = PatternCredentialStore(
        hasher = SecretHasher(iterations = 1_000),
        storage = InMemoryCredentialStorage(),
    )

    @Test fun `not configured before save`() {
        assertThat(store.isConfigured()).isFalse()
    }

    @Test fun `correct pattern verifies`() {
        store.savePattern(pattern = listOf(0, 1, 2, 4), backupPin = "1234")
        assertThat(store.verifyPattern(listOf(0, 1, 2, 4))).isInstanceOf(Success::class.java)
    }

    @Test fun `wrong pattern reports remaining attempts`() {
        store.savePattern(pattern = listOf(0, 1, 5, 7), backupPin = null)
        val r = store.verifyPattern(listOf(8, 6, 3, 0))
        assertThat(r).isInstanceOf(WrongPattern::class.java)
        assertThat((r as WrongPattern).attemptsLeft).isEqualTo(4)
    }

    @Test fun `cooldown starts after five failures`() {
        store.savePattern(pattern = listOf(0, 3, 6, 7), backupPin = null)
        repeat(5) { store.verifyPattern(listOf(1, 4, 7, 8)) }
        assertThat(store.verifyPattern(listOf(1, 4, 7, 8))).isInstanceOf(Cooldown::class.java)
    }

    @Test fun `backup pin verifies`() {
        store.savePattern(pattern = listOf(0, 1, 5, 7), backupPin = "4321")
        assertThat(store.verifyPin("4321")).isInstanceOf(Success::class.java)
    }

    @Test fun `weak pattern encoded is rejected on save`() {
        val r = runCatching { store.savePattern(pattern = listOf(0, 3, 6), backupPin = null) }
        assertThat(r.isFailure).isTrue()
    }

    @Test fun `backup pin outside 4 to 8 digits is rejected on save`() {
        val tooShort = runCatching { store.savePattern(pattern = listOf(0, 1, 2, 4), backupPin = "123") }
        val tooLong = runCatching { store.savePattern(pattern = listOf(0, 1, 2, 4), backupPin = "123456789") }
        assertThat(tooShort.isFailure).isTrue()
        assertThat(tooLong.isFailure).isTrue()
    }

    @Test fun `pattern codec joins indices`() {
        assertThat(PatternCodec.encode(listOf(0, 1, 2, 4))).isEqualTo("0-1-2-4")
    }
}
