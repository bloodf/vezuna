package app.vezuna.security

import app.vezuna.ui.SourceFileStore
import kotlinx.serialization.json.Json

/** Keeps imported source files as encrypted vault entries; the repository deletes them with their document. */
class VaultSourceFileStore(private val vault: SecureVault, private val json: Json = Json) : SourceFileStore {
    override suspend fun save(profileId: String, documentId: String, bytes: ByteArray): String {
        val key = "source-document-$documentId"
        val envelope = vault.encrypt(bytes, associatedData(profileId, documentId).encodeToByteArray())
        vault.write(key, json.encodeToString(CipherEnvelope.serializer(), envelope).encodeToByteArray())
        return key
    }

    /** The bound profile is authenticated with the file, so a key from another profile never decrypts here. */
    override suspend fun read(profileId: String, key: String): ByteArray? {
        val stored = vault.read(key) ?: return null
        val envelope = json.decodeFromString(CipherEnvelope.serializer(), stored.decodeToString())
        check(envelope.associatedData.decodeToString().startsWith(associatedData(profileId, ""))) { "Source file belongs to another profile" }
        return vault.decrypt(envelope)
    }

    override suspend fun discard(key: String) {
        vault.delete(key)
    }

    private fun associatedData(profileId: String, documentId: String) = "Vezuna source v1:$profileId:$documentId"
}
