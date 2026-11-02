package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.Profile
import app.vezuna.ui.AppScreen
import app.vezuna.ui.DesignFixture
import app.vezuna.ui.nav.Destination
import app.vezuna.ui.nav.Navigator
import app.vezuna.ui.nav.routeFromName
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigatorTest {
    @Test
    fun rootsAreTheFiveLabeledDestinationsInOrder() {
        assertEquals(listOf("TODAY", "RECORDS", "CARE", "TRENDS", "MORE"), Destination.entries.map { it.name })
        assertEquals(AppScreen.Dashboard, Navigator().current)
    }

    @Test
    fun pushAndBackRestoreThePreviousScreen() {
        val navigator = Navigator()
        navigator.selectRoot(Destination.RECORDS)
        navigator.navigate(AppScreen.ResultDetail("hgb"))
        navigator.navigate(AppScreen.ResultEdit("hgb"))
        assertEquals(AppScreen.ResultEdit("hgb"), navigator.current)
        assertTrue(navigator.back())
        assertEquals(AppScreen.ResultDetail("hgb"), navigator.current)
        assertTrue(navigator.back())
        assertEquals(AppScreen.Labs, navigator.current)
    }

    @Test
    fun eachRootKeepsItsOwnStack() {
        val navigator = Navigator()
        navigator.selectRoot(Destination.RECORDS)
        navigator.navigate(AppScreen.ResultDetail("hgb"))
        navigator.selectRoot(Destination.CARE)
        assertEquals(AppScreen.Medications, navigator.current)
        navigator.selectRoot(Destination.RECORDS)
        assertEquals(AppScreen.ResultDetail("hgb"), navigator.current)
    }

    @Test
    fun reselectingTheCurrentRootPopsToItsFirstScreen() {
        val navigator = Navigator()
        navigator.selectRoot(Destination.MORE)
        navigator.navigate(AppScreen.Settings)
        navigator.selectRoot(Destination.MORE)
        assertEquals(AppScreen.More, navigator.current)
    }

    @Test
    fun navigatingToARootScreenSelectsItsTab() {
        val navigator = Navigator()
        navigator.navigate(AppScreen.ImportReview)
        navigator.navigate(AppScreen.Labs)
        assertEquals(Destination.RECORDS, navigator.state.value.root)
        assertTrue(navigator.state.value.isRoot)
        // The Today stack still holds the import screen it was on.
        navigator.selectRoot(Destination.TODAY)
        assertEquals(AppScreen.ImportReview, navigator.current)
    }

    @Test
    fun backFromAnotherRootReturnsToTodayThenStops() {
        val navigator = Navigator()
        navigator.selectRoot(Destination.TRENDS)
        assertTrue(navigator.state.value.canGoBack)
        assertTrue(navigator.back())
        assertEquals(Destination.TODAY, navigator.state.value.root)
        assertFalse(navigator.state.value.canGoBack)
        assertFalse(navigator.back())
    }

    @Test
    fun pushingTheCurrentScreenAgainDoesNotStackDuplicates() {
        val navigator = Navigator()
        navigator.navigate(AppScreen.ImportReview)
        navigator.navigate(AppScreen.ImportReview)
        assertEquals(2, navigator.state.value.stack.size)
    }

    @Test
    fun profileChangeClearsBoundStateAndPushedRoutes() = runTest {
        val repository = InMemoryVezunaRepository()
        DesignFixture.install(repository)
        repository.createProfile(Profile("b", "Bruno", createdAt = Instant.parse("2026-11-01T12:00:00Z")))
        val controller = labController(repository).also { it.initialize() }
        assertEquals(DesignFixture.PROFILE_ID, controller.state.value.data?.profile?.profileId)
        controller.selectRoot(Destination.RECORDS)
        controller.navigate(AppScreen.ResultDetail("hgb-2026-10"))
        controller.toggleObservation("hgb-2026-10")
        assertEquals(setOf("hgb-2026-10"), controller.state.value.selectedObservationIds)

        controller.switchProfile("b")

        assertEquals("b", controller.state.value.data?.profile?.profileId)
        assertEquals(emptySet(), controller.state.value.selectedObservationIds)
        assertNull(controller.state.value.pdfExport)
        assertEquals(AppScreen.Labs, controller.navigator.current, "routes carry the previous profile's IDs")
        assertEquals(Destination.RECORDS, controller.navigator.state.value.root, "the visible tab stays")
    }

    @Test
    fun finishingATaskElsewhereDoesNotLeaveItOnTheOriginTab() {
        val navigator = Navigator()
        navigator.navigate(AppScreen.ImportReview)
        navigator.finishAndGo(AppScreen.Labs)
        assertEquals(AppScreen.Labs, navigator.current)
        navigator.selectRoot(Destination.TODAY)
        assertEquals(AppScreen.Dashboard, navigator.current)
    }

    @Test
    fun deepLinkNamesParse() {
        assertEquals(AppScreen.More, routeFromName("More"))
        assertEquals(AppScreen.ResultDetail("hgb-2026-10"), routeFromName("ResultDetail:hgb-2026-10"))
        assertEquals(AppScreen.MedicationForm(null), routeFromName("MedicationForm"))
        assertNull(routeFromName("ResultDetail"))
        assertNull(routeFromName("Nope"))
    }

    @Test
    fun designFixtureMatchesTheCanonicalHousehold() = runTest {
        val repository = InMemoryVezunaRepository()
        DesignFixture.install(repository)
        val data = repository.readProfile(DesignFixture.PROFILE_ID)
        assertEquals("Ana Silva", data.profile.displayName)
        assertEquals(8, data.observations.count { it.confirmation == ConfirmationStatus.CONFIRMED })
        assertEquals(3, data.ocrCandidates.count { it.status == ConfirmationStatus.UNREVIEWED })
        assertEquals(listOf("Vitamin D"), data.medications.map { it.displayName })
        assertEquals(7, data.doseEvents.size)
        assertEquals(7, data.vitals.steps.size)
        assertEquals(45_585L, data.vitals.steps.sumOf { it.count })
        assertEquals(DesignFixture.PROFILE_ID, repository.linkedHealthProfileId())
    }
}
