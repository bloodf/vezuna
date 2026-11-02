package app.vezuna.security

import app.vezuna.domain.ProfileData
import app.vezuna.domain.SourceDocument
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
data class BackupPayload(
    val schemaVersion: Int,
    val profileId: String,
    val exportedAt: Instant,
    val data: ProfileData,
)

/**
 * File ID of page [page] (1-based) of a document inside [BackupMember.files]. It is also the document ID the
 * source store binds page files to, so the same string is used for loading, backing up and restoring a page.
 */
fun backupFileId(documentId: String, page: Int): String = if (page == 1) documentId else "$documentId-page-$page"

private val PAGE_LIKE_ID = Regex(".*-page-[0-9]+")
private const val MAX_PAGES = 500

/** Every file ID a document can have in a backup or in the vault: its first page and each further page. */
fun SourceDocument.fileIds(): List<String> = (1..1 + pageVaultKeys.size).map { backupFileId(documentId, it) }

/** One profile of a household backup plus its original source files, keyed by document ID. */
class BackupMember(val data: ProfileData, val files: Map<String, ByteArray> = emptyMap())

@Serializable
private data class BackupFileEntry(val documentId: String, val content: String)

@Serializable
private data class BackupMemberEntry(val data: ProfileData, val files: List<BackupFileEntry> = emptyList())

/** Schema 4: several profiles, each with vitals (inside [ProfileData]) and its original source files. */
@Serializable
private data class HouseholdPayload(val schemaVersion: Int, val exportedAt: Instant, val members: List<BackupMemberEntry>)

enum class BackupKind {
    /** Passphrase-encrypted file that opens on any device. */
    PORTABLE,

    /** Older file encrypted with this device's vault key; it only opens on the device that made it. */
    SAME_DEVICE,

    UNKNOWN,
}

class OpenedBackup(val kind: BackupKind, val members: List<BackupMember>) {
    /** The first (for single-profile files, the only) profile. */
    val data: ProfileData get() = members.first().data
}

/**
 * Creates and opens `.vhbak` files. The payload holds whatever [ProfileData] serializes, so new record types flow
 * through without changes here. [vault] is only needed to open legacy same-device files.
 */
