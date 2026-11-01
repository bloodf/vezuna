package app.vezuna.health

import app.vezuna.domain.ProfileVitals
import app.vezuna.security.CipherEnvelope
import app.vezuna.security.SecureVault
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * One encrypted vault file of vitals per profile, so ordinary record commits never rewrite them
 * and a vitals sync never rewrites the profile records.
 */
class VitalsStore(private val vault: SecureVault, private val json: Json) {
    /** Null when nothing is stored. An unreadable file is reported through [onUnreadable] and treated as absent. */
    suspend fun load(profileId: String, onUnreadable: (Throwable) -> Unit = {}): ProfileVitals? = try {
        vault.read(key(profileId))?.let { bytes ->
            val envelope = json.decodeFromString(CipherEnvelope.serializer(), bytes.decodeToString())
            json.decodeFromString(ProfileVitals.serializer(), vault.decrypt(envelope).decodeToString()).migrated()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onUnreadable(error)
        null
    }

    suspend fun save(profileId: String, vitals: ProfileVitals) {
        val plain = json.encodeToString(ProfileVitals.serializer(), vitals).encodeToByteArray()
        val envelope = vault.encrypt(plain, associatedData(profileId))
        vault.write(key(profileId), json.encodeToString(CipherEnvelope.serializer(), envelope).encodeToByteArray())
    }

    suspend fun delete(profileId: String) = vault.delete(key(profileId))

    private fun key(profileId: String) = "repository-vitals-$profileId-v1"
    private fun associatedData(profileId: String) = "Vezuna vitals v1:$profileId".encodeToByteArray()
}
