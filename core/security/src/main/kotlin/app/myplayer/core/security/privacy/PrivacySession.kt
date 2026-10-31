package app.myplayer.core.security.privacy

import java.io.Closeable
import javax.crypto.Cipher

/** 隐私解锁失败（未配置 / 缺少恢复钥匙 / PIN 错误 / 数据损坏）。 */
class PrivacyUnlockException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 已通过系统认证的恢复钥匙：`BiometricPrompt` 成功回调里取回的
 * `AuthenticationResult.cryptoObject.cipher` + 对应的 [PrivacyAuthKek]。
 *
 * **一个实例只能用一次**：认证门控的 Keystore 密钥是「按次授权」的，
 * 用完（或认证失败）就必须重新 [PrivacyAuthKek.prepareWrap] / [PrivacyAuthKek.prepareUnwrap]
 * 再弹一次认证框。
 */
class AuthorizedPrivacyKey(
    val kek: PrivacyAuthKek,
    val cipher: Cipher,
)

/**
 * 已解锁的隐私会话：持有 DEK，[close] 时归零。
 *
 * 生命周期由上层（`MainActivity` / `ProcessLifecycleOwner`）绑定前台：
 * 退后台、手动退出、进程回收到期都必须 [close]，并配合播放/任务/连接/缓存清理。
 */
class PrivacySession internal constructor(
    private val dek: ByteArray,
) : Closeable {

    @Volatile
    private var closed = false

    val isOpen: Boolean get() = !closed

    fun encryptPayload(plaintext: ByteArray, aad: String): ByteArray =
        PrivacyCrypto.encryptPayload(dekOrThrow(), plaintext, aad)

    fun decryptPayload(blob: ByteArray, aad: String): ByteArray =
        PrivacyCrypto.decryptPayload(dekOrThrow(), blob, aad)

    /** 在 DEK 上执行操作（供同模块「重设 PIN」重新封装，不把 DEK 引用暴露给业务层）。 */
    internal fun <T> useDek(block: (ByteArray) -> T): T = block(dekOrThrow())

    override fun close() {
        if (closed) return
        dek.fill(0)
        closed = true
    }

    private fun dekOrThrow(): ByteArray {
        if (closed) throw PrivacyCryptoException("隐私会话已关闭")
        return dek
    }
}

/**
 * 隐私模式的创建 / 解锁 / 重设 PIN 入口。
 *
 * **不存储 PIN 哈希**：PIN 是否正确由「KEK-PIN 能否解封 DEK」判定，解封失败即拒绝。
 */
