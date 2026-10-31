package app.myplayer.feature.privacy

import app.myplayer.core.model.MutablePrivacyVisibilityPolicy
import app.myplayer.core.security.privacy.AuthGatedPrivacyKek
import app.myplayer.core.security.privacy.AuthorizedPrivacyKey
import app.myplayer.core.security.privacy.PinPrivacyKek
import app.myplayer.core.security.privacy.PrivacyAuthCapability
import app.myplayer.core.security.privacy.PrivacyAuthKek
import app.myplayer.core.security.privacy.PrivacyKeyProvider
import app.myplayer.core.security.privacy.PrivacyUnlocker
import app.myplayer.core.security.privacy.PrivacyVaultStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * [PrivacyAccessCoordinator] 的分支覆盖。
 *
 * 认证弹框用 [FakeAuthenticator] 替身，但**封装本身是真的** —— 替身拿真
 * `AuthGatedPrivacyKek` 走 `prepareWrap` / `prepareUnwrap`，所以「指纹钥匙能不能真的
 * 解封保险箱」这条链路依然被验证，只有「系统有没有弹框」被跳过。
 */
class PrivacyAccessCoordinatorTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private lateinit var unlocker: PrivacyUnlocker
    private lateinit var manager: PrivacySessionManager
    private lateinit var policy: MutablePrivacyVisibilityPolicy
    private lateinit var bioKek: PrivacyAuthKek
    private lateinit var deviceKek: PrivacyAuthKek
    private lateinit var authenticator: FakeAuthenticator
    private lateinit var coordinator: PrivacyAccessCoordinator

    @Before
    fun setUp() {
        unlocker = PrivacyUnlocker(
            vaultStore = PrivacyVaultStore(File(tempDir.root, "privacy/vault.bin")),
            pinKekFactory = { PinPrivacyKek(it, iterations = 1_000) },
        )
        policy = MutablePrivacyVisibilityPolicy()
        manager = PrivacySessionManager(unlocker, policy)
        bioKek = AuthGatedPrivacyKek("bio", PlainKeyProvider(aesKey()))
        deviceKek = AuthGatedPrivacyKek("device", PlainKeyProvider(aesKey()))
        authenticator = FakeAuthenticator(unlocker)
        coordinator = PrivacyAccessCoordinator(
            manager = manager,
            authenticator = authenticator,
            keystore = FakeKeystore(bioKek, deviceKek),
        )
    }

    @Test
    fun `capability reflects the device`() {
        authenticator.capability = PrivacyAuthCapability(biometric = false, deviceCredential = false)
        assertThat(coordinator.capability.biometric).isFalse()
    }

    @Test
    fun `canUseBiometrics needs both capability and a bound key`(): Unit = runBlocking {
        assertThat(coordinator.canUseBiometrics()).isFalse()

        coordinator.setup(PIN, enableBiometrics = true)

        assertThat(coordinator.canUseBiometrics()).isTrue()
        coordinator.lock()
        assertThat(coordinator.canUseBiometrics()).isTrue()
    }

    @Test
    fun `enter with biometrics opens the session`(): Unit = runBlocking {
        coordinator.setup(PIN, enableBiometrics = true)
        coordinator.lock()

        val result = coordinator.enterWithBiometrics()

        assertThat(result).isEqualTo(PrivacyAccessResult.Success)
        assertThat(manager.isPrivate).isTrue()
        assertThat(policy.includePrivate()).isTrue()
    }

    @Test
    fun `negative button surfaces as Cancelled and keeps normal mode`(): Unit = runBlocking {
        coordinator.setup(PIN, enableBiometrics = true)
        coordinator.lock()
        authenticator.mode = Mode.Cancel

        val result = coordinator.enterWithBiometrics()

        assertThat(result).isEqualTo(PrivacyAccessResult.Cancelled)
        assertThat(policy.includePrivate()).isFalse()
    }

    @Test
    fun `biometrics unavailable is reported instead of failing`(): Unit = runBlocking {
        coordinator.setup(PIN, enableBiometrics = true)
        coordinator.lock()
        authenticator.capability = PrivacyAuthCapability(biometric = false, deviceCredential = true)

        val result = coordinator.enterWithBiometrics()

        assertThat(result)
            .isEqualTo(PrivacyAccessResult.Unavailable(PrivacyAuthUnavailable.NO_HARDWARE))
    }

    @Test
    fun `pin path works and rejects a wrong pin`(): Unit = runBlocking {
        coordinator.setup(PIN, enableBiometrics = false)
        coordinator.lock()

        assertThat(coordinator.enterWithPin("000000".toCharArray()))
            .isInstanceOf(PrivacyAccessResult.Failed::class.java)
        assertThat(policy.includePrivate()).isFalse()

        assertThat(coordinator.enterWithPin(PIN)).isEqualTo(PrivacyAccessResult.Success)
        assertThat(manager.isPrivate).isTrue()
    }

    @Test
    fun `setup without biometrics stores only the pin key`(): Unit = runBlocking {
        val result = coordinator.setup(PIN, enableBiometrics = false)

        assertThat(result).isEqualTo(SetupResult.Created(biometricSkipped = false))
        assertThat(manager.availableKeks()).containsExactly(PinPrivacyKek.ID)
    }

    @Test
    fun `setup with biometrics stores both keys`(): Unit = runBlocking {
        val result = coordinator.setup(PIN, enableBiometrics = true)

        assertThat(result).isEqualTo(SetupResult.Created(biometricSkipped = false))
        assertThat(manager.availableKeks()).containsExactly(PinPrivacyKek.ID, "bio")
    }

    @Test
    fun `enrollment failure falls back to a pin-only vault and still unlocks`(): Unit = runBlocking {
        authenticator.mode = Mode.Fail

        val result = coordinator.setup(PIN, enableBiometrics = true)

        assertThat(result).isEqualTo(SetupResult.Created(biometricSkipped = true))
        assertThat(manager.availableKeks()).containsExactly(PinPrivacyKek.ID)
        // 关键：不能因为指纹失败就让用户连隐私都设不起来
        coordinator.lock()
        assertThat(coordinator.enterWithPin(PIN)).isEqualTo(PrivacyAccessResult.Success)
    }

    @Test
    fun `setup on a device without biometrics reports skipped`(): Unit = runBlocking {
        authenticator.capability = PrivacyAuthCapability(biometric = false, deviceCredential = false)

        val result = coordinator.setup(PIN, enableBiometrics = true)

        assertThat(result).isEqualTo(SetupResult.Created(biometricSkipped = true))
        assertThat(manager.availableKeks()).containsExactly(PinPrivacyKek.ID)
    }

    @Test
    fun `recover prefers the device credential key`(): Unit = runBlocking {
        // 两把钥匙都绑上：恢复必须挑设备密码那把（重启后指纹不可用，它才是可靠后路）
        coordinator.setup(PIN, enableBiometrics = true)
        manager.bindAuthorizedKey(AuthorizedPrivacyKey(deviceKek, deviceKek.prepareWrap())).getOrThrow()
        coordinator.lock()

        val result = coordinator.recover(NEW_PIN)

        assertThat(result).isEqualTo(PrivacyAccessResult.Success)
        assertThat(authenticator.lastUsedKekId).isEqualTo("device")
        coordinator.lock()
        assertThat(coordinator.enterWithPin(NEW_PIN)).isEqualTo(PrivacyAccessResult.Success)
        // 旧 PIN 已作废，数据仍在
        assertThat(manager.availableKeks()).containsExactly(PinPrivacyKek.ID, "bio", "device")
    }

    @Test
    fun `recover falls back to biometrics when the device key is absent`(): Unit = runBlocking {
        coordinator.setup(PIN, enableBiometrics = true)
        coordinator.lock()

        val result = coordinator.recover(NEW_PIN)

        assertThat(result).isEqualTo(PrivacyAccessResult.Success)
        assertThat(authenticator.lastUsedKekId).isEqualTo("bio")
        coordinator.lock()
        assertThat(coordinator.enterWithPin(PIN)).isInstanceOf(PrivacyAccessResult.Failed::class.java)
        assertThat(coordinator.enterWithPin(NEW_PIN)).isEqualTo(PrivacyAccessResult.Success)
    }

    @Test
    fun `recover without any recovery key is reported as unavailable`(): Unit = runBlocking {
        coordinator.setup(PIN, enableBiometrics = false)
        coordinator.lock()

        val result = coordinator.recover(NEW_PIN)

        assertThat(result)
            .isEqualTo(PrivacyAccessResult.Unavailable(PrivacyAuthUnavailable.NO_RECOVERY_KEY))
    }

    @Test
    fun `hasRecoveryKey tracks what is actually bound`(): Unit = runBlocking {
        coordinator.setup(PIN, enableBiometrics = false)
        assertThat(coordinator.hasRecoveryKey()).isFalse()

        coordinator.bindBiometrics()
        assertThat(coordinator.hasRecoveryKey()).isTrue()
    }

    @Test
    fun `bind biometrics later enables the fingerprint path`(): Unit = runBlocking {
        coordinator.setup(PIN, enableBiometrics = false)
        assertThat(coordinator.canUseBiometrics()).isFalse()

        assertThat(coordinator.bindBiometrics()).isEqualTo(PrivacyAccessResult.Success)
        coordinator.lock()

        assertThat(coordinator.canUseBiometrics()).isTrue()
        assertThat(coordinator.enterWithBiometrics()).isEqualTo(PrivacyAccessResult.Success)
    }

    @Test
    fun `bind biometrics cancels without changing the vault`(): Unit = runBlocking {
        coordinator.setup(PIN, enableBiometrics = false)
        authenticator.mode = Mode.Cancel

        val result = coordinator.bindBiometrics()

        assertThat(result).isEqualTo(PrivacyAccessResult.Cancelled)
        assertThat(manager.availableKeks()).containsExactly(PinPrivacyKek.ID)
    }

    // --- 替身 ---

    private enum class Mode { Work, Cancel, Unavailable, Fail }

    private class FakeAuthenticator(private val unlocker: PrivacyUnlocker) : PrivacyAuthenticator {

        var capability: PrivacyAuthCapability =
            PrivacyAuthCapability(biometric = true, deviceCredential = true)
        var mode: Mode = Mode.Work
        var lastUsedKekId: String? = null

        override fun capability(): PrivacyAuthCapability = capability

        override fun isBound(kekId: String): Boolean =
            runCatching { unlocker.hasKek(kekId) }.getOrDefault(false)

        override suspend fun enroll(kek: PrivacyAuthKek): PrivacyAuthOutcome = when (mode) {
            Mode.Work -> PrivacyAuthOutcome.Authorized(AuthorizedPrivacyKey(kek, kek.prepareWrap()))
            Mode.Cancel -> PrivacyAuthOutcome.Cancelled
            Mode.Unavailable -> PrivacyAuthOutcome.Unavailable(PrivacyAuthUnavailable.NONE_ENROLLED)
            Mode.Fail -> PrivacyAuthOutcome.Failed("模拟指纹硬件故障")
        }

        override suspend fun unlock(kek: PrivacyAuthKek): PrivacyAuthOutcome = when (mode) {
            Mode.Work -> {
                lastUsedKekId = kek.id
                val sealed = unlocker.sealedEntry(kek.id)
                if (sealed == null) {
                    PrivacyAuthOutcome.Unavailable(PrivacyAuthUnavailable.NOT_BOUND)
                } else {
                    PrivacyAuthOutcome.Authorized(AuthorizedPrivacyKey(kek, kek.prepareUnwrap(sealed)))
                }
            }

            Mode.Cancel -> PrivacyAuthOutcome.Cancelled
            Mode.Unavailable -> PrivacyAuthOutcome.Unavailable(PrivacyAuthUnavailable.NONE_ENROLLED)
            Mode.Fail -> PrivacyAuthOutcome.Failed("模拟指纹硬件故障")
        }
    }

    private class FakeKeystore(
        private val bio: PrivacyAuthKek,
        private val device: PrivacyAuthKek?,
    ) : PrivacyKeystore {
        override fun biometric(): PrivacyAuthKek = bio
        override fun device(): PrivacyAuthKek? = device
    }

    private class PlainKeyProvider(private val key: SecretKey) : PrivacyKeyProvider {
        override fun get(): SecretKey = key
        override fun exists(): Boolean = true
    }

    private fun aesKey(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private companion object {
        val PIN = "123456".toCharArray()
        val NEW_PIN = "246810".toCharArray()
    }
}
