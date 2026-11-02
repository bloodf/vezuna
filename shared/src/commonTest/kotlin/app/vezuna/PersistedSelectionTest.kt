package app.vezuna

import app.vezuna.data.VaultBackedRepository
import app.vezuna.domain.HealthLink
import app.vezuna.domain.Profile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class PersistedSelectionTest {
    private val vault = GenerationalMemoryVault()
    private val created = Instant.fromEpochSeconds(1_700_000_000)

    private suspend fun open() = VaultBackedRepository(vault, persistSelection = true).also { it.load() }

    private fun VaultBackedRepository.ids() = profiles.value.map { it.profileId }

    @Test
    fun selectionSurvivesReopening() = runTest {
        val first = open()
        first.createProfile(Profile("a", "Ana", createdAt = created))
        first.createProfile(Profile("z", "Zed", createdAt = created))
        first.readProfile("z")

        assertEquals(listOf("z", "a"), open().ids())
    }

    @Test
    fun restoredProfileIsFirstAfterReopeningEvenWhenItSortsAfterMe() = runTest {
        val first = open()
        first.createProfile(Profile("me", "Me", createdAt = created))
        first.createProfile(Profile("restored", "Zoe", createdAt = created))
        first.readProfile("restored")

        assertEquals("restored", open().profiles.value.first().profileId)
    }

    @Test
    fun staleSelectionFallsBackToNameOrder() = runTest {
        val first = open()
        first.createProfile(Profile("a", "Ana", createdAt = created))
        first.createProfile(Profile("z", "Zed", createdAt = created))
        vault.write("repository-selected-profile-v1", "gone".encodeToByteArray())
        assertEquals(listOf("a", "z"), open().ids())

        vault.write("repository-selected-profile-v1", ByteArray(0))
        assertEquals(listOf("a", "z"), open().ids())
    }

    @Test
    fun missingSelectionFileFallsBackToNameOrder() = runTest {
        val first = open()
        first.createProfile(Profile("a", "Ana", createdAt = created))
        first.createProfile(Profile("z", "Zed", createdAt = created))
        first.readProfile("z")
        vault.current.remove("repository-selected-profile-v1")
        vault.previous.remove("repository-selected-profile-v1")

        assertEquals(listOf("a", "z"), open().ids())
    }

    @Test
    fun healthLinkDoesNotFollowSelectionOrListOrder() = runTest {
        val old = Instant.fromEpochSeconds(1_600_000_000)
        val first = open()
        first.createProfile(Profile("me", "Me", createdAt = old))
        first.createProfile(Profile("z", "Zed", createdAt = created))
        first.readProfile("z")

        val reopened = open()
        assertEquals("z", reopened.profiles.value.first().profileId)
        assertEquals("me", HealthLink.resolve(null, reopened.profiles.value))
    }
}
