package app.myplayer.core.datastore

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
}
