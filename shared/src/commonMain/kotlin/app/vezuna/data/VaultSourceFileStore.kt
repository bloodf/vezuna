package app.vezuna.data

import app.vezuna.security.CipherEnvelopeCodec
import app.vezuna.security.SecureVault
import app.vezuna.ui.SourceFileStore

/**
 * Keeps imported source files as encrypted vault entries on both platforms.
 * The repository deletes the entry together with its document (see `ProfileData.vaultKeys`).
 */
class VaultSourceFileStore(private val vault: SecureVault) : SourceFileStore {
    override suspend fun save(profileId: String, documentId: String, bytes: ByteArray): String {
        val key = keyFor(documentId)
        val envelope = vault.encrypt(bytes, associatedData(profileId, documentId))
        vault.write(key, CipherEnvelopeCodec.encode(envelope))
        return key
    }

    override suspend fun discard(key: String) {
        vault.delete(key)
    }

    override suspend fun load(profileId: String, documentId: String, key: String): ByteArray? {
        val stored = vault.read(key) ?: return null
        val envelope = CipherEnvelopeCodec.decode(stored)
        require(envelope.associatedData.contentEquals(associatedData(profileId, documentId))) { "Source file belongs to another document" }
        return vault.decrypt(envelope)
    }

    private fun keyFor(documentId: String) = "source-document-$documentId"
    private fun associatedData(profileId: String, documentId: String) = "Vezuna source v1:$profileId:$documentId".encodeToByteArray()
}
