package app.myplayer.core.security.privacy

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.fail
import org.junit.Test

class PinPrivacyKekTest {

    private val pin = "123456".toCharArray()
    private val dek = ByteArray(PrivacyCrypto.KEY_SIZE) { 7 }

    private fun kek(value: CharArray = pin) = PinPrivacyKek(value, iterations = 1_000)

    @Test
    fun `correct pin unwraps dek`() {
        val blob = kek().wrap(dek)
        assertThat(kek().unwrap(blob)).isEqualTo(dek)
    }

    @Test
    fun `wrong pin fails to unwrap`() {
        val blob = kek().wrap(dek)
        expectCryptoFailure { kek("654321".toCharArray()).unwrap(blob) }
    }

    @Test
    fun `same pin produces different blobs because salt is random`() {
        val first = kek().wrap(dek)
        val second = kek().wrap(dek)
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `salt is not the pin`() {
        // 若把 PIN 当盐，相同 PIN 的封装会完全一致；随机盐保证不一致。
        val blobs = List(8) { kek().wrap(dek) }
        assertThat(blobs.toSet().size).isEqualTo(8)
    }

    @Test
    fun `tampered blob fails`() {
        val blob = kek().wrap(dek)
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        expectCryptoFailure { kek().unwrap(blob) }
    }

    @Test
    fun `truncated blob fails`() {
        expectCryptoFailure { kek().unwrap(ByteArray(4)) }
    }

    @Test
    fun `blob does not contain pin plaintext`() {
        val blob = kek().wrap(dek)
        val asText = String(blob, Charsets.ISO_8859_1)
        assertThat(asText).doesNotContain("123456")
    }

    @Test
    fun `pin shorter than six digits is rejected`() {
        expectIllegalArgument { PinPrivacyKek("12345".toCharArray(), iterations = 1_000) }
    }

    @Test
    fun `pin longer than twelve digits is rejected`() {
        expectIllegalArgument { PinPrivacyKek("1234567890123".toCharArray(), iterations = 1_000) }
    }

    @Test
    fun `non digit pin is rejected`() {
        expectIllegalArgument { PinPrivacyKek("12a456".toCharArray(), iterations = 1_000) }
    }

    @Test
    fun `stored iteration count is honored so params can be recalibrated later`() {
        val wrappedWithLowIterations = PinPrivacyKek(pin, iterations = 1_000).wrap(dek)
        // 用默认迭代次数的实例解封也能成功：迭代次数来自封装自身。
        assertThat(PinPrivacyKek(pin, iterations = 5_000).unwrap(wrappedWithLowIterations)).isEqualTo(dek)
    }

    @Test
    fun `clear releases pin copy without breaking existing blobs`() {
        val kek = kek()
        val blob = kek.wrap(dek)
        kek.clear()
        // 已生成的封装不依赖内存中的 PIN 副本；新实例仍可解封。
        assertThat(kek().unwrap(blob)).isEqualTo(dek)
    }

    private fun expectCryptoFailure(block: () -> Unit): PrivacyCryptoException {
        try {
            block()
        } catch (e: PrivacyCryptoException) {
            return e
        }
        fail("应当抛出 PrivacyCryptoException")
        throw AssertionError("unreachable")
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        fail("应当抛出 IllegalArgumentException")
    }
}
