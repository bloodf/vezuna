package app.vezuna.health

import app.vezuna.domain.ProfileVitals
import app.vezuna.data.StorageSchemaTooNewException
import app.vezuna.security.VaultWriteResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import app.vezuna.security.CipherEnvelopeCodec
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

    suspend fun load(profileId: String, onUnreadable: (Throwable) -> Unit = {}): ProfileVitals? =
        loadStored(profileId, onUnreadable)?.value?.clinical

    internal suspend fun loadStored(profileId: String, onUnreadable: (Throwable) -> Unit = {}): StoredVitalsRead? {
        try {
            val generation = vault.readGeneration(key(profileId)) ?: return null
            // Inspect both before recovery or any rotation. A future previous generation is evidence too.
            val previous = vault.readPrevious(key(profileId))
            previous?.let { refuseFuture(profileId, it) }
            return StoredVitalsRead(decode(profileId, generation.bytes), generation.previous)
        } catch (error: CancellationException) {
            throw error
        } catch (error: StorageSchemaTooNewException) {
            throw error
        } catch (error: Exception) {
            if (error is app.vezuna.security.VaultUnreadableException && error.failure != app.vezuna.security.VaultFailure.CORRUPT) throw error
            onUnreadable(error)
            val previous = vault.readPrevious(key(profileId))
            val recovered = previous?.let {
                try { decode(profileId, it) } catch (future: StorageSchemaTooNewException) { throw future }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
            }
            if (!vault.quarantineCurrent(key(profileId))) blocked += profileId
            return recovered?.let { StoredVitalsRead(it, true) }
        }
    }

    private suspend fun plaintext(profileId: String, bytes: ByteArray): String =
        vault.decrypt(CipherEnvelopeCodec.decode(bytes), associatedData(profileId)).decodeToString()

    private suspend fun refuseFuture(profileId: String, bytes: ByteArray) {
        val document = try { json.parseToJsonElement(plaintext(profileId, bytes)) as? JsonObject }
            catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { return }
        if (document != null && "vitalsFormat" in document) {
            val version = (document["vitalsFormat"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
            if (version != 1 || document.keys.any { it !in setOf("vitalsFormat", "commitId", "revision", "clinical", "local") }) throw StorageSchemaTooNewException(version ?: Int.MAX_VALUE, 1)
            try { json.decodeFromJsonElement(StoredVitals.serializer(), document) }
            catch (_: kotlinx.serialization.SerializationException) { throw StorageSchemaTooNewException(Int.MAX_VALUE, 1) }
        }
    }

    private suspend fun decode(profileId: String, bytes: ByteArray): StoredVitals {
        refuseFuture(profileId, bytes)
        val plain = plaintext(profileId, bytes)
        val document = json.parseToJsonElement(plain) as? JsonObject ?: error("Invalid stored vitals")
        return if ("vitalsFormat" in document) try { json.decodeFromString(StoredVitals.serializer(), plain) }
            catch (_: kotlinx.serialization.SerializationException) { throw StorageSchemaTooNewException(Int.MAX_VALUE, 1) }
            else StoredVitals(clinical = json.decodeFromString(ProfileVitals.serializer(), plain))
    }

    internal suspend fun assertPreservable(profileId: String, allowBlocked: Boolean = false) {
        vault.readGeneration(key(profileId))?.let { refuseFuture(profileId, it.bytes) }
        vault.readPrevious(key(profileId))?.let { refuseFuture(profileId, it) }
        check(allowBlocked || profileId !in blocked) { "Stored vitals are blocked; refusing to overwrite them" }
    }

    /** Legacy callers keep the entire local state. Paging uses [commit] for classified acknowledgement. */
    suspend fun save(profileId: String, vitals: ProfileVitals) { saveLegacy(profileId, vitals) }

    /** No cursor/provenance input and no transactional receipt. Keeps local state exactly as read. */
    internal suspend fun saveLegacy(profileId: String, vitals: ProfileVitals): StoredVitals {
        val old = loadStored(profileId)?.value ?: StoredVitals()
        assertPreservable(profileId)
        val next = old.copy(clinical = vitals, revision = old.revision + 1, commitId = kotlin.uuid.Uuid.random().toString())
        writeIndex(readIndex() + profileId)
        try { vault.write(key(profileId), encode(profileId, next)) }
        catch (error: Exception) { blocked += profileId; throw error }
        return next
    }

    internal suspend fun acknowledgeCurrent(profileId: String): Boolean {
        assertPreservable(profileId, allowBlocked = true)
        return vault.supportsAcknowledgedWrites && vault.acknowledgeCurrent(key(profileId)) == VaultWriteResult.Acknowledged
    }

    internal fun clearResolvedBlock(profileId: String) { blocked -= profileId }

    internal suspend fun commit(profileId: String, value: StoredVitals): VaultWriteResult {
        assertPreservable(profileId)
        if (!vault.supportsAcknowledgedWrites) return VaultWriteResult.Rejected(IllegalStateException("Transactional acknowledgement unsupported"))
        val bytes = encode(profileId, value)
        // Discovery metadata precedes the sole authoritative data/cursor write.
        try { writeIndex(readIndex() + profileId) }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { return VaultWriteResult.Rejected(error) }
        val result = try { vault.writeAcknowledged(key(profileId), bytes) }
        catch (error: Exception) { VaultWriteResult.Indeterminate(error) }
        if (result is VaultWriteResult.Indeterminate) blocked += profileId
        return result
    }

    private suspend fun encode(profileId: String, value: StoredVitals): ByteArray =
        CipherEnvelopeCodec.encode(vault.encrypt(json.encodeToString(StoredVitals.serializer(), value).encodeToByteArray(), associatedData(profileId)))

    suspend fun delete(profileId: String) {
        assertPreservable(profileId, allowBlocked = true)
        vault.delete(key(profileId))
        blocked -= profileId
        writeIndex(readIndex() - profileId)
    }

    /** Profile IDs that have a vitals file. */
    suspend fun ids(): Set<String> = readIndex()

    internal suspend fun assertAllPreservable() {
        val ids = readIndex() + vault.listKeys().orEmpty().filter {
            it.startsWith("repository-vitals-") && it.endsWith("-v1") && it != INDEX_KEY
        }.map { it.removePrefix("repository-vitals-").removeSuffix("-v1") }
        ids.forEach { assertPreservable(it) }
    }

    /** Deletes vitals files of profiles that no longer exist (left by a delete that failed or was interrupted). */
    suspend fun sweep(existingProfileIds: Set<String>) {
        // Scan actual keys too: future files must survive a missing/corrupt discovery index.
        val indexed = readIndex()
        val candidates = indexed + vault.listKeys().orEmpty().mapNotNull { storedKey ->
            storedKey.removePrefix("repository-vitals-").removeSuffix("-v1")
                .takeIf { storedKey.startsWith("repository-vitals-") && storedKey.endsWith("-v1") && storedKey != INDEX_KEY }
        }
        candidates.forEach { assertPreservable(it) }
        // Enumeration protects future bytes; it never grants new deletion authority over unindexed clinical files.
        (indexed - existingProfileIds).forEach { delete(it) }
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
