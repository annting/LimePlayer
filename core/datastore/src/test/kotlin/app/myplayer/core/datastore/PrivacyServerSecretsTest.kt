package app.myplayer.core.datastore

import app.myplayer.core.security.privacy.PinPrivacyKek
import app.myplayer.core.security.privacy.PrivacyCryptoException
import app.myplayer.core.security.privacy.PrivacySecretVault
import app.myplayer.core.security.privacy.PrivacySession
import app.myplayer.core.security.privacy.PrivacyUnlocker
import app.myplayer.core.security.privacy.PrivacyVaultStore
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 私有源敏感字段 + 令牌分流的验收（`docs/隐私模式方案.md` §4.1 / 决策 21）。
 *
 * 重点不是「能存能取」，而是**普通模式拿不到**：
 * 会话未开时既读不出地址/令牌，也写不进去（不许退化成明文）。
 */
class PrivacyServerSecretsTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var secretsDir: File
    private lateinit var unlocker: PrivacyUnlocker
    private lateinit var secrets: PrivacyServerSecretStore
    private lateinit var delegate: InMemoryTokenStore
    private lateinit var tokenStore: TokenStore

    private var session: PrivacySession? = null

    @Before
    fun setUp() {
        dir = File(tempDir.root, "privacy")
        secretsDir = File(dir, "secrets")
        unlocker = PrivacyUnlocker(
            vaultStore = PrivacyVaultStore(File(dir, "privacy.vault")),
            pinKekFactory = { PinPrivacyKek(it, iterations = 1_000) },
        )
        secrets = PrivacyServerSecretStore(PrivacySecretVault(secretsDir) { session })
        delegate = InMemoryTokenStore()
        tokenStore = PrivacyAwareTokenStore(delegate, secrets)
    }

    @After
    fun tearDown() {
        session?.close()
    }

    // ---- 加密区门面 ----

    @Test
    fun `敏感字段写入后读回`() {
        open()
        secrets.write("s1", ServerSecrets(baseUrl = "http://10.0.0.2:8096", userId = "u1", token = "t1"))

        val back = secrets.read("s1")
        assertThat(back).isEqualTo(ServerSecrets("http://10.0.0.2:8096", "u1", "t1"))
    }

    @Test
    fun `会话关闭后读不出敏感字段`() {
        open()
        secrets.write("s1", ServerSecrets(baseUrl = "http://10.0.0.2:8096"))
        session!!.close()

        assertThat(secrets.read("s1")).isNull()
        // 条目还在：退出隐私不等于删数据
        assertThat(secrets.has("s1")).isTrue()
    }

    @Test
    fun `会话关闭时拒绝写入敏感字段`() {
        expectCryptoFailure {
            secrets.write("s1", ServerSecrets(baseUrl = "http://10.0.0.2:8096"))
        }
        assertThat(secrets.has("s1")).isFalse()
    }

    @Test
    fun `has 不看会话 只看条目是否存在`() {
        open()
        secrets.write("s1", ServerSecrets(baseUrl = "http://x"))
        assertThat(secrets.has("s1")).isTrue()
        assertThat(secrets.has("s2")).isFalse()
    }

    @Test
    fun `delete 后条目消失`() {
        open()
        secrets.write("s1", ServerSecrets(baseUrl = "http://x"))
        secrets.delete("s1")
        assertThat(secrets.has("s1")).isFalse()
    }

    // ---- 令牌分流 ----

    @Test
    fun `私有源令牌写进加密区 普通区不留痕`() {
        open()
        secrets.write("s1", ServerSecrets(baseUrl = "http://10.0.0.2:8096"))

        tokenStore.put("s1", "private-token")

        assertThat(secrets.read("s1")!!.token).isEqualTo("private-token")
        assertThat(delegate.get("s1")).isNull()
        assertThat(tokenStore.get("s1")).isEqualTo("private-token")
    }

    @Test
    fun `普通源令牌仍走原存储`() {
        open()
        tokenStore.put("n1", "normal-token")

        assertThat(delegate.get("n1")).isEqualTo("normal-token")
        assertThat(secrets.has("n1")).isFalse()
        assertThat(tokenStore.get("n1")).isEqualTo("normal-token")
    }

    @Test
    fun `私有源令牌在普通模式下读不出`() {
        open()
        secrets.write("s1", ServerSecrets(baseUrl = "http://x"))
        tokenStore.put("s1", "private-token")

        session!!.close()
        session = null

        assertThat(tokenStore.get("s1")).isNull()
    }

    @Test
    fun `会话关闭时私有源写令牌被拒绝`() {
        open()
        secrets.write("s1", ServerSecrets(baseUrl = "http://x"))
        session!!.close()
        session = null

        expectCryptoFailure { tokenStore.put("s1", "leak") }
        assertThat(delegate.get("s1")).isNull()
    }

    @Test
    fun `remove 清掉私有源令牌但不删掉服务器条目`() {
        open()
        secrets.write("s1", ServerSecrets(baseUrl = "http://x"))
        tokenStore.put("s1", "private-token")

        tokenStore.remove("s1")

        assertThat(secrets.read("s1")!!.token).isNull()
        assertThat(secrets.read("s1")!!.baseUrl).isEqualTo("http://x")
        assertThat(delegate.get("s1")).isNull()
        assertThat(tokenStore.get("s1")).isNull()
    }

    // ---- helpers ----

    private fun open(pin: CharArray = "123456".toCharArray()): PrivacySession =
        unlocker.create(pin).also { session = it }

    private fun expectCryptoFailure(block: () -> Unit) {
        try {
            block()
        } catch (_: PrivacyCryptoException) {
            return
        }
        fail("应当抛出 PrivacyCryptoException")
    }
}
