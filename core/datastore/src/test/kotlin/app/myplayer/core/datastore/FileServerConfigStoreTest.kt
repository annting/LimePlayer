package app.myplayer.core.datastore

import app.myplayer.core.model.MutablePrivacyVisibilityPolicy
import app.myplayer.core.model.ShowPrivate
import app.myplayer.core.model.SourceType
import app.myplayer.core.security.SecretCipher
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 落盘版的验收：跨实例（= 模拟重启）可读回，且磁盘上不出现明文。 */
class FileServerConfigStoreTest {
    private lateinit var dir: File
    private lateinit var file: File

    /** 测试用假密码器：可逆且能一眼看出是否落盘 —— 真机用 KeystoreSecretCipher（任务 1-4）。 */
    private val reversed = object : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(bytes: ByteArray) = bytes.reversedArray()
    }

    @Before fun setup() {
        dir = Files.createTempDirectory("cfg").toFile()
        file = File(dir, "servers.bin")
    }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun store() = FileServerConfigStore(file, reversed)

    @Test fun `落盘后新实例仍能读回`() = runTest {
        store().upsert(ServerConfig("a", SourceType.EMBY, "Home", "http://10.0.0.2:8096"))
        val reopened = store().observeAll().first()
        assertThat(reopened).hasSize(1)
        assertThat(reopened.first().baseUrl).isEqualTo("http://10.0.0.2:8096")
    }

    @Test fun `磁盘上不出现明文地址`() = runTest {
        store().upsert(ServerConfig("a", SourceType.EMBY, "Home", "http://10.0.0.2:8096"))
        assertThat(String(file.readBytes())).doesNotContain("10.0.0.2")
    }

    // ---- 档一：isPrivate 落盘 + 读过滤（docs/隐私模式方案.md §三 / §八）----

    @Test fun `隐私标记跨实例保留`() = runTest {
        store().upsert(ServerConfig("p", SourceType.SMB, "私密", "smb://nas/secret", isPrivate = true))
        // 普通模式默认看不到 —— 用 ShowPrivate 重新打开才能读回它
        val visible = FileServerConfigStore(file, reversed, ShowPrivate).observeAll().first()
        assertThat(visible).hasSize(1)
        assertThat(visible.first().isPrivate).isTrue()
        assertThat(visible.first().name).isEqualTo("私密")
    }

    @Test fun `普通模式读不出隐私源 进隐私才显出来`() = runTest {
        val privacy = MutablePrivacyVisibilityPolicy()
        val s = FileServerConfigStore(file, reversed, privacy)
        s.upsert(ServerConfig("n", SourceType.EMBY, "正常", "http://n"))
        s.upsert(ServerConfig("p", SourceType.SMB, "隐私", "smb://nas/secret", isPrivate = true))

        assertThat(s.observeAll().first().map { it.name }).containsExactly("正常")

        privacy.enter()
        assertThat(s.observeAll().first().map { it.name }).containsExactly("正常", "隐私")
    }

    @Test fun `老配置文件没有 isPrivate 键时按可见处理`() = runTest {
        // 模拟旧版本写下的 servers.bin：JSON 里根本没有 isPrivate 字段。
        // 靠 ServerConfigRecord 的默认值 + ignoreUnknownKeys，读出来必须是 false（可见），不能崩、不能丢。
        val legacy = """[{"id":"a","sourceType":"EMBY","name":"Home","baseUrl":"http://10.0.0.2:8096"}]"""
        file.writeBytes(reversed.encrypt(legacy.toByteArray()))

        val loaded = store().observeAll().first()
        assertThat(loaded).hasSize(1)
        assertThat(loaded.first().isPrivate).isFalse()
        assertThat(loaded.first().baseUrl).isEqualTo("http://10.0.0.2:8096")
    }
}
