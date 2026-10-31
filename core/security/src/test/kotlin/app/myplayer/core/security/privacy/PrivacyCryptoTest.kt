package app.myplayer.core.security.privacy

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.fail
import org.junit.Test

class PrivacyCryptoTest {

    private val dek = ByteArray(PrivacyCrypto.KEY_SIZE) { it.toByte() }
    private val aad = "privacy-payload:v1:item:abc"

    @Test
    fun `payload round trip`() {
        val plaintext = "隐私标题 / secret path".toByteArray(Charsets.UTF_8)
        val blob = PrivacyCrypto.encryptPayload(dek, plaintext, aad)
        assertThat(PrivacyCrypto.decryptPayload(dek, blob, aad)).isEqualTo(plaintext)
    }

    @Test
    fun `same plaintext produces different ciphertext because of random iv`() {
        val plaintext = "same".toByteArray()
        val first = PrivacyCrypto.encryptPayload(dek, plaintext, aad)
        val second = PrivacyCrypto.encryptPayload(dek, plaintext, aad)
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val blob = PrivacyCrypto.encryptPayload(dek, "payload".toByteArray(), aad)
        blob[blob.size / 2] = (blob[blob.size / 2].toInt() xor 0x01).toByte()
        expectCryptoFailure { PrivacyCrypto.decryptPayload(dek, blob, aad) }
    }

    @Test
    fun `tampered iv is rejected`() {
        val blob = PrivacyCrypto.encryptPayload(dek, "payload".toByteArray(), aad)
        blob[2] = (blob[2].toInt() xor 0x01).toByte()
        expectCryptoFailure { PrivacyCrypto.decryptPayload(dek, blob, aad) }
    }

    @Test
    fun `wrong aad is rejected`() {
        val blob = PrivacyCrypto.encryptPayload(dek, "payload".toByteArray(), aad)
        expectCryptoFailure {
            PrivacyCrypto.decryptPayload(dek, blob, "privacy-payload:v1:item:other")
        }
    }

    @Test
    fun `wrong dek is rejected`() {
        val blob = PrivacyCrypto.encryptPayload(dek, "payload".toByteArray(), aad)
        expectCryptoFailure {
            PrivacyCrypto.decryptPayload(ByteArray(PrivacyCrypto.KEY_SIZE) { 9 }, blob, aad)
        }
    }

    @Test
    fun `unsupported version is rejected`() {
        val blob = PrivacyCrypto.encryptPayload(dek, "payload".toByteArray(), aad)
        blob[0] = 2
        val error = expectCryptoFailure { PrivacyCrypto.decryptPayload(dek, blob, aad) }
        assertThat(error.message).contains("版本")
    }

    @Test
    fun `truncated blob is rejected`() {
        expectCryptoFailure { PrivacyCrypto.decryptPayload(dek, ByteArray(8), aad) }
    }

    @Test
    fun `hkdf is deterministic and separates info domains`() {
        val ikm = ByteArray(32) { 1 }
        val salt = ByteArray(16) { 2 }
        val enc = PrivacyCrypto.hkdf(ikm, salt, "enc".toByteArray(), 32)
        val encAgain = PrivacyCrypto.hkdf(ikm, salt, "enc".toByteArray(), 32)
        val mac = PrivacyCrypto.hkdf(ikm, salt, "mac".toByteArray(), 32)
        assertThat(enc).isEqualTo(encAgain)
        assertThat(enc).isNotEqualTo(mac)
    }

    @Test
    fun `hkdf treats empty salt as zeros but still differs from pin as salt`() {
        val ikm = "123456".toByteArray()
        val withEmptySalt = PrivacyCrypto.hkdf(ikm, ByteArray(0), "info".toByteArray(), 32)
        val withPinAsSalt = PrivacyCrypto.hkdf(ikm, ikm, "info".toByteArray(), 32)
        assertThat(withEmptySalt).isNotEqualTo(withPinAsSalt)
    }

    @Test
    fun `key wrapping round trip`() {
        val kek = ByteArray(PrivacyCrypto.KEY_SIZE) { 5 }
        val wrapped = PrivacyCrypto.encryptKey(kek, dek, "privacy-dek:v1:pin")
        assertThat(PrivacyCrypto.decryptKey(kek, wrapped, "privacy-dek:v1:pin")).isEqualTo(dek)
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
}
