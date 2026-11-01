package app.vezuna

import app.vezuna.license.AppFeature
import app.vezuna.license.FeatureAccessPolicy
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureAccessPolicyTest {
    @Test
    fun onlyPremiumModelActivationDependsOnLicense() {
        assertFalse(FeatureAccessPolicy.isAllowed(AppFeature.PREMIUM_AI_MODEL, premiumAllowed = false))
        assertTrue(FeatureAccessPolicy.isAllowed(AppFeature.PREMIUM_AI_MODEL, premiumAllowed = true))
        setOf(AppFeature.HEALTH_RECORDS, AppFeature.CSV_EXPORT, AppFeature.PDF_EXPORT, AppFeature.ENCRYPTED_BACKUP).forEach { feature ->
            assertTrue(FeatureAccessPolicy.isAllowed(feature, premiumAllowed = false), "$feature must remain available")
            assertTrue(FeatureAccessPolicy.isAllowed(feature, premiumAllowed = true), "$feature must remain available")
        }
    }
}
