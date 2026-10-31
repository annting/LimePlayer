package app.myplayer.core.security.privacy

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class AuthGatedPrivacyKekTest {

    @Test
    fun `wrap then unwrap returns the same dek`() {
        val kek = kek("bio")
        val dek = PrivacyCrypto.randomBytes(PrivacyCrypto.KEY_SIZE)

        val blob = kek.wrapWith(kek.prepareWrap(), dek)
        val restored = kek.unwrapWith(kek.prepareUnwrap(blob), blob)

        assertThat(restored).isEqualTo(dek)
    }

    @Test
    fun `each wrap uses a fresh iv`() {
        val kek = kek("bio")
        val dek = ByteArray(PrivacyCrypto.KEY_SIZE) { 7 }
        val a = kek.wrapWith(kek.prepareWrap(), dek)
        val b = kek.wrapWith(kek.prepareWrap(), dek)
        assertThat(a).isNotEqualTo(b)
        // 前两字节是 version + ivLen
        assertThat(a.copyOfRange(2, 14)).isNotEqualTo(b.copyOfRange(2, 14))
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val kek = kek("bio")
        val blob = kek.wrapWith(kek.prepareWrap(), ByteArray(PrivacyCrypto.KEY_SIZE))
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0x01).toByte()
        expectFailure { kek.unwrapWith(kek.prepareUnwrap(blob), blob) }
    }

    @Test
    fun `tampered iv is rejected`() {
        val kek = kek("bio")
        val blob = kek.wrapWith(kek.prepareWrap(), ByteArray(PrivacyCrypto.KEY_SIZE))
        blob[3] = (blob[3].toInt() xor 0x01).toByte()
        expectFailure { kek.unwrapWith(kek.prepareUnwrap(blob), blob) }
    }

    @Test
    fun `different key cannot unwrap`() {
        val writer = kek("bio", key())
        val reader = kek("bio", key())
        val blob = writer.wrapWith(writer.prepareWrap(), ByteArray(PrivacyCrypto.KEY_SIZE))
        expectFailure { reader.unwrapWith(reader.prepareUnwrap(blob), blob) }
    }

    @Test
    fun `aad binds the kek id so blobs cannot move between keys`() {
        // 同一个底层密钥，但 id 不同：AAA 不同 ⇒ 解封必须失败
        val shared = key()
        val bio = kek(AndroidKeystorePrivacyKeys.BIOMETRIC_ID, shared)
        val device = kek(AndroidKeystorePrivacyKeys.DEVICE_ID, shared)

        val blob = bio.wrapWith(bio.prepareWrap(), ByteArray(PrivacyCrypto.KEY_SIZE))
        expectFailure { device.unwrapWith(device.prepareUnwrap(blob), blob) }
    }

    @Test
    fun `truncated blob fails during preparation before touching the key`() {
        // 长度不足时应在弹认证框之前就失败，不打扰用户
        val kek = kek("bio", present = false)
        expectFailure { kek.prepareUnwrap(ByteArray(12)) }
    }

    @Test
    fun `unknown format version is rejected`() {
        val kek = kek("bio")
        val blob = kek.wrapWith(kek.prepareWrap(), ByteArray(PrivacyCrypto.KEY_SIZE))
        blob[0] = 9
        expectFailure { kek.prepareUnwrap(blob) }
    }

    @Test
    fun `missing key reports unavailable and fails loudly`() {
        val kek = kek("bio", present = false)
        assertThat(kek.isAvailable()).isFalse()
        expectFailure { kek.prepareWrap() }
    }

    @Test
    fun `delete key clears availability`() {
        val provider = PlainKeyProvider(key())
        val kek = AuthGatedPrivacyKek("bio", provider)
        assertThat(kek.isAvailable()).isTrue()

        kek.deleteKey()

        assertThat(kek.isAvailable()).isFalse()
    }

    @Test
    fun `blob carries no raw dek bytes`() {
        val dek = ByteArray(PrivacyCrypto.KEY_SIZE) { 0x42 }
        val kek = kek("bio")
        val blob = kek.wrapWith(kek.prepareWrap(), dek)
        assertThat(String(blob, Charsets.ISO_8859_1)).doesNotContain(String(dek, Charsets.ISO_8859_1))
    }

    private fun kek(
        id: String,
        key: SecretKey = key(),
        present: Boolean = true,
    ): AuthGatedPrivacyKek = AuthGatedPrivacyKek(id, PlainKeyProvider(key, present))

    private fun key(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private class PlainKeyProvider(
        private val key: SecretKey,
        var present: Boolean = true,
    ) : PrivacyKeyProvider {
        override fun get(): SecretKey {
            if (!present) throw PrivacyCryptoException("认证密钥不存在")
            return key
        }

        override fun exists(): Boolean = present

        override fun delete() {
            present = false
        }
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
        } catch (_: PrivacyCryptoException) {
            return
        }
        fail("应当抛出 PrivacyCryptoException")
    }
}
