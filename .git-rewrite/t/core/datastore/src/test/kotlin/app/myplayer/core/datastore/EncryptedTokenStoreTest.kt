package app.myplayer.core.datastore

import app.myplayer.core.security.SecretCipher
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 令牌落盘验收：跨实例可读回；磁盘上不出现明文令牌（M1 DoD 用 adb 检查的文件就是它）。 */
class EncryptedTokenStoreTest {
    private lateinit var dir: File

    private val reversed = object : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(bytes: ByteArray) = bytes.reversedArray()
    }

    @Before fun setup() { dir = Files.createTempDirectory("tokens").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun store(): EncryptedTokenStore {
        val storage = DirByteFileStorage(dir)
        return EncryptedTokenStore(reversed, storage::read, storage::write, storage::delete)
    }

    @Test fun `跨实例读回令牌`() {
        store().put("srv-a", "TOKEN-1234567890")
        assertThat(store().get("srv-a")).isEqualTo("TOKEN-1234567890")
    }

    @Test fun `磁盘上不出现明文令牌`() {
        store().put("srv-a", "TOKEN-1234567890")
        val files = dir.listFiles()!!
        assertThat(files).hasLength(1)
        assertThat(String(files.first().readBytes())).doesNotContain("TOKEN-1234567890")
    }

    @Test fun `remove 后读回为空`() {
        store().put("srv-a", "T")
        store().remove("srv-a")
        assertThat(store().get("srv-a")).isNull()
    }
}
