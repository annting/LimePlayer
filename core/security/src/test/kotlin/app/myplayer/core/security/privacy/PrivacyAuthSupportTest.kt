package app.myplayer.core.security.privacy

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PrivacyAuthSupportTest {

    @Test
    fun `android 11 with device lock and strong biometric gets both keys`() {
        val capability = PrivacyAuthSupport.evaluate(
            sdkInt = 30,
            deviceCredentialAvailable = true,
            biometricStrongAvailable = true,
        )
        assertThat(capability.biometric).isTrue()
        assertThat(capability.deviceCredential).isTrue()
        assertThat(capability.recoveryRisk).isEqualTo(PrivacyRecoveryRisk.RECOVERABLE)
        assertThat(capability.survivesRebootWithoutPin).isTrue()
    }

    @Test
    fun `android 10 cannot use device credential crypto`() {
        // API 29 的 BiometricPrompt 不支持 DEVICE_CREDENTIAL + CryptoObject
        val capability = PrivacyAuthSupport.evaluate(
            sdkInt = 29,
            deviceCredentialAvailable = true,
            biometricStrongAvailable = true,
        )
        assertThat(capability.biometric).isTrue()
        assertThat(capability.deviceCredential).isFalse()
        assertThat(capability.recoveryRisk).isEqualTo(PrivacyRecoveryRisk.BIOMETRIC_ONLY)
        assertThat(capability.survivesRebootWithoutPin).isFalse()
    }

    @Test
    fun `no device credential means no device credential key`() {
        val capability = PrivacyAuthSupport.evaluate(
            sdkInt = 34,
            deviceCredentialAvailable = false,
            biometricStrongAvailable = true,
        )
        assertThat(capability.deviceCredential).isFalse()
        assertThat(capability.recoveryRisk).isEqualTo(PrivacyRecoveryRisk.BIOMETRIC_ONLY)
    }

    @Test
    fun `weak only biometric is not enough`() {
        // BIOMETRIC_WEAK 不能给 Keystore 密钥授权
        val capability = PrivacyAuthSupport.evaluate(
            sdkInt = 34,
            deviceCredentialAvailable = true,
            biometricStrongAvailable = false,
        )
        assertThat(capability.biometric).isFalse()
        assertThat(capability.deviceCredential).isTrue()
        assertThat(capability.recoveryRisk).isEqualTo(PrivacyRecoveryRisk.RECOVERABLE)
    }

    @Test
    fun `nothing available leaves no recovery key`() {
        val capability = PrivacyAuthSupport.evaluate(
            sdkInt = 27,
            deviceCredentialAvailable = false,
            biometricStrongAvailable = false,
        )
        assertThat(capability.biometric).isFalse()
        assertThat(capability.deviceCredential).isFalse()
        assertThat(capability.recoveryRisk).isEqualTo(PrivacyRecoveryRisk.NO_RECOVERY_KEY)
        assertThat(capability.survivesRebootWithoutPin).isFalse()
    }

    @Test
    fun `device credential key is only supported from android 11`() {
        assertThat(PrivacyAuthSupport.supportsDeviceCredentialKey(29)).isFalse()
        assertThat(PrivacyAuthSupport.supportsDeviceCredentialKey(30)).isTrue()
        assertThat(PrivacyAuthSupport.supportsDeviceCredentialKey(35)).isTrue()
    }
}
