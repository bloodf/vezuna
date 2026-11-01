package app.vezuna.security

import app.vezuna.domain.ProfileData
import app.vezuna.domain.isInternallyConsistent
import app.vezuna.localization.L10nKey
import app.vezuna.localization.failMessage
import app.vezuna.platform.currentInstant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

enum class BackupKind {
    /** Passphrase-encrypted file that opens on any device. */
    PORTABLE,

    /** Older file encrypted with this device's vault key; it only opens on the device that made it. */
    SAME_DEVICE,

    UNKNOWN,
}

class OpenedBackup(val kind: BackupKind, val data: ProfileData)

/**
 * Creates and opens `.vhbak` files. The payload holds whatever [ProfileData] serializes, so new record types flow
 * through without changes here. [vault] is only needed to open legacy same-device files.
 */
class BackupService(
    private val vault: SecureVault? = null,
    private val json: Json = Json { prettyPrint = false; encodeDefaults = true },
) {
    fun kind(file: ByteArray): BackupKind = when {
        file.size > MAX_BACKUP_BYTES -> BackupKind.UNKNOWN
        PassphraseBackupCodec.isPortable(file) -> BackupKind.PORTABLE
        looksLikeLegacyEnvelope(file) -> BackupKind.SAME_DEVICE
        else -> BackupKind.UNKNOWN
    }

    /** Key stretching is deliberately slow, so this runs off the caller's thread. */
    suspend fun create(profileData: ProfileData, passphrase: CharArray): ByteArray = withContext(Dispatchers.Default) {
        val payload = BackupPayload(SCHEMA_VERSION, profileData.profile.profileId, currentInstant(), profileData)
        val plain = json.encodeToString(BackupPayload.serializer(), payload).encodeToByteArray()
        try {
            PassphraseBackupCodec.seal(plain, passphrase)
        } finally {
            plain.fill(0)
        }
    }

    /** [passphrase] is ignored for same-device files. */
    suspend fun open(file: ByteArray, passphrase: CharArray?): OpenedBackup = withContext(Dispatchers.Default) {
        when (val kind = kind(file)) {
            BackupKind.PORTABLE -> {
                if (passphrase == null) failMessage(L10nKey.BACKUP_WRONG_PASSPHRASE)
                OpenedBackup(kind, decodePayload(PassphraseBackupCodec.open(file, passphrase)).data)
            }
            BackupKind.SAME_DEVICE -> {
                val localVault = vault ?: failMessage(L10nKey.BACKUP_SAME_DEVICE_UNAVAILABLE)
                val envelope = json.decodeFromString(CipherEnvelope.serializer(), file.decodeToString())
                val plain = try {
                    localVault.decrypt(envelope)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failMessage(L10nKey.BACKUP_SAME_DEVICE_UNAVAILABLE)
                }
                val payload = decodePayload(plain)
                // The associated data names the profile the old service bound the file to.
                val expected = "Vezuna backup v1:${payload.profileId}".encodeToByteArray()
                if (!envelope.associatedData.contentEquals(expected)) failMessage(L10nKey.BACKUP_INVALID_CONTENT)
                OpenedBackup(kind, payload.data)
            }
            BackupKind.UNKNOWN -> failMessage(L10nKey.BACKUP_NOT_A_BACKUP)
        }
    }

    private fun decodePayload(plain: ByteArray): BackupPayload {
        val payload = try {
            json.decodeFromString(BackupPayload.serializer(), plain.decodeToString())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failMessage(L10nKey.BACKUP_INVALID_CONTENT)
        } finally {
            plain.fill(0)
        }
        if (payload.schemaVersion !in ACCEPTED_SCHEMA_VERSIONS) failMessage(L10nKey.BACKUP_UNSUPPORTED_SCHEMA)
        if (payload.profileId != payload.data.profile.profileId || !payload.data.isInternallyConsistent()) {
            failMessage(L10nKey.BACKUP_INVALID_CONTENT)
        }
        return payload
    }

    private fun looksLikeLegacyEnvelope(file: ByteArray): Boolean {
        if (file.firstOrNull()?.toInt() != '{'.code) return false
        return try {
            json.decodeFromString(CipherEnvelope.serializer(), file.decodeToString())
            true
        } catch (error: Exception) {
            false
        }
    }

    companion object {
        /** 2 adds the vitals section to the profile data; version 1 payloads (no vitals) are still accepted. */
        const val SCHEMA_VERSION = 2
        private val ACCEPTED_SCHEMA_VERSIONS = 1..SCHEMA_VERSION
        const val MAX_BACKUP_BYTES = 64 * 1024 * 1024
        const val FILE_EXTENSION = "vhbak"
    }
}