class BackupService(
    private val vault: SecureVault? = null,
    private val json: Json = Json { prettyPrint = false; encodeDefaults = true },
) {
    fun kind(file: ByteArray): BackupKind = when {
        file.size > MAX_BACKUP_READ_BYTES -> BackupKind.UNKNOWN
        PassphraseBackupCodec.isPortable(file) -> BackupKind.PORTABLE
        looksLikeLegacyEnvelope(file) -> BackupKind.SAME_DEVICE
        else -> BackupKind.UNKNOWN
    }

    /** Key stretching is deliberately slow, so this runs off the caller's thread. */
    suspend fun create(profileData: ProfileData, passphrase: CharArray): ByteArray = withContext(Dispatchers.Default) {
        val payload = BackupPayload(SINGLE_SCHEMA_VERSION, profileData.profile.profileId, currentInstant(), profileData)
        val plain = json.encodeToString(BackupPayload.serializer(), payload).encodeToByteArray()
        try {
            PassphraseBackupCodec.seal(plain, passphrase)
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Backs up every profile with its vitals and original source files (schema 4). The file must stay under
     * [MAX_BACKUP_WRITE_BYTES] (files are Base64 inside the sealed payload); beyond that, [L10nKey.BACKUP_TOO_LARGE] and nothing is written.
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun createHousehold(members: List<BackupMember>, passphrase: CharArray): ByteArray = withContext(Dispatchers.Default) {
        require(members.isNotEmpty()) { "Nothing to back up" }
        val estimate = members.sumOf { member -> member.files.values.sumOf { (it.size + 2L) / 3 * 4 } }
        if (estimate > MAX_BACKUP_WRITE_BYTES) failMessage(L10nKey.BACKUP_TOO_LARGE)
        val payload = HouseholdPayload(
            SCHEMA_VERSION, currentInstant(),
            members.map { member ->
                BackupMemberEntry(member.data, member.files.map { (documentId, bytes) -> BackupFileEntry(documentId, Base64.encode(bytes)) })
            },
        )
        val plain = json.encodeToString(HouseholdPayload.serializer(), payload).encodeToByteArray()
        try {
            PassphraseBackupCodec.seal(plain, passphrase).also { if (it.size > MAX_BACKUP_WRITE_BYTES) failMessage(L10nKey.BACKUP_TOO_LARGE) }
        } finally {
            plain.fill(0)
        }
    }

    /** [passphrase] is ignored for same-device files. */
    suspend fun open(file: ByteArray, passphrase: CharArray?): OpenedBackup = withContext(Dispatchers.Default) {
        when (val kind = kind(file)) {
            BackupKind.PORTABLE -> {
                if (passphrase == null) failMessage(L10nKey.BACKUP_WRONG_PASSPHRASE)
                OpenedBackup(kind, decodePayload(PassphraseBackupCodec.open(file, passphrase)))
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
                val members = decodePayload(plain)
                // The associated data names the profile the old service bound the file to.
                val expected = "Vezuna backup v1:${members.first().data.profile.profileId}".encodeToByteArray()
                if (!envelope.associatedData.contentEquals(expected)) failMessage(L10nKey.BACKUP_INVALID_CONTENT)
                OpenedBackup(kind, members)
            }
            BackupKind.UNKNOWN -> failMessage(L10nKey.BACKUP_NOT_A_BACKUP)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodePayload(plain: ByteArray): List<BackupMember> {
        try {
            val element = json.parseToJsonElement(plain.decodeToString())
            val version = (element as? JsonObject)?.get("schemaVersion")?.jsonPrimitive?.intOrNull ?: failMessage(L10nKey.BACKUP_INVALID_CONTENT)
            val members = when (version) {
                in 1..SINGLE_SCHEMA_VERSION -> {
                    val payload = json.decodeFromJsonElement(BackupPayload.serializer(), element)
                    if (payload.profileId != payload.data.profile.profileId) failMessage(L10nKey.BACKUP_INVALID_CONTENT)
                    listOf(BackupMember(payload.data))
                }
                SCHEMA_VERSION -> json.decodeFromJsonElement(HouseholdPayload.serializer(), element).members.map { entry ->
                    BackupMember(entry.data, entry.files.associate { it.documentId to Base64.decode(it.content) })
                }
                else -> failMessage(L10nKey.BACKUP_UNSUPPORTED_SCHEMA)
            }
            val ids = members.map { it.data.profile.profileId }
            if (members.isEmpty() || ids.toSet().size != ids.size || members.any { !it.isValid() }) failMessage(L10nKey.BACKUP_INVALID_CONTENT)
            return members
        } catch (error: CancellationException) {
            throw error
        } catch (error: app.vezuna.localization.VitaMessageException) {
            throw error
        } catch (error: Exception) {
            failMessage(L10nKey.BACKUP_INVALID_CONTENT)
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Consistent records, and files only for pages of documents that exist. A document ID that looks like a page file ID
     * ("<id>-page-N") is refused, because it could name another document's page file; page counts are capped.
     */
    private fun BackupMember.isValid(): Boolean {
        if (!data.isInternallyConsistent()) return false
        if (data.documents.any { PAGE_LIKE_ID.matches(it.documentId) || it.pageVaultKeys.size > MAX_PAGES }) return false
        val allowed = data.documents.flatMapTo(HashSet()) { it.fileIds() }
        return files.keys.all { it in allowed }
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
        /**
         * Highest payload version this build reads. 2 added the vitals section; 3 is the last single-profile shape;
         * 4 is the household payload (several profiles, original source files). Versions 1-4 are all accepted.
         */
        const val SCHEMA_VERSION = 4

        /** What [create] still writes, so single-profile files keep opening on builds that predate the household payload. */
        const val SINGLE_SCHEMA_VERSION = 3
        /** Files from released builds can be this large (64 MiB), so opening still accepts them. */
        const val MAX_BACKUP_READ_BYTES = 64 * 1024 * 1024

        /** New files stay smaller: the payload is held in memory several times over (bytes, Base64, JSON, plaintext, ciphertext). */
        const val MAX_BACKUP_WRITE_BYTES = 24 * 1024 * 1024
        const val FILE_EXTENSION = "vhbak"
    }
}
