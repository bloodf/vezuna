package app.vezuna

import app.vezuna.security.VaultFailure
import app.vezuna.security.classifyDecryptFailure
import org.junit.Test
import java.io.IOException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import kotlin.test.assertEquals

class DecryptFailureClassifierTest {
    @Test
    fun tamperedDataIsCorrupt() {
        assertEquals(VaultFailure.CORRUPT, classifyDecryptFailure(AEADBadTagException("tag")))
        assertEquals(VaultFailure.CORRUPT, classifyDecryptFailure(IllegalArgumentException("iv")))
    }

    @Test
    fun deadKeyIsInvalidated() {
        assertEquals(VaultFailure.KEY_INVALIDATED, classifyDecryptFailure(UnrecoverableKeyException()))
    }

    @Test
    fun transientKeystoreAndIoErrorsStayRetryable() {
        assertEquals(VaultFailure.UNAVAILABLE, classifyDecryptFailure(KeyStoreException("daemon busy")))
        assertEquals(VaultFailure.UNAVAILABLE, classifyDecryptFailure(IOException("disk")))
        assertEquals(VaultFailure.UNAVAILABLE, classifyDecryptFailure(RuntimeException("provider")))
    }
}
