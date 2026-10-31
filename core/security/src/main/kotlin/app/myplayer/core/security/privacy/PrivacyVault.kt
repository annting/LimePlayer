package app.myplayer.core.security.privacy

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 隐私保险箱内容：`kekId -> 用该 KEK 封装的 DEK`。 */
class SealedVault(
    val version: Int,
    val entries: Map<String, ByteArray>,
) {
    fun entry(kekId: String): ByteArray? = entries[kekId]

    val kekIds: Set<String> get() = entries.keys
}

/**
 * 隐私保险箱的二进制编解码。
 *
 * 格式：`magic("LPPM",4) || version(1) || count(1) || [idLen(1) || id || sealedLen(4) || sealed]*`。
 * 单条 DEK 封装自身带版本与 HMAC 认证；外层只负责结构与边界校验。
 */
object PrivacyVaultCodec {
    const val FORMAT_VERSION: Int = 1

    private val MAGIC = byteArrayOf('L'.code.toByte(), 'P'.code.toByte(), 'P'.code.toByte(), 'M'.code.toByte())
    private const val MAX_ENTRIES = 16
    private const val MAX_ID_LENGTH = 32

    fun encode(vault: SealedVault): ByteArray {
        require(vault.entries.isNotEmpty()) { "隐私保险箱不能为空" }
        require(vault.entries.size <= MAX_ENTRIES) { "隐私保险箱条目过多（上限 $MAX_ENTRIES）" }

        var size = MAGIC.size + 2
        vault.entries.forEach { (id, sealed) ->
            require(id.isNotBlank() && id.length <= MAX_ID_LENGTH) { "非法的恢复钥匙标识: $id" }
            require(sealed.isNotEmpty()) { "空的 DEK 封装: $id" }
            size += 1 + id.toByteArray(Charsets.UTF_8).size + 4 + sealed.size
        }

        val buffer = ByteBuffer.allocate(size)
        buffer.put(MAGIC)
        buffer.put(FORMAT_VERSION.toByte())
        buffer.put(vault.entries.size.toByte())
        vault.entries.forEach { (id, sealed) ->
            val idBytes = id.toByteArray(Charsets.UTF_8)
            buffer.put(idBytes.size.toByte())
            buffer.put(idBytes)
            buffer.putInt(sealed.size)
            buffer.put(sealed)
        }
        return buffer.array()
    }

    fun decode(bytes: ByteArray): SealedVault {
        if (bytes.size < MAGIC.size + 2) {
            throw PrivacyCryptoException("隐私保险箱格式非法（长度不足）")
        }
        val buffer = ByteBuffer.wrap(bytes)
        val magic = ByteArray(MAGIC.size).also { buffer.get(it) }
        if (!magic.contentEquals(MAGIC)) {
            throw PrivacyCryptoException("隐私保险箱格式非法（魔数不符）")
        }
        val version = buffer.get().toInt() and 0xFF
        if (version != FORMAT_VERSION) {
            throw PrivacyCryptoException("不支持的隐私保险箱版本: $version")
        }
        val count = buffer.get().toInt() and 0xFF
        if (count !in 1..MAX_ENTRIES) {
            throw PrivacyCryptoException("隐私保险箱格式非法（条目数异常: $count）")
        }

        val entries = LinkedHashMap<String, ByteArray>(count)
        repeat(count) {
            if (buffer.remaining() < 1) throw PrivacyCryptoException("隐私保险箱格式非法（截断）")
            val idLength = buffer.get().toInt() and 0xFF
            if (idLength !in 1..MAX_ID_LENGTH || buffer.remaining() < idLength + 4) {
                throw PrivacyCryptoException("隐私保险箱格式非法（标识长度异常）")
            }
            val id = ByteArray(idLength).also { buffer.get(it) }.toString(Charsets.UTF_8)
            val sealedLength = buffer.getInt()
            if (sealedLength <= 0 || sealedLength > buffer.remaining()) {
                throw PrivacyCryptoException("隐私保险箱格式非法（封装长度异常）")
            }
            val sealed = ByteArray(sealedLength).also { buffer.get(it) }
            if (entries.put(id, sealed) != null) {
                throw PrivacyCryptoException("隐私保险箱含重复标识: $id")
            }
        }
        return SealedVault(version, entries)
    }
}

/**
 * 隐私保险箱文件存储：**原子写**（临时文件 + fsync + 原子 move），避免掉电留下半截文件。
 *
 * 生产装配必须把 [file] 指向 `noBackupFilesDir`，使其不参与 Android 自动备份与设备迁移。
 */
class PrivacyVaultStore(private val file: File) {

    fun isConfigured(): Boolean = file.exists()

    fun read(): SealedVault? =
        if (file.exists()) PrivacyVaultCodec.decode(file.readBytes()) else null

    fun write(vault: SealedVault) {
        val bytes = PrivacyVaultCodec.encode(vault)
        val directory = file.parentFile
            ?: throw PrivacyCryptoException("隐私保险箱路径非法: ${file.path}")
        if (!directory.exists() && !directory.mkdirs()) {
            throw PrivacyCryptoException("无法创建隐私保险箱目录: ${directory.path}")
        }

        val temp = File.createTempFile(file.name + ".", ".tmp", directory)
        try {
            FileOutputStream(temp).use { stream ->
                stream.write(bytes)
                stream.flush()
                stream.fd.sync()
            }
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: PrivacyCryptoException) {
            throw e
        } catch (e: Exception) {
            throw PrivacyCryptoException("写入隐私保险箱失败", e)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    fun delete() {
        if (file.exists() && !file.delete()) {
            throw PrivacyCryptoException("删除隐私保险箱失败: ${file.path}")
        }
    }
}
