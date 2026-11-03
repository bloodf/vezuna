package app.vezuna

import app.vezuna.data.VaultBackedRepository
import app.vezuna.domain.Profile
import app.vezuna.security.CipherEnvelope
import app.vezuna.security.SecureVault
import app.vezuna.security.VaultFailure
import app.vezuna.security.VaultUnreadableException
import app.vezuna.ui.AppUiState
import app.vezuna.ui.today.startupFailed
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/** A vault whose key cannot be created or read, like a Keychain refusing every call. */
private class DeadKeyVault(private val inner: GenerationalMemoryVault = GenerationalMemoryVault()) : SecureVault by inner {
    var dead = true
    val stored get() = inner.current

    override suspend fun encrypt(plainText: ByteArray, associatedData: ByteArray): CipherEnvelope {
        if (dead) throw VaultUnreadableException(VaultFailure.KEY_UNAVAILABLE)
        return inner.encrypt(plainText, associatedData)
    }
}

class VaultFirstRunKeyTest {
    @Test
    fun anEmptyVaultWithADeadKeyDoesNotOpen() = runTest {
        val vault = DeadKeyVault()

        val failure = assertFailsWith<VaultUnreadableException> { VaultBackedRepository.open(vault) }

        assertEquals(VaultFailure.KEY_UNAVAILABLE, failure.failure)
        assertTrue(vault.stored.isEmpty())
    }

    @Test
    fun retryingTheSameRepositoryChecksTheKeyAgainAndOpensOnceItWorks() = runTest {
        val vault = DeadKeyVault()
        val repository = VaultBackedRepository(vault)
        assertFailsWith<VaultUnreadableException> { repository.load() }
        assertFailsWith<VaultUnreadableException> { repository.load() }

        vault.dead = false
        repository.load()

        repository.createProfile(Profile("id", "Ana", createdAt = Instant.fromEpochMilliseconds(1)))
        assertEquals(listOf("Ana"), repository.profiles.value.map { it.displayName })
    }

    @Test
    fun aVaultWithRecordsOpensWithoutTheEncryptCheck() = runTest {
        val vault = DeadKeyVault()
        vault.dead = false
        VaultBackedRepository.open(vault).createProfile(Profile("id", "Ana", createdAt = Instant.fromEpochMilliseconds(1)))

        vault.dead = true

        assertEquals(listOf("Ana"), VaultBackedRepository.open(vault).profiles.value.map { it.displayName })
    }

    @Test
    fun todayOnlyReportsAFailureOnceLoadingHasEnded() {
        assertFalse(startupFailed(AppUiState()))
        assertTrue(startupFailed(AppUiState(loading = false)))
    }
}
