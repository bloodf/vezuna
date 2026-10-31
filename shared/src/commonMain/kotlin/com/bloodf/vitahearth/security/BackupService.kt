package com.bloodf.vitahearth.security

import com.bloodf.vitahearth.domain.ProfileData
import com.bloodf.vitahearth.localization.L10nKey
import com.bloodf.vitahearth.localization.failMessage
import com.bloodf.vitahearth.platform.currentInstant
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupPayload(
    val schemaVersion: Int,
    val profileId: String,
    val exportedAt: Instant,
    val data: ProfileData,
)

class BackupService(
    private val vault: SecureVault,
    private val json: Json = Json { prettyPrint = false },
) {
    suspend fun create(profileData: ProfileData): CipherEnvelope {
        val payload = BackupPayload(
            schemaVersion = SCHEMA_VERSION,
            profileId = profileData.profile.profileId,
            exportedAt = currentInstant(),
            data = profileData,
        )
        val plaintext = json.encodeToString(BackupPayload.serializer(), payload).encodeToByteArray()
        val associatedData = "VitaHearth backup v$SCHEMA_VERSION:${profileData.profile.profileId}".encodeToByteArray()
        return vault.encrypt(plaintext, associatedData)
    }

    suspend fun restore(envelope: CipherEnvelope, expectedProfileId: String): ProfileData {
        val plaintext = vault.decrypt(envelope)
        val payload = json.decodeFromString(BackupPayload.serializer(), plaintext.decodeToString())
        if (payload.schemaVersion != SCHEMA_VERSION) failMessage(L10nKey.BACKUP_UNSUPPORTED_SCHEMA)
        if (payload.profileId != expectedProfileId || payload.data.profile.profileId != expectedProfileId) {
            failMessage(L10nKey.BACKUP_DIFFERENT_PROFILE)
        }
        return payload.data
    }

    companion object { const val SCHEMA_VERSION = 1 }
}
