package app.myplayer.core.security.privacy

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 隐私数据的**加密区**：一个 scope 一个文件，载荷用当前隐私会话的 DEK 加密。
 *
 * 与 `PrivacyVaultStore`（存 DEK 封装）的分工：
 * - `PrivacyVaultStore`：放「钥匙」——DEK 的三份封装，会话未开也要能读（否则没法解锁）；
 * - 本类：放「内容」——私有源的 baseUrl / userId / 令牌等。**会话未开时读不出任何明文**，
 *   这既是加密，也是「普通模式下连地址都拿不到」的保证（`docs/隐私模式方案.md` §4.1）。
 *
 * AAD 绑定 scope（`privacy-secret:v1:<scope>`）：两个不同对象/用途的密文不能互换，
 * 否则把 A 服务器的密文改名成 B 就能读出 B 的配置。
 *
 * 文件必须落在 `noBackupFilesDir`（生产装配在 `AppModule` 里固定如此）：
 * 参与云备份/换机迁移会违反方案 §A–F 的 D 条。
 */
class PrivacySecretVault(
    private val dir: File,
    private val sessionProvider: () -> PrivacySession?,
) {

    fun isOpen(): Boolean = sessionProvider()?.isOpen == true

    fun exists(scope: String): Boolean = fileOf(scope).exists()

    /**
     * 读取并解密。返回 null = 文件不存在 **或** 会话未打开（两者对调用方同义：现在拿不到）。
     * 解密失败（被篡改 / 换了 DEK）也返回 null —— 调用方按「没有这份数据」处理，
     * 不把密文内容或内部异常抛到 UI 上。
     */
    fun read(scope: String): ByteArray? {
        val session = sessionProvider()?.takeIf { it.isOpen } ?: return null
        val file = fileOf(scope)
        if (!file.exists()) return null
        return runCatching { session.decryptPayload(file.readBytes(), aad(scope)) }.getOrNull()
    }

    /** 写入（覆盖）。会话未打开直接抛错 —— 绝不允许退化成明文落盘。 */
    fun write(scope: String, plaintext: ByteArray) {
        val session = sessionProvider()?.takeIf { it.isOpen }
            ?: throw PrivacyCryptoException("隐私会话未打开，拒绝写入加密区")
        writeAtomic(fileOf(scope), session.encryptPayload(plaintext, aad(scope)))
    }

    fun delete(scope: String) {
        val file = fileOf(scope)
        if (file.exists() && !file.delete()) {
            throw PrivacyCryptoException("删除隐私数据失败: ${file.path}")
        }
    }

    private fun aad(scope: String): String = "$AAD_PREFIX$scope"

    /**
     * scope → 文件名。scope 里只允许 `[A-Za-z0-9._-]`（serverId 是 UUID、itemId 是 Base64 URL safe），
     * 其余字符一律替换掉，杜绝 `../` 这类路径穿越。
     */
    private fun fileOf(scope: String): File {
        require(scope.isNotBlank() && scope.length <= 128) { "非法的隐私数据标识: $scope" }
        val safe = buildString(scope.length) {
            scope.forEach { ch ->
                append(if (ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-') ch else '_')
            }
        }
        return File(dir, "$safe.bin")
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val directory = target.parentFile
            ?: throw PrivacyCryptoException("隐私加密区路径非法: ${target.path}")
        if (!directory.exists() && !directory.mkdirs()) {
            throw PrivacyCryptoException("无法创建隐私加密区目录: ${directory.path}")
        }
        val temp = File.createTempFile(target.name + ".", ".tmp", directory)
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(bytes)
                stream.flush()
                stream.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: PrivacyCryptoException) {
            throw e
        } catch (e: Exception) {
            throw PrivacyCryptoException("写入隐私加密区失败", e)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private companion object {
        const val AAD_PREFIX = "privacy-secret:v1:"
    }
}
