package app.vezuna

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import app.vezuna.security.AppLockStore
import app.vezuna.security.DeviceAuthenticator
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Pre-API-30 prompts offer only biometrics, so errors that leave the user unable to use them hand over to the
 * system credential screen instead of locking them out. User or system cancellation stays closed.
 */
internal fun fallsBackToCredential(errorCode: Int): Boolean = when (errorCode) {
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    BiometricPrompt.ERROR_LOCKOUT,
    BiometricPrompt.ERROR_LOCKOUT_PERMANENT,
    BiometricPrompt.ERROR_HW_UNAVAILABLE,
    BiometricPrompt.ERROR_NO_BIOMETRICS,
    BiometricPrompt.ERROR_HW_NOT_PRESENT,
    -> true
    else -> false
}

/** Plain device-local preference: the lock must be known before the encrypted vault loads. Not backed up. */
class SharedPrefsAppLockStore(context: Context) : AppLockStore {
    private val prefs = context.applicationContext.getSharedPreferences("vezuna_app_lock", Context.MODE_PRIVATE)
    override fun isEnabled(): Boolean = prefs.getBoolean(KEY, false)
    override fun setEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY, enabled).apply()

    private companion object {
        const val KEY = "enabled"
    }
}

/**
 * API 30+: one BiometricPrompt accepting strong biometrics or the device credential. Below 30 that combination
 * is unsupported, so the prompt offers strong biometrics with a "use passcode" button that opens the system
 * confirm-credential screen (also used directly when no strong biometric is enrolled).
 * Must be constructed before the activity is STARTED (registers an activity-result launcher).
 */
class AndroidDeviceAuthenticator(private val activity: FragmentActivity) : DeviceAuthenticator {
    private val keyguard = activity.getSystemService(KeyguardManager::class.java)
    private val biometrics = BiometricManager.from(activity)
    private var credentialResult: ((Boolean) -> Unit)? = null
    private val credentialLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        credentialResult?.invoke(it.resultCode == Activity.RESULT_OK)
        credentialResult = null
    }

    private fun strongEnrolled() = biometrics.canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    override fun canAuthenticate(): Boolean? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            biometrics.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS
        } else {
            strongEnrolled() || keyguard.isDeviceSecure
        }

    override suspend fun authenticate(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> prompt(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, fallbackToCredential = false)
        strongEnrolled() -> prompt(BIOMETRIC_STRONG, fallbackToCredential = true)
        else -> confirmCredential()
    }

    private suspend fun prompt(authenticators: Int, fallbackToCredential: Boolean): Boolean {
        var credentialFallback = false
        val succeeded = suspendCancellableCoroutine { continuation ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    credentialFallback = fallbackToCredential && fallsBackToCredential(errorCode)
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(nativeMessage(NativeKey.APP_LOCK_PROMPT_TITLE).resolve(activity))
                .setSubtitle(nativeMessage(NativeKey.APP_LOCK_PROMPT_SUBTITLE).resolve(activity))
                .setAllowedAuthenticators(authenticators)
                .apply {
                    if (fallbackToCredential) setNegativeButtonText(nativeMessage(NativeKey.APP_LOCK_USE_PASSCODE).resolve(activity))
                }
                .build()
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
            prompt.authenticate(info)
        }
        return if (credentialFallback) confirmCredential() else succeeded
    }

    private suspend fun confirmCredential(): Boolean {
        val intent = keyguard.createConfirmDeviceCredentialIntent(
            nativeMessage(NativeKey.APP_LOCK_PROMPT_TITLE).resolve(activity),
            nativeMessage(NativeKey.APP_LOCK_PROMPT_SUBTITLE).resolve(activity),
        ) ?: return false
        return suspendCancellableCoroutine { continuation ->
            credentialResult = { ok -> if (continuation.isActive) continuation.resume(ok) }
            continuation.invokeOnCancellation { credentialResult = null }
            credentialLauncher.launch(intent)
        }
    }
}
