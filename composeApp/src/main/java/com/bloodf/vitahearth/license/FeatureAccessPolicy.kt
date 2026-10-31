package com.bloodf.vitahearth.license

enum class AppFeature { PREMIUM_AI_MODEL, HEALTH_RECORDS, CSV_EXPORT, PDF_EXPORT, ENCRYPTED_BACKUP }

object FeatureAccessPolicy {
    fun isAllowed(feature: AppFeature, premiumAllowed: Boolean): Boolean = when (feature) {
        AppFeature.PREMIUM_AI_MODEL -> premiumAllowed
        AppFeature.HEALTH_RECORDS,
        AppFeature.CSV_EXPORT,
        AppFeature.PDF_EXPORT,
        AppFeature.ENCRYPTED_BACKUP,
        -> true
    }
}
