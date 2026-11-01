package app.vezuna

import androidx.biometric.BiometricPrompt
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialFallbackTest {
    @Test
    fun unusableBiometricsHandOverToTheDeviceCredential() {
        listOf(
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_LOCKOUT,
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
            BiometricPrompt.ERROR_HW_UNAVAILABLE,
            BiometricPrompt.ERROR_NO_BIOMETRICS,
            BiometricPrompt.ERROR_HW_NOT_PRESENT,
        ).forEach { assertTrue(fallsBackToCredential(it), "code $it") }
    }

    @Test
    fun cancellationStaysClosed() {
        listOf(BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_TIMEOUT).forEach {
            assertFalse(fallsBackToCredential(it), "code $it")
        }
    }
}
