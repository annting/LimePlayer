package app.myplayer.core.security.privacy

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 加密区（内容层）的验收：`docs/隐私模式方案.md` §4.1。
 *
 * 三条底线，缺一条就等于隐私源地址泄露：
 * 1. 没有会话就读不出明文（不是「读出来再判断要不要用」）；
 * 2. 没有会话就写不进去（不存在「先明文放着以后迁」的退化路径）；
 * 3. 密文与 scope 绑定，改名不能读出别人。
 */
class PrivacySecretVaultTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var secretsDir: File
    private lateinit var unlocker: PrivacyUnlocker

    /** 当前会话槽位：`null` 模拟「隐私模式已退出 / 进程刚起」。 */
    private var session: PrivacySession? = null

    private lateinit var vault: PrivacySecretVault

    @Before
    fun setUp() {
        dir = File(tempDir.root, "privacy")
        secretsDir = File(dir, "secrets")
        unlocker = PrivacyUnlocker(
            vaultStore = PrivacyVaultStore(File(dir, "privacy.vault")),
            pinKekFactory = { PinPrivacyKek(it, iterations = 1_000) },
        )
        vault = PrivacySecretVault(secretsDir) { session }
    }

    @After
    fun tearDown() {
        session?.close()
    }

    // ---- 基本读写 ----

    @Test
    fun `写入后同一会话可读回`() {
        open()
        val plaintext = """{"baseUrl":"http://10.0.0.2:8096"}""".toByteArray()
        vault.write("server-a", plaintext)
        assertThat(vault.read("server-a")).isEqualTo(plaintext)
    }

    @Test
    fun `覆盖写读到的是最新值`() {
        open()
        vault.write("server-a", "first".toByteArray())
        vault.write("server-a", "second".toByteArray())
        assertThat(String(vault.read("server-a")!!)).isEqualTo("second")
        assertThat(files()).hasSize(1)
    }

    @Test
    fun `重新解锁的新会话仍能解开老密文`() {
        open()
        vault.write("server-a", "keep me".toByteArray())
        session!!.close()

        // 同一 PIN 重新解封出同一个 DEK —— 跨会话可读，否则每次退出隐私就丢配置
        session = unlocker.unlockWithPin("123456".toCharArray())
        assertThat(String(vault.read("server-a")!!)).isEqualTo("keep me")
    }

    // ---- 会话门控 ----

    @Test
    fun `会话关闭后读不出任何明文`() {
        open()
        vault.write("server-a", "secret".toByteArray())
        session!!.close()

        assertThat(vault.isOpen()).isFalse()
        assertThat(vault.read("server-a")).isNull()
        // 文件还在（数据没被删），只是现在解不开
        assertThat(vault.exists("server-a")).isTrue()
    }

    @Test
    fun `从未打开会话时读返回空`() {
        assertThat(vault.read("server-a")).isNull()
        assertThat(vault.exists("server-a")).isFalse()
    }

    @Test
    fun `会话关闭时拒绝写入`() {
        open()
        vault.write("server-a", "secret".toByteArray())
        session!!.close()

        expectCryptoFailure { vault.write("server-b", "leak".toByteArray()) }
        assertThat(vault.exists("server-b")).isFalse()
    }

    // ---- AAD 绑定 / 篡改 ----

    @Test
    fun `密文改名到别的 scope 解不开`() {
        open()
        vault.write("server-a", "a-secret".toByteArray())
        // 模拟攻击者把 A 的密文改名成 B：AAD 里带 scope，解密必须失败
        File(secretsDir, "server-a.bin").copyTo(File(secretsDir, "server-b.bin"))

        assertThat(vault.read("server-b")).isNull()
        assertThat(String(vault.read("server-a")!!)).isEqualTo("a-secret")
    }

    @Test
    fun `被篡改的密文按缺失处理而不是抛异常`() {
        open()
        vault.write("server-a", "tamper me".toByteArray())
        val file = File(secretsDir, "server-a.bin")
        val bytes = file.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)

        assertThat(vault.read("server-a")).isNull()
    }

    // ---- 落盘形态 ----

    @Test
    fun `加密区文件不含明文`() {
        open()
        vault.write("server-a", """{"baseUrl":"http://10.0.0.2:8096","token":"abc123"}""".toByteArray())
        val asText = String(File(secretsDir, "server-a.bin").readBytes(), Charsets.ISO_8859_1)
        assertThat(asText).doesNotContain("10.0.0.2")
        assertThat(asText).doesNotContain("abc123")
    }

    @Test
    fun `scope 里的路径穿越字符被清洗`() {
        open()
        vault.write("../../evil", "x".toByteArray())

        assertThat(files()).hasSize(1)
        assertThat(files().first().parentFile).isEqualTo(secretsDir)
        assertThat(File(dir, "evil.bin").exists()).isFalse()
        assertThat(File(tempDir.root, "evil.bin").exists()).isFalse()
    }

    @Test
    fun `空 scope 被拒绝`() {
        open()
        expectIllegalArgument { vault.write("  ", "x".toByteArray()) }
    }

    @Test
    fun `delete 后文件消失`() {
        open()
        vault.write("server-a", "x".toByteArray())
        vault.delete("server-a")
        assertThat(vault.exists("server-a")).isFalse()
        assertThat(files()).isEmpty()
    }

    // ---- helpers ----

    private fun open(pin: CharArray = "123456".toCharArray()): PrivacySession =
        unlocker.create(pin).also { session = it }

    private fun files(): List<File> = secretsDir.listFiles()?.toList().orEmpty()

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
