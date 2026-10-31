package app.myplayer.core.security.privacy

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PrivacyVaultTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun `codec round trip`() {
        val vault = SealedVault(
            version = PrivacyVaultCodec.FORMAT_VERSION,
            entries = linkedMapOf(
                "pin" to byteArrayOf(1, 2, 3),
                "bio" to byteArrayOf(4, 5, 6),
            ),
        )
        val decoded = PrivacyVaultCodec.decode(PrivacyVaultCodec.encode(vault))
        assertThat(decoded.version).isEqualTo(PrivacyVaultCodec.FORMAT_VERSION)
        assertThat(decoded.entry("pin")).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(decoded.entry("bio")).isEqualTo(byteArrayOf(4, 5, 6))
        assertThat(decoded.kekIds).containsExactly("pin", "bio")
    }

    @Test
    fun `bad magic is rejected`() {
        val bytes = PrivacyVaultCodec.encode(SealedVault(1, mapOf("pin" to byteArrayOf(1))))
        bytes[0] = 'X'.code.toByte()
        expectCryptoFailure { PrivacyVaultCodec.decode(bytes) }
    }

    @Test
    fun `unsupported version is rejected`() {
        val bytes = PrivacyVaultCodec.encode(SealedVault(1, mapOf("pin" to byteArrayOf(1))))
        bytes[4] = 9
        expectCryptoFailure { PrivacyVaultCodec.decode(bytes) }
    }

    @Test
    fun `truncated vault is rejected`() {
        val bytes = PrivacyVaultCodec.encode(SealedVault(1, mapOf("pin" to byteArrayOf(1, 2, 3))))
        expectCryptoFailure { PrivacyVaultCodec.decode(bytes.copyOfRange(0, bytes.size - 1)) }
    }

    @Test
    fun `empty vault is rejected on encode`() {
        expectIllegalArgument { PrivacyVaultCodec.encode(SealedVault(1, emptyMap())) }
    }

    @Test
    fun `store write read delete`() {
        val file = File(tempDir.root, "privacy/privacy.vault")
        val store = PrivacyVaultStore(file)

        assertThat(store.isConfigured()).isFalse()
        assertThat(store.read()).isNull()

        store.write(SealedVault(1, mapOf("pin" to byteArrayOf(1, 2, 3))))
        assertThat(store.isConfigured()).isTrue()
        assertThat(store.read()!!.entry("pin")).isEqualTo(byteArrayOf(1, 2, 3))

        store.delete()
        assertThat(store.isConfigured()).isFalse()
    }

    @Test
    fun `overwrite keeps only latest content`() {
        val store = PrivacyVaultStore(File(tempDir.root, "privacy.vault"))
        store.write(SealedVault(1, mapOf("pin" to byteArrayOf(1))))
        store.write(SealedVault(1, mapOf("pin" to byteArrayOf(2), "device" to byteArrayOf(3))))

        val read = store.read()!!
        assertThat(read.entry("pin")).isEqualTo(byteArrayOf(2))
        assertThat(read.entry("device")).isEqualTo(byteArrayOf(3))
    }

    @Test
    fun `no temp files left behind after write`() {
        val directory = File(tempDir.root, "privacy")
        val store = PrivacyVaultStore(File(directory, "privacy.vault"))
        repeat(5) { store.write(SealedVault(1, mapOf("pin" to byteArrayOf(it.toByte())))) }

        val leftovers = directory.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertThat(leftovers).isEmpty()
    }

    private fun expectCryptoFailure(block: () -> Unit) {
        try {
            block()
        } catch (_: PrivacyCryptoException) {
            return
        }
        fail("应当抛出 PrivacyCryptoException")
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