class PrivacyUnlocker(
    private val vaultStore: PrivacyVaultStore,
    private val pinKekFactory: (CharArray) -> PrivacyKek = { PinPrivacyKek(it) },
    private val defaultExtraKeks: List<PrivacyKek> = emptyList(),
) {
    fun isConfigured(): Boolean = vaultStore.isConfigured()

    /** 已配置的恢复钥匙标识（`pin` / `bio` / `device`）。 */
    fun availableKeks(): Set<String> = vaultStore.read()?.kekIds ?: emptySet()

    fun hasKek(kekId: String): Boolean = kekId in availableKeks()

    /**
     * 认证钥匙对应的 DEK 封装（密文本身不敏感，但只在隐私模块内部用）。
     *
     * 认证门控的 Keystore 密钥必须先按这段密文里的 IV 初始化 Cipher、再交给
     * `BiometricPrompt` 授权 —— 所以要用它去 [prepareUnwrapFor]。
     */
    fun sealedEntry(kekId: String): ByteArray? = vaultStore.read()?.entry(kekId)

    /**
     * 为一把认证钥匙准备「解封用」的 Cipher（还没授权，不能用）。
     *
     * 返回 null = 保险箱里没有这把钥匙的条目（未绑定）。
     * 封装损坏会抛 [PrivacyCryptoException] —— 那是真错误，调用方应提示而不是静默回退。
     */
    fun prepareUnwrapFor(kek: PrivacyAuthKek): Cipher? =
        sealedEntry(kek.id)?.let { kek.prepareUnwrap(it) }

    /**
     * 首次设置：生成随机 DEK，用 PIN + 额外 KEK（生物/设备）分别封装后原子落盘。
     * 成功后返回已解锁会话，DEK 由 [PrivacySession] 持有。
     *
     * [authorizedKeys] 是已通过 `BiometricPrompt` 认证的钥匙（KEK‑BIO / KEK‑DEVICE）——
     * 首次设置时若用户勾选「用指纹快速进入」，先建 Keystore 密钥、弹一次认证，
     * 再把授权后的 Cipher 传进来，与 PIN 一起封同一份 DEK。
     */
    fun create(
        pin: CharArray,
        additionalKeks: List<PrivacyKek> = emptyList(),
        authorizedKeys: List<AuthorizedPrivacyKey> = emptyList(),
    ): PrivacySession {
        PinPrivacyKek.validate(pin)
        val dek = PrivacyCrypto.randomBytes(PrivacyCrypto.KEY_SIZE)
        val keks = buildList {
            add(pinKekFactory(pin))
            addAll(defaultExtraKeks)
            addAll(additionalKeks)
        }
        return try {
            val entries = LinkedHashMap<String, ByteArray>(keks.size + authorizedKeys.size)
            keks.forEach { kek ->
                if (entries.containsKey(kek.id)) {
                    throw PrivacyUnlockException("重复的恢复钥匙: ${kek.id}")
                }
                entries[kek.id] = kek.wrap(dek)
            }
            authorizedKeys.forEach { authorized ->
                if (entries.containsKey(authorized.kek.id)) {
                    throw PrivacyUnlockException("重复的恢复钥匙: ${authorized.kek.id}")
                }
                entries[authorized.kek.id] = authorized.kek.wrapWith(authorized.cipher, dek)
            }
            vaultStore.write(SealedVault(PrivacyVaultCodec.FORMAT_VERSION, entries))
            PrivacySession(dek)
        } catch (e: Exception) {
            dek.fill(0)
            throw if (e is PrivacyUnlockException) e else PrivacyUnlockException("创建隐私模式失败", e)
        }
    }

    fun unlockWithPin(pin: CharArray): PrivacySession = unlockWith(pinKekFactory(pin))

    fun unlockWith(kek: PrivacyKek): PrivacySession {
        val vault = vaultStore.read() ?: throw PrivacyUnlockException("尚未配置隐私模式")
        val sealed = vault.entry(kek.id) ?: throw PrivacyUnlockException("缺少恢复钥匙: ${kek.id}")
        val dek = try {
            kek.unwrap(sealed)
        } catch (e: Exception) {
            throw PrivacyUnlockException("隐私验证失败", e)
        }
        if (dek.size != PrivacyCrypto.KEY_SIZE) {
            dek.fill(0)
            throw PrivacyUnlockException("DEK 长度非法: ${dek.size}")
        }
        return PrivacySession(dek)
    }

    /**
     * 用已授权的认证钥匙（指纹 / 设备密码）解锁。
     *
     * 调用前必须已经：[AndroidKeystorePrivacyKeys.ensureBiometricKey] →
     * [PrivacyAuthKek.prepareUnwrap] → `BiometricPrompt.authenticate(CryptoObject(cipher))`。
     */
    fun unlockWithAuthorized(authorized: AuthorizedPrivacyKey): PrivacySession {
        val vault = vaultStore.read() ?: throw PrivacyUnlockException("尚未配置隐私模式")
        val sealed = vault.entry(authorized.kek.id)
            ?: throw PrivacyUnlockException("缺少恢复钥匙: ${authorized.kek.id}")
        val dek = try {
            authorized.kek.unwrapWith(authorized.cipher, sealed)
        } catch (e: Exception) {
            throw PrivacyUnlockException("隐私验证失败", e)
        }
        if (dek.size != PrivacyCrypto.KEY_SIZE) {
            dek.fill(0)
            throw PrivacyUnlockException("DEK 长度非法: ${dek.size}")
        }
        return PrivacySession(dek)
    }

    /**
     * 在**已打开的会话**里绑定 / 刷新一把认证钥匙（生物或设备密码）。
     *
     * 用途：首次没开指纹、后来在隐私设置里补绑；或换指纹导致旧钥匙失效后重绑。
     * 覆盖同 id 的旧条目 —— 旧封装一并作废。
     */
    fun bindAuthorizedKey(session: PrivacySession, authorized: AuthorizedPrivacyKey) {
        if (!session.isOpen) throw PrivacyUnlockException("隐私会话已关闭")
        val vault = vaultStore.read() ?: throw PrivacyUnlockException("尚未配置隐私模式")
        val entries = LinkedHashMap(vault.entries)
        entries[authorized.kek.id] = try {
            session.useDek { dek -> authorized.kek.wrapWith(authorized.cipher, dek) }
        } catch (e: Exception) {
            throw if (e is PrivacyUnlockException) e else PrivacyUnlockException("绑定恢复钥匙失败", e)
        }
        vaultStore.write(SealedVault(vault.version, entries))
    }

    /**
     * 移除一把恢复钥匙（解绑指纹 / 作废设备密码恢复）。
     *
     * 拒绝移除最后一把 —— 否则保险箱再没有任何钥匙可解，等同于丢数据。
     * 调用方须确保此刻会话已解锁（是机主在操作）；本方法不重复验证。
     */
    fun unbindKek(kekId: String) {
        val vault = vaultStore.read() ?: throw PrivacyUnlockException("尚未配置隐私模式")
        if (!vault.entries.containsKey(kekId)) {
            throw PrivacyUnlockException("没有这把恢复钥匙: $kekId")
        }
        val remaining = LinkedHashMap(vault.entries).apply { remove(kekId) }
        if (remaining.isEmpty()) {
            throw PrivacyUnlockException("不能移除最后一把恢复钥匙")
        }
        vaultStore.write(SealedVault(vault.version, remaining))
    }

    /**
     * 重设 PIN：先用任一可用 KEK 解封 DEK，再用新 PIN 重新封装。
     * vault 只保留一份 PIN 条目，覆盖写即**删除旧 PIN 封装**。
     */
    fun resetPin(currentKek: PrivacyKek, newPin: CharArray): PrivacySession {
        PinPrivacyKek.validate(newPin)
        val session = unlockWith(currentKek)
        return try {
            val vault = vaultStore.read() ?: throw PrivacyUnlockException("尚未配置隐私模式")
            val entries = LinkedHashMap(vault.entries)
            entries[PinPrivacyKek.ID] = session.useDek { dek -> pinKekFactory(newPin).wrap(dek) }
            vaultStore.write(SealedVault(vault.version, entries))
            session
        } catch (e: Exception) {
            session.close()
            throw if (e is PrivacyUnlockException) e else PrivacyUnlockException("重设隐私 PIN 失败", e)
        }
    }

    /**
     * 方案 Y 的恢复主路径：**忘了 PIN** → 用设备密码钥匙（或指纹）解封 DEK → 重设 PIN。
     *
     * 数据不丢：DEK 本身没变，只是换了一把 PIN 封装，旧 PIN 封装被覆盖删除。
     * 这也是「设备密码重置」的真正落地方式 —— 设备密码并不直接当 KEK 用，
     * 而是通过认证门控的 Keystore 密钥间接解封。
     */
    fun resetPinWithAuthorized(authorized: AuthorizedPrivacyKey, newPin: CharArray): PrivacySession {
        PinPrivacyKek.validate(newPin)
        val session = unlockWithAuthorized(authorized)
        return try {
            val vault = vaultStore.read() ?: throw PrivacyUnlockException("尚未配置隐私模式")
            val entries = LinkedHashMap(vault.entries)
            entries[PinPrivacyKek.ID] = session.useDek { dek -> pinKekFactory(newPin).wrap(dek) }
            vaultStore.write(SealedVault(vault.version, entries))
            session
        } catch (e: Exception) {
            session.close()
            throw if (e is PrivacyUnlockException) e else PrivacyUnlockException("重设隐私 PIN 失败", e)
        }
    }
}
