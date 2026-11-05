package app.vezuna

import app.vezuna.security.AndroidSecureVault
import app.vezuna.security.CipherEnvelope
import app.vezuna.security.VaultFailure
import app.vezuna.security.VaultUnreadableException
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.UnrecoverableKeyException
import java.security.cert.Certificate
import java.util.Date
import java.util.Enumeration
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Covers the Android Keystore state the other adapter tests never reach: the alias is still listed, but the key behind it
 * can no longer be unwrapped.
 *
 * Existing adapter tests cover an absent alias on the JVM and a healthy alias in Android instrumentation. A
 * present-but-dead alias is the state a real invalidated Keystore entry lands in, and it is the only state in which
 * [AndroidSecureVault.discardUnusableKey] is supposed to delete the shared alias.
 *
 * The fixture is a plain JVM [KeyStore]. `java.security.KeyStore.getKey` is final and delegates to
 * [KeyStoreSpi.engineGetKey], so the dead state is modelled in the SPI: the alias resolves, and unwrapping it throws
 * [UnrecoverableKeyException] until the alias is deleted. No Android class is constructed, so the JVM stub `android.jar`
 * is never asked to build an exception, and no device, no platform Keystore and no real alias are involved.
 */
class AndroidSecureVaultDeadKeyTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun envelope() = CipherEnvelope(
        "AES-256-GCM",
        VAULT_ALIAS,
        ByteArray(12) { it.toByte() },
        ByteArray(32) { (it + 1).toByte() },
        "synthetic-profile".encodeToByteArray(),
    )

    /**
     * The alias failure must win over the ciphertext. The envelope carries garbage ciphertext, so a working key would report
     * [VaultFailure.CORRUPT]; the cause proves the reported failure came from the key lookup instead.
     */
    @Test
    fun deadAliasDecryptsAsInvalidatedAndNeverAsCorruptOrUnavailable() = runBlocking {
        val store = DeadKeyStore.withDeadAlias(VAULT_ALIAS)
        val vault = AndroidSecureVault(temporary.newFolder(), store)

        val failure = assertFailsWith<VaultUnreadableException> { vault.decrypt(envelope()) }

        assertEquals(VaultFailure.KEY_INVALIDATED, failure.failure)
        assertNotEquals(VaultFailure.CORRUPT, failure.failure)
        assertNotEquals(VaultFailure.UNAVAILABLE, failure.failure)
        assertTrue(failure.cause is UnrecoverableKeyException)
        assertTrue(store.containsAlias(VAULT_ALIAS))
        assertTrue(store.deletedAliases.isEmpty())
    }

    @Test
    fun discardingADeadKeyRemovesTheAlias() = runBlocking {
        val store = DeadKeyStore.withDeadAlias(VAULT_ALIAS)
        val vault = AndroidSecureVault(temporary.newFolder(), store)

        vault.discardUnusableKey()

        assertEquals(listOf(VAULT_ALIAS), store.deletedAliases)
        assertFalse(store.containsAlias(VAULT_ALIAS))
        assertTrue(store.aliases().toList().isEmpty())
    }

    /** The second discard finds no alias left to remove, so it must not delete anything again. */
    @Test
    fun discardingADeadKeyTwiceDeletesOnceAndLeavesTheStoreEmpty() = runBlocking {
        val store = DeadKeyStore.withDeadAlias(VAULT_ALIAS)
        val vault = AndroidSecureVault(temporary.newFolder(), store)

        vault.discardUnusableKey()
        vault.discardUnusableKey()

        assertEquals(listOf(VAULT_ALIAS), store.deletedAliases)
        assertFalse(store.containsAlias(VAULT_ALIAS))
        assertTrue(store.aliases().toList().isEmpty())
    }

    private companion object {
        /** The alias the production adapter uses. It is a private constant there, so the literal is repeated here on purpose. */
        const val VAULT_ALIAS = "app.vezuna.vault.v1"
    }
}

/**
 * A [KeyStore] whose only entry is listed but can no longer be unwrapped.
 *
 * [present] holds the aliases the store still lists. [deleted] records every deletion the adapter asked for, in order.
 */
private class DeadKeyStore(
    private val present: MutableSet<String>,
    private val deleted: MutableList<String>,
) : KeyStore(DeadKeyStoreSpi(present, deleted), null, "DeadKeyStore") {
    init {
        load(null, null)
    }

    val deletedAliases: List<String> get() = deleted.toList()

    companion object {
        fun withDeadAlias(alias: String): DeadKeyStore = DeadKeyStore(mutableSetOf(alias), mutableListOf())
    }
}

private class DeadKeyStoreSpi(
    private val present: MutableSet<String>,
    private val deleted: MutableList<String>,
) : KeyStoreSpi() {
    /** A listed alias whose key is dead: the lookup itself fails, exactly as the platform reports an invalidated entry. */
    override fun engineGetKey(alias: String?, password: CharArray?): Key? {
        val name = alias ?: return null
        if (!present.contains(name)) return null
        throw UnrecoverableKeyException("Key '$name' can no longer be unwrapped")
    }

    override fun engineDeleteEntry(alias: String?) {
        val name = alias ?: return
        deleted += name
        present -= name
    }

    override fun engineContainsAlias(alias: String?): Boolean = alias != null && present.contains(alias)

    override fun engineAliases(): Enumeration<String> = java.util.Collections.enumeration(present.toList())

    override fun engineSize(): Int = present.size

    override fun engineIsKeyEntry(alias: String?): Boolean = alias != null && present.contains(alias)

    override fun engineIsCertificateEntry(alias: String?): Boolean = false

    override fun engineGetCertificate(alias: String?): Certificate? = null

    override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null

    override fun engineGetCertificateAlias(cert: Certificate?): String? = null

    override fun engineGetCreationDate(alias: String?): Date = Date(0)

    override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) = Unit

    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = Unit

    override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = Unit

    override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit

    override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
}
