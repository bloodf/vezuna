package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.ui.DesignFixture
import app.vezuna.ui.EmergencyPreviewFixture
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class EmergencyPreviewFixtureTest {
    @Test
    fun bothVariantsLoadIntoARepository() = runTest {
        listOf("full", "partial").forEach { variant ->
            val repository = InMemoryVezunaRepository()
            EmergencyPreviewFixture.install(repository, variant)
            assertTrue(repository.profiles.value.any { it.profileId == DesignFixture.PROFILE_ID }, variant)
        }
    }
}
