package app.myplayer.feature.privacy

import app.myplayer.core.model.MutablePrivacyVisibilityPolicy
import app.myplayer.core.security.privacy.AuthGatedPrivacyKek
import app.myplayer.core.security.privacy.AuthorizedPrivacyKey
import app.myplayer.core.security.privacy.PinPrivacyKek
import app.myplayer.core.security.privacy.PrivacyKeyProvider
import app.myplayer.core.security.privacy.PrivacyUnlocker
import app.myplayer.core.security.privacy.PrivacyVaultStore
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * [PrivacySessionManager] 的行为测试。
 *
 * 只覆盖「状态机 + 可见性策略次序」，**不覆盖** BiometricPrompt 的真实授权 ——
 * 那一步依赖系统弹窗，只能在真机验证；这里用 `AuthGatedPrivacyKek` 的 JVM 密钥替身
 * 把「授权后拿到的 Cipher」等价地造出来，验证封装/解封链路本身。
 */
class PrivacySessionManagerTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var unlocker: PrivacyUnlocker
    private lateinit var policy: MutablePrivacyVisibilityPolicy
    private lateinit var manager: PrivacySessionManager

    @Before
    fun setUp() {
        val store = PrivacyVaultStore(File(tempDir.root, "privacy/vault.bin"))
        // 迭代次数压到 1000：真实 210k 次在这里会花掉几秒，而测试只关心链路是否走通
        unlocker = PrivacyUnlocker(store, pinKekFactory = { PinPrivacyKek(it, iterations = 1_000) })
        policy = MutablePrivacyVisibilityPolicy()
        manager = PrivacySessionManager(unlocker, policy)
    }

    @Test
    fun `fresh install starts unconfigured and hidden`() {
        assertThat(manager.state.value).isEqualTo(PrivacyLockState.Unconfigured)
        assertThat(manager.isPrivate).isFalse()
        assertThat(policy.includePrivate()).isFalse()
    }

    @Test
    fun `create unlocks and opens visibility`() {
        manager.create(PIN).getOrThrow()

        assertThat(manager.state.value).isEqualTo(PrivacyLockState.Unlocked)
        assertThat(manager.isPrivate).isTrue()
        assertThat(policy.includePrivate()).isTrue()
    }

    @Test
    fun `lock closes visibility before handing over to cleanup listeners`() {
        manager.create(PIN).getOrThrow()

        var policySeenByListener: Boolean? = null
        manager.addEndListener { policySeenByListener = policy.includePrivate() }

        manager.lock()

        assertThat(manager.state.value).isEqualTo(PrivacyLockState.Locked)
        assertThat(policy.includePrivate()).isFalse()
        // 清理阶段绝对不能再看到隐私数据：策略必须先翻
        assertThat(policySeenByListener).isFalse()
    }

    @Test
    fun `wrong pin keeps normal mode`() {
        manager.create(PIN).getOrThrow()
        manager.lock()

        val result = manager.openWithPin("999999".toCharArray())

        assertThat(result.isFailure).isTrue()
        assertThat(manager.state.value).isEqualTo(PrivacyLockState.Locked)
        assertThat(policy.includePrivate()).isFalse()
    }

    @Test
    fun `correct pin reopens the session`() {
        manager.create(PIN).getOrThrow()
        manager.lock()

        manager.openWithPin(PIN).getOrThrow()

        assertThat(manager.state.value).isEqualTo(PrivacyLockState.Unlocked)
        assertThat(policy.includePrivate()).isTrue()
    }

    @Test
    fun `lock is idempotent and still forces visibility off`() {
        manager.create(PIN).getOrThrow()

        manager.lock()
        manager.lock()

        assertThat(manager.state.value).isEqualTo(PrivacyLockState.Locked)
        assertThat(policy.includePrivate()).isFalse()
    }

    @Test
    fun `lock without a session still turns visibility off`() {
        // 进程重建 / 异常路径下策略可能残留为 true，lock 必须无条件收拾干净
        policy.enter()
        manager.lock()
        assertThat(policy.includePrivate()).isFalse()
    }

    @Test
    fun `create with an authorized key records it and can unlock with it`() {
        val bio = biometricKek()
        val authorized = AuthorizedPrivacyKey(bio, bio.prepareWrap())

        manager.create(PIN, authorizedKeys = listOf(authorized)).getOrThrow()
        assertThat(manager.availableKeks()).containsExactly(PinPrivacyKek.ID, bio.id)
        assertThat(manager.hasKek(bio.id)).isTrue()
        manager.lock()

        // 生产路径就是先 prepareUnwrapFor 拿到待授权 Cipher，再由认证回调换回已授权实例
        val pending = unlocker.prepareUnwrapFor(bio) ?: error("缺少生物钥匙封装")
        manager.openWithAuthorized(AuthorizedPrivacyKey(bio, pending)).getOrThrow()

        assertThat(manager.state.value).isEqualTo(PrivacyLockState.Unlocked)
        assertThat(policy.includePrivate()).isTrue()
    }

    @Test
    fun `open with a key that was never bound fails`() {
        manager.create(PIN).getOrThrow()
        manager.lock()

        val bio = biometricKek()
        // 未绑定的钥匙没有保险箱条目：openWithAuthorized 自己返回 failure，不向上抛
        val result = manager.openWithAuthorized(AuthorizedPrivacyKey(bio, bio.prepareWrap()))

        assertThat(result.isFailure).isTrue()
        assertThat(policy.includePrivate()).isFalse()
    }

    @Test
    fun `reset pin with authorized key keeps data and invalidates the old pin`() {
        val bio = biometricKek()
        manager.create(PIN, authorizedKeys = listOf(AuthorizedPrivacyKey(bio, bio.prepareWrap())))
            .getOrThrow()
        manager.lock()

        val pending = unlocker.prepareUnwrapFor(bio) ?: error("缺少生物钥匙封装")
        manager.resetPinWithAuthorized(AuthorizedPrivacyKey(bio, pending), NEW_PIN).getOrThrow()
        assertThat(manager.state.value).isEqualTo(PrivacyLockState.Unlocked)

        manager.lock()
        assertThat(manager.openWithPin(PIN).isFailure).isTrue()
        manager.openWithPin(NEW_PIN).getOrThrow()
        assertThat(policy.includePrivate()).isTrue()
    }

    @Test
    fun `unbind refuses to remove the last key`() {
        manager.create(PIN).getOrThrow()

        val result = manager.unbindKek(PinPrivacyKek.ID)

        assertThat(result.isFailure).isTrue()
        assertThat(manager.availableKeks()).containsExactly(PinPrivacyKek.ID)
    }

    @Test
    fun `failing listener does not block the rest or the state change`() {
        manager.create(PIN).getOrThrow()
        var secondCalled = false
        manager.addEndListener { throw IllegalStateException("模拟清理失败") }
        manager.addEndListener { secondCalled = true }

        manager.lock()

        assertThat(secondCalled).isTrue()
        assertThat(manager.state.value).isEqualTo(PrivacyLockState.Locked)
        assertThat(policy.includePrivate()).isFalse()
    }

    @Test
    fun `removed listener is not called again`() {
        manager.create(PIN).getOrThrow()
        var calls = 0
        val remove = manager.addEndListener { calls++ }
        remove()

        manager.lock()

        assertThat(calls).isEqualTo(0)
    }

    @Test
    fun `bind authorized key from an open session`() {
        manager.create(PIN).getOrThrow()

        val bio = biometricKek()
        manager.bindAuthorizedKey(AuthorizedPrivacyKey(bio, bio.prepareWrap())).getOrThrow()

        assertThat(manager.hasKek(bio.id)).isTrue()
        manager.lock()

        val pending = unlocker.prepareUnwrapFor(bio) ?: error("缺少生物钥匙封装")
        manager.openWithAuthorized(AuthorizedPrivacyKey(bio, pending)).getOrThrow()
        assertThat(manager.isPrivate).isTrue()
    }

    @Test
    fun `bind without an open session fails`() {
        manager.create(PIN).getOrThrow()
        manager.lock()

        val bio = biometricKek()
        val result = manager.bindAuthorizedKey(AuthorizedPrivacyKey(bio, bio.prepareWrap()))

        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `session data survives a fresh manager instance`() {
        manager.create(PIN).getOrThrow()
        val payload = "secret-notes".toByteArray()

        // 用 DEK 加密一段业务数据，模拟隐私源配置落盘
        val opened = unlocker.unlockWithPin(PIN)
        val blob = opened.encryptPayload(payload, "test:payload")
        opened.close()
        manager.lock()

        // 重建 manager（等价于进程重启）：状态应回到 Locked，数据仍可用
        val restarted = PrivacySessionManager(unlocker, MutablePrivacyVisibilityPolicy())
        assertThat(restarted.state.value).isEqualTo(PrivacyLockState.Locked)
        val reopened = unlocker.unlockWithPin(PIN)
        assertThat(reopened.decryptPayload(blob, "test:payload")).isEqualTo(payload)
        reopened.close()
    }

    // --- 辅助 ---

    private fun biometricKek(): AuthGatedPrivacyKek =
        AuthGatedPrivacyKek("bio", PlainKeyProvider(aesKey()))

    private fun aesKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private class PlainKeyProvider(private val key: SecretKey) : PrivacyKeyProvider {
        override fun get(): SecretKey = key
        override fun exists(): Boolean = true
    }

    private companion object {
        val PIN = "123456".toCharArray()
        val NEW_PIN = "654321".toCharArray()
    }
}
