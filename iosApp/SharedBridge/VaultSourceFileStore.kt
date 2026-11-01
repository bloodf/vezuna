package app.vezuna.ios

import app.vezuna.security.CipherEnvelope
import app.vezuna.security.SecureVault
import app.vezuna.ui.SourceFileStore
import kotlinx.serialization.json.Json

/** Keeps imported source files as encrypted vault entries; the repository deletes them with their document. */
internal class VaultSourceFileStore(private val vault: SecureVault, private val json: Json) : SourceFileStore {
    override suspend fun save(profileId: String, documentId: String, bytes: ByteArray): String {
        val key = "source-document-$documentId"
        val envelope = vault.encrypt(bytes, "Vezuna source v1:$profileId:$documentId".encodeToByteArray())
        vault.write(key, json.encodeToString(CipherEnvelope.serializer(), envelope).encodeToByteArray())
        return key
    }

    override suspend fun discard(key: String) {
        vault.delete(key)
    }
}
