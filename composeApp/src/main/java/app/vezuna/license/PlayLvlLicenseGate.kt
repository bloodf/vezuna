package app.vezuna.license

import android.content.Context
import android.provider.Settings
import android.util.Log
import app.vezuna.BuildConfig
import com.google.android.vending.licensing.AESObfuscator
import com.google.android.vending.licensing.LicenseChecker
import com.google.android.vending.licensing.LicenseCheckerCallback
import com.google.android.vending.licensing.Policy
import com.google.android.vending.licensing.ServerManagedPolicy
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

interface PremiumLicenseGate {
    suspend fun premiumAllowed(): Boolean
}

/** One LVL round trip. [LicenseChecker] owns a HandlerThread and a service binding, so every probe must be destroyed. */
internal interface LicenseProbe {
    fun checkAccess(callback: LicenseCheckerCallback)
    fun onDestroy()
}

/**
 * Runs LVL checks one at a time and destroys each probe as soon as its check ends (result, cancellation or
 * failure). Offline behaviour comes from [policy]: a cached server answer and the RETRY grace window.
 */
internal class LicenseCheckRunner(
    private val policy: Policy,
    private val onProbeFailure: (Throwable) -> Unit = {},
    private val newProbe: () -> LicenseProbe,
) {
    private val mutex = Mutex()

    suspend fun check(): Boolean = mutex.withLock {
        // A malformed public key makes the checker constructor throw; deny instead of escaping premiumAllowed().
        val probe = try { newProbe() } catch (error: Throwable) { onProbeFailure(error); return@withLock false }
        // finally covers result, cancellation and failure, so the probe never outlives its check.
        try {
            suspendCancellableCoroutine { continuation ->
                val completed = AtomicBoolean(false)
                continuation.invokeOnCancellation { completed.set(true) }
                fun finish(allowed: Boolean) {
                    if (completed.compareAndSet(false, true)) continuation.resume(allowed)
                }
                val callback = object : LicenseCheckerCallback {
                    override fun allow(reason: Int) = finish(policy.allowAccess())
                    override fun dontAllow(reason: Int) = finish(reason == Policy.RETRY && policy.allowAccess())
                    override fun applicationError(errorCode: Int) = finish(false)

                }
                try {
                    probe.checkAccess(callback)
                } catch (_: Throwable) {
                    finish(false)
                }
            }
        } finally {
            probe.onDestroy()
        }
    }
}

/** Play LVL gates only premium model activation. Its result is never consulted by records or exports. */
class PlayLvlLicenseGate(context: Context) : PremiumLicenseGate {
    // Application context only: the gate lives in a ViewModel and must not retain an Activity.
    private val appContext = context.applicationContext
    private val policy = ServerManagedPolicy(
        appContext,
        AESObfuscator(SALT, appContext.packageName, Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()),
    )
    private val runner = LicenseCheckRunner(policy, { error -> Log.w(TAG, "License checker could not start: ${error::class.java.name}") }) {
        val checker = LicenseChecker(appContext, policy, BuildConfig.LVL_PUBLIC_KEY)
        object : LicenseProbe {
            override fun checkAccess(callback: LicenseCheckerCallback) = checker.checkAccess(callback)
            override fun onDestroy() = checker.onDestroy()
        }
    }

    override suspend fun premiumAllowed(): Boolean {
        if (BuildConfig.DEBUG && BuildConfig.DEBUG_PREMIUM_BYPASS && BuildConfig.LVL_PUBLIC_KEY.isBlank()) return true
        if (BuildConfig.LVL_PUBLIC_KEY.isBlank()) return false
        return runner.check()
    }

    private companion object {
        const val TAG = "PlayLvlLicenseGate"
        val SALT = byteArrayOf(41, -91, 17, 63, -78, 102, 38, 88, -12, 7, 19, 73, -33, 96, 22, 54, 81, -27, 44, 100)
    }
}
