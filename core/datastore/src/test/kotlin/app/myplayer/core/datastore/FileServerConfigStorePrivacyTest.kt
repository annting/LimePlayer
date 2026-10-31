package app.myplayer.core.datastore

import app.myplayer.core.model.MutablePrivacyVisibilityPolicy
import app.myplayer.core.model.ShowPrivate
import app.myplayer.core.model.SourceType
import app.myplayer.core.security.SecretCipher
import app.myplayer.core.security.privacy.PinPrivacyKek
import app.myplayer.core.security.privacy.PrivacySecretVault
import app.myplayer.core.security.privacy.PrivacySession
import app.myplayer.core.security.privacy.PrivacyUnlocker
import app.myplayer.core.security.privacy.PrivacyVaultStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 档一隐私源的**敏感字段搬家**验收（`docs/隐私模式方案.md` §4.1 / 决策 21）。
 *
 * `servers.bin` 只留壳（id / sourceType / name / isPrivate），
 * 地址与账号进 DEK 加密区；普通模式连地址都拿不到，因此不可能发起连接（方案 A）。
 */
class FileServerConfigStorePrivacyTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var file: File
    private lateinit var secretsDir: File
    private lateinit var unlocker: PrivacyUnlocker
    private lateinit var secrets: PrivacyServerSecretStore

    private var session: PrivacySession? = null

    private val reversed = object : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(cipher: ByteArray) = cipher.reversedArray()
    }

    @Before
    fun setUp() {
        dir = File(tempDir.root, "cfg").apply { mkdirs() }
        file = File(dir, "servers.bin")
        secretsDir = File(tempDir.root, "privacy/secrets")
        unlocker = PrivacyUnlocker(
            vaultStore = PrivacyVaultStore(File(tempDir.root, "privacy/privacy.vault")),
            pinKekFactory = { PinPrivacyKek(it, iterations = 1_000) },
        )
        secrets = PrivacyServerSecretStore(PrivacySecretVault(secretsDir) { session })
    }

    @After
    fun tearDown() {
        session?.close()
        dir.deleteRecursively()
    }

    // ---- 落盘形态 ----

    @Test
    fun `私有源的地址与账号不落 servers_bin`() = runTest {
        open()
        store().upsert(privateConfig())

        val json = diskJson()
        assertThat(json).doesNotContain("10.0.0.2")
        assertThat(json).doesNotContain("user-abc-123")
        // 壳还在，名字也还在 —— 列表要能显示出「有一台隐私源」
        assertThat(json).contains("\"baseUrl\":\"\"")
        assertThat(json).contains("私密库")
        assertThat(secrets.read("p")!!.baseUrl).isEqualTo("http://10.0.0.2:8096")
    }

    @Test
    fun `没有加密区时也不把私有地址写进 servers_bin`() = runTest {
        // 双保险：即使装配漏了 secrets，write() 也要把私有源的地址削掉
        FileServerConfigStore(file, reversed, ShowPrivate, secrets = null).upsert(privateConfig())

        assertThat(diskJson()).doesNotContain("10.0.0.2")
    }

    // ---- 水合 ----

    @Test
    fun `普通模式看不见 进隐私模式才水合出地址`() = runTest {
        open()
        val privacy = MutablePrivacyVisibilityPolicy()
        val store = FileServerConfigStore(file, reversed, privacy, secrets)
        store.upsert(ServerConfig("n", SourceType.EMBY, "正常", "http://10.0.0.3:8096"))
        store.upsert(privateConfig())

        assertThat(store.observeAll().first().map { it.name }).containsExactly("正常")

        privacy.enter()
        val visible = store.observeAll().first()
        assertThat(visible.map { it.name }).containsExactly("正常", "私密库")
        assertThat(visible.first { it.id == "p" }.baseUrl).isEqualTo("http://10.0.0.2:8096")
    }

    @Test
    fun `会话关闭时隐私模式也只能看到空壳`() = runTest {
        open()
        store().upsert(privateConfig())
        session!!.close()
        session = null

        val visible = FileServerConfigStore(file, reversed, ShowPrivate, secrets).observeAll().first()
        assertThat(visible).hasSize(1)
        assertThat(visible.first().baseUrl).isEmpty()
        assertThat(visible.first().userId).isNull()
    }

    @Test
    fun `只改名字不会丢掉加密区里的地址和令牌`() = runTest {
        open()
        val store = store()
        store.upsert(privateConfig())
        secrets.write("p", secrets.read("p")!!.copy(token = "keep-token"))

        store.upsert(ServerConfig("p", SourceType.EMBY, "改名了", baseUrl = "", isPrivate = true))

        val hydrated = FileServerConfigStore(file, reversed, ShowPrivate, secrets).observeAll().first().first()
        assertThat(hydrated.name).isEqualTo("改名了")
        assertThat(hydrated.baseUrl).isEqualTo("http://10.0.0.2:8096")
        assertThat(hydrated.userId).isEqualTo("user-abc-123")
        assertThat(secrets.read("p")!!.token).isEqualTo("keep-token")
    }

    // ---- 老数据迁移 ----

    @Test
    fun `老明文地址迁进加密区且幂等`() = runTest {
        val legacy = """[{"id":"p","sourceType":"EMBY","name":"私密库",""" +
            """"baseUrl":"http://10.0.0.2:8096","userId":"user-abc-123","isPrivate":true}]"""
        file.writeBytes(reversed.encrypt(legacy.toByteArray()))
        open()

        val store = store()
        assertThat(store.migratePrivateSecrets()).isEqualTo(1)

        assertThat(diskJson()).doesNotContain("10.0.0.2")
        assertThat(secrets.read("p")!!.userId).isEqualTo("user-abc-123")

        val hydrated = FileServerConfigStore(file, reversed, ShowPrivate, secrets).observeAll().first().first()
        assertThat(hydrated.baseUrl).isEqualTo("http://10.0.0.2:8096")

        // 再跑一次没有可迁的了
        assertThat(store.migratePrivateSecrets()).isEqualTo(0)
    }

    @Test
    fun `会话未开时迁移不动文件也不丢数据`() = runTest {
        val legacy = """[{"id":"p","sourceType":"EMBY","name":"私密库",""" +
            """"baseUrl":"http://10.0.0.2:8096","isPrivate":true}]"""
        file.writeBytes(reversed.encrypt(legacy.toByteArray()))

        assertThat(store().migratePrivateSecrets()).isEqualTo(0)

        assertThat(diskJson()).contains("10.0.0.2")
        assertThat(secrets.has("p")).isFalse()
    }

    // ---- 删除 ----

    @Test
    fun `删除服务器一并清掉加密条目`() = runTest {
        open()
        val store = store()
        store.upsert(privateConfig())
        assertThat(secrets.has("p")).isTrue()

        store.remove("p")

        assertThat(secrets.has("p")).isFalse()
        assertThat(FileServerConfigStore(file, reversed, ShowPrivate, secrets).observeAll().first()).isEmpty()
    }

    // ---- helpers ----

    private fun open(pin: CharArray = "123456".toCharArray()): PrivacySession =
        unlocker.create(pin).also { session = it }

    private fun store() = FileServerConfigStore(file, reversed, ShowPrivate, secrets)

    private fun diskJson(): String = String(reversed.decrypt(file.readBytes()))

    private fun privateConfig() = ServerConfig(
        id = "p",
        sourceType = SourceType.EMBY,
        name = "私密库",
        baseUrl = "http://10.0.0.2:8096",
        userId = "user-abc-123",
        isPrivate = true,
    )
}
