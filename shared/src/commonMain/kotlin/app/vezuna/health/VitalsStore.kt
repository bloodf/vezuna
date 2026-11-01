package app.vezuna.health

import app.vezuna.domain.ProfileVitals
import app.vezuna.security.CipherEnvelope
import app.vezuna.security.SecureVault
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * One encrypted vault file of vitals per profile, so ordinary record commits never rewrite them
 * and a vitals sync never rewrites the profile records.
 *
 * An unreadable file is never overwritten: it is moved to quarantine, or, when the vault cannot preserve it,
 * saving for that profile is refused until the app restarts with a readable file.
 */
class VitalsStore(private val vault: SecureVault, private val json: Json) {
    private val blocked = mutableSetOf<String>()

    /** Null when nothing is stored (or the unreadable file was moved aside). [onUnreadable] receives the cause. */
    suspend fun load(profileId: String, onUnreadable: (Throwable) -> Unit = {}): ProfileVitals? = try {
        vault.read(key(profileId))?.let { bytes ->
            val envelope = json.decodeFromString(CipherEnvelope.serializer(), bytes.decodeToString())
            json.decodeFromString(ProfileVitals.serializer(), vault.decrypt(envelope).decodeToString()).migrated()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onUnreadable(error)
        if (!vault.quarantineCurrent(key(profileId))) blocked += profileId
        null
    }

    suspend fun save(profileId: String, vitals: ProfileVitals) {
        check(profileId !in blocked) { "Stored vitals are unreadable and could not be preserved; refusing to overwrite them" }
        // Indexed first: a crash after the file write can then never leave a file nothing knows about.
        writeIndex(readIndex() + profileId)
        val plain = json.encodeToString(ProfileVitals.serializer(), vitals).encodeToByteArray()
        val envelope = vault.encrypt(plain, associatedData(profileId))
        vault.write(key(profileId), json.encodeToString(CipherEnvelope.serializer(), envelope).encodeToByteArray())
    }

    suspend fun delete(profileId: String) {
        vault.delete(key(profileId))
        blocked -= profileId
        writeIndex(readIndex() - profileId)
    }

    /** Deletes vitals files of profiles that no longer exist (left by a delete that failed or was interrupted). */
    suspend fun sweep(existingProfileIds: Set<String>) {
        (readIndex() - existingProfileIds).forEach { delete(it) }
    }

    private suspend fun readIndex(): Set<String> = try {
        vault.read(INDEX_KEY)?.let { json.decodeFromString(SetSerializer(String.serializer()), it.decodeToString()) } ?: emptySet()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        emptySet() // an unreadable index only means an orphan may stay; it never costs data
    }

    private suspend fun writeIndex(ids: Set<String>) =
        vault.write(INDEX_KEY, json.encodeToString(SetSerializer(String.serializer()), ids).encodeToByteArray())

    private fun key(profileId: String) = "repository-vitals-$profileId-v1"
    private fun associatedData(profileId: String) = "Vezuna vitals v1:$profileId".encodeToByteArray()

    private companion object {
        const val INDEX_KEY = "repository-vitals-index-v1"
    }
}
