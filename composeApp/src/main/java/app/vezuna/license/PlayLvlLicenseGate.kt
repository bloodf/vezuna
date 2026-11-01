package app.vezuna.license

import android.content.Context
import android.provider.Settings
import app.vezuna.BuildConfig
import com.google.android.vending.licensing.AESObfuscator
import com.google.android.vending.licensing.LicenseChecker
import com.google.android.vending.licensing.LicenseCheckerCallback
import com.google.android.vending.licensing.Policy
import com.google.android.vending.licensing.ServerManagedPolicy
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

interface PremiumLicenseGate {
    suspend fun premiumAllowed(): Boolean
}

/** Play LVL gates only premium model activation. Its result is never consulted by records or exports. */
class PlayLvlLicenseGate(context: Context) : PremiumLicenseGate {
    private val appContext = context.applicationContext
    private val policy = ServerManagedPolicy(
        context,
        AESObfuscator(SALT, context.packageName, Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()),
    )
    private var checker: LicenseChecker? = null

    override suspend fun premiumAllowed(): Boolean {
        if (BuildConfig.DEBUG && BuildConfig.DEBUG_PREMIUM_BYPASS && BuildConfig.LVL_PUBLIC_KEY.isBlank()) return true
        if (BuildConfig.LVL_PUBLIC_KEY.isBlank()) return false
        return suspendCancellableCoroutine { continuation ->
            val callback = object : LicenseCheckerCallback {
                override fun allow(reason: Int) = resumeOnce(policy.allowAccess())
                override fun dontAllow(reason: Int) {
                    val graceAllowed = reason == Policy.RETRY && policy.allowAccess()
                    resumeOnce(graceAllowed)
                }
                override fun applicationError(errorCode: Int) = resumeOnce(false)

                private fun resumeOnce(allowed: Boolean) {
                    if (continuation.isActive) continuation.resume(allowed)
                }
            }
            val licenseChecker = LicenseChecker(appContext, policy, BuildConfig.LVL_PUBLIC_KEY)
            checker = licenseChecker
            continuation.invokeOnCancellation { licenseChecker.onDestroy() }
            try {
                licenseChecker.checkAccess(callback)
            } catch (_: Throwable) {
                licenseChecker.onDestroy()
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }

    fun close() {
        checker?.onDestroy()
        checker = null
    }

    private companion object {
        val SALT = byteArrayOf(41, -91, 17, 63, -78, 102, 38, 88, -12, 7, 19, 73, -33, 96, 22, 54, 81, -27, 44, 100)
    }
}
