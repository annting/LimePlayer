package app.myplayer.core.security.privacy

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class PrivacySessionTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var vaultFile: File
    private lateinit var unlocker: PrivacyUnlocker

    @Before
    fun setUp() {
        vaultFile = File(tempDir.root, "privacy/privacy.vault")
        unlocker = PrivacyUnlocker(
            vaultStore = PrivacyVaultStore(vaultFile),
            pinKekFactory = { PinPrivacyKek(it, iterations = 1_000) },
        )
    }

    @Test
    fun `create then unlock with pin`() {
        unlocker.create("123456".toCharArray()).close()
        val session = unlocker.unlockWithPin("123456".toCharArray())
        assertThat(session.isOpen).isTrue()
        session.close()
    }

    @Test
    fun `unlock before configure fails`() {
        expectUnlockFailure { unlocker.unlockWithPin("123456".toCharArray()) }
    }

    @Test
    fun `wrong pin is rejected`() {
        unlocker.create("123456".toCharArray()).close()
        expectUnlockFailure { unlocker.unlockWithPin("654321".toCharArray()) }
    }

    @Test
    fun `closed session rejects further use`() {
        val session = unlocker.create("123456".toCharArray())
        session.close()
        assertThat(session.isOpen).isFalse()
        expectCryptoFailure {
            session.encryptPayload("x".toByteArray(), "privacy-payload:v1:item:1")
        }
    }

    @Test
    fun `payload round trip within session`() {
        unlocker.create("123456".toCharArray()).use { session ->
            val aad = "privacy-payload:v1:item:42"
            val plaintext = "私人视频标题".toByteArray(Charsets.UTF_8)
            val blob = session.encryptPayload(plaintext, aad)
            assertThat(session.decryptPayload(blob, aad)).isEqualTo(plaintext)
        }
    }

    @Test
    fun `reset pin invalidates old pin and keeps data`() {
        val aad = "privacy-payload:v1:item:7"
        val blob = unlocker.create("123456".toCharArray()).use { session ->
            session.encryptPayload("secret data".toByteArray(), aad)
        }

        val currentKek = PinPrivacyKek("123456".toCharArray(), iterations = 1_000)
        val resetSession = unlocker.resetPin(currentKek, "999999".toCharArray())
        resetSession.close()

        expectUnlockFailure { unlocker.unlockWithPin("123456".toCharArray()) }

        unlocker.unlockWithPin("999999".toCharArray()).use { reopened ->
            assertThat(reopened.decryptPayload(blob, aad)).isEqualTo("secret data".toByteArray())
        }
    }

    @Test
    fun `extra kek can unlock`() {
        val bio = FakeKek("bio", ByteArray(PrivacyCrypto.KEY_SIZE) { 3 })
        unlocker.create("123456".toCharArray(), additionalKeks = listOf(bio)).close()

        unlocker.unlockWith(bio).use { session ->
            assertThat(session.isOpen).isTrue()
        }
    }

    @Test
    fun `available keks lists configured keys`() {
        val bio = FakeKek("bio", ByteArray(PrivacyCrypto.KEY_SIZE) { 3 })
        unlocker.create("123456".toCharArray(), additionalKeks = listOf(bio)).close()
        assertThat(unlocker.availableKeks()).containsExactly("pin", "bio")
    }

    @Test
    fun `duplicate kek id is rejected`() {
        val a = FakeKek("bio", ByteArray(PrivacyCrypto.KEY_SIZE) { 1 })
        val b = FakeKek("bio", ByteArray(PrivacyCrypto.KEY_SIZE) { 2 })
        expectUnlockFailure {
            unlocker.create("123456".toCharArray(), additionalKeks = listOf(a, b)).close()
        }
    }

    @Test
    fun `vault file contains no plaintext pin or dek`() {
        unlocker.create("123456".toCharArray()).close()
        val bytes = vaultFile.readBytes()
        val asText = String(bytes, Charsets.ISO_8859_1)
        assertThat(asText).doesNotContain("123456")
    }

    @Test
    fun `tampered vault fails to unlock`() {
        unlocker.create("123456".toCharArray()).close()
        val bytes = vaultFile.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        vaultFile.writeBytes(bytes)
        expectUnlockFailure { unlocker.unlockWithPin("123456".toCharArray()) }
    }

    @Test
    fun `create with an authorized key then unlock with it`() {
        val bio = authKek(AndroidKeystorePrivacyKeys.BIOMETRIC_ID)
        unlocker.create(
            "123456".toCharArray(),
            authorizedKeys = listOf(AuthorizedPrivacyKey(bio, bio.prepareWrap())),
        ).close()

        assertThat(unlocker.availableKeks()).containsExactly("pin", "bio")

        unlocker.unlockWithAuthorized(authorizedUnwrap(bio)).use { session ->
            assertThat(session.isOpen).isTrue()
        }
    }

    @Test
    fun `reset pin with an authorized key keeps the data`() {
        val bio = authKek(AndroidKeystorePrivacyKeys.BIOMETRIC_ID)
        val aad = "privacy-payload:v1:item:9"
        val blob = unlocker.create(
            "123456".toCharArray(),
            authorizedKeys = listOf(AuthorizedPrivacyKey(bio, bio.prepareWrap())),
        ).use { session -> session.encryptPayload("私人数据".toByteArray(), aad) }

        // 忘记 PIN：靠指纹解封 DEK，重设 PIN（方案 Y）
        unlocker.resetPinWithAuthorized(authorizedUnwrap(bio), "998877".toCharArray()).close()

        expectUnlockFailure { unlocker.unlockWithPin("123456".toCharArray()) }
        unlocker.unlockWithPin("998877".toCharArray()).use { reopened ->
            assertThat(reopened.decryptPayload(blob, aad)).isEqualTo("私人数据".toByteArray())
        }
    }

    @Test
    fun `recovery key can be bound later to an open session`() {
        val device = authKek(AndroidKeystorePrivacyKeys.DEVICE_ID)
        val session = unlocker.create("123456".toCharArray())
        assertThat(unlocker.availableKeks()).containsExactly("pin")

        unlocker.bindAuthorizedKey(session, AuthorizedPrivacyKey(device, device.prepareWrap()))
        session.close()

        assertThat(unlocker.availableKeks()).containsExactly("pin", "device")
        unlocker.unlockWithAuthorized(authorizedUnwrap(device)).use { reopened ->
            assertThat(reopened.isOpen).isTrue()
        }
    }

    @Test
    fun `unbinding a key removes it and the last key cannot be removed`() {
        val device = authKek(AndroidKeystorePrivacyKeys.DEVICE_ID)
        unlocker.create(
            "123456".toCharArray(),
            authorizedKeys = listOf(AuthorizedPrivacyKey(device, device.prepareWrap())),
        ).close()

        // 先留住 device 封装，解绑后再拿它尝试解锁
        val sealed = PrivacyVaultStore(vaultFile).read()!!.entry(AndroidKeystorePrivacyKeys.DEVICE_ID)!!

        unlocker.unbindKek(AndroidKeystorePrivacyKeys.DEVICE_ID)
        assertThat(unlocker.availableKeks()).containsExactly("pin")

        val cipher = device.prepareUnwrap(sealed)
        expectUnlockFailure { unlocker.unlockWithAuthorized(AuthorizedPrivacyKey(device, cipher)) }

        expectUnlockFailure { unlocker.unbindKek("pin") }
        assertThat(unlocker.availableKeks()).containsExactly("pin")
    }

    @Test
    fun `authorized key without a vault entry is rejected`() {
        unlocker.create("123456".toCharArray()).close()
        val device = authKek(AndroidKeystorePrivacyKeys.DEVICE_ID)
        // 自造一份 device 封装但不写进 vault：解锁必须因「缺少恢复钥匙」失败
        val blob = device.wrapWith(device.prepareWrap(), ByteArray(PrivacyCrypto.KEY_SIZE))
        val forged = AuthorizedPrivacyKey(device, device.prepareUnwrap(blob))
        expectUnlockFailure { unlocker.unlockWithAuthorized(forged) }
    }

    private fun authKek(id: String): AuthGatedPrivacyKek =
        AuthGatedPrivacyKek(id, PlainKeyProvider(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))

    private fun authorizedUnwrap(kek: AuthGatedPrivacyKek): AuthorizedPrivacyKey {
        val sealed = PrivacyVaultStore(vaultFile).read()!!.entry(kek.id)
            ?: error("保险箱里没有 ${kek.id} 封装")
        return AuthorizedPrivacyKey(kek, kek.prepareUnwrap(sealed))
    }

    private class PlainKeyProvider(private val key: SecretKey) : PrivacyKeyProvider {
        override fun get(): SecretKey = key
    }

    private class FakeKek(
        override val id: String,
        private val key: ByteArray,
    ) : PrivacyKek {
        override fun wrap(dek: ByteArray): ByteArray =
            PrivacyCrypto.encryptKey(key, dek, "privacy-dek:v${PrivacyCrypto.VERSION}:$id")

        override fun unwrap(blob: ByteArray): ByteArray =
            PrivacyCrypto.decryptKey(key, blob, "privacy-dek:v${PrivacyCrypto.VERSION}:$id")
    }

    private fun expectUnlockFailure(block: () -> Unit) {
        try {
            block()
        } catch (_: PrivacyUnlockException) {
            return
        }
        fail("应当抛出 PrivacyUnlockException")
    }

    private fun expectCryptoFailure(block: () -> Unit) {
        try {
            block()
        } catch (_: PrivacyCryptoException) {
            return
        }
        fail("应当抛出 PrivacyCryptoException")
    }
}
