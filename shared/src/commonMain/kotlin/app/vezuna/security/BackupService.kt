package app.vezuna.security

import app.vezuna.domain.ProfileData
import app.vezuna.domain.healthRecords
import app.vezuna.domain.SourceDocument
import app.vezuna.domain.allDocuments
import app.vezuna.domain.isInternallyConsistent
import app.vezuna.localization.L10nKey
import app.vezuna.localization.failMessage
import app.vezuna.platform.currentInstant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
private const val MAX_PAGES = 10

/** Every file ID a document can have in a backup or in the vault: its first page and each further page. */
fun SourceDocument.fileIds(): List<String> = (1..1 + pageVaultKeys.size).map { backupFileId(documentId, it) }

/** One profile of a household backup plus its original source files, keyed by document ID. */
class BackupMember(val data: ProfileData, val files: Map<String, ByteArray> = emptyMap(), val omitted: List<OmittedDocument> = emptyList())

/** A vault document whose file bytes are absent (#100). Its record, name and tags remain in [ProfileData]. */
@Serializable
data class OmittedDocument(val documentId: String, val displayName: String)

@Serializable
private data class BackupFileEntry(val documentId: String, val content: String)

/** [omitted] is written only when something was left out, so every other file keeps the exact shape older builds read. */
@Serializable
private data class BackupMemberEntry(
    val data: ProfileData,
    val files: List<BackupFileEntry> = emptyList(),
    @OptIn(ExperimentalSerializationApi::class) @EncodeDefault(EncodeDefault.Mode.NEVER) val omitted: List<OmittedDocument> = emptyList(),
)

/** Schema 4 (5 when vault documents or health records are present, 6 with appointments): several profiles, each with vitals (inside [ProfileData]) and its original source files. */
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
        val plain = if (profileData.needsNewerSchema()) {
            json.encodeToString(HouseholdPayload.serializer(), HouseholdPayload(profileData.householdSchema(), currentInstant(), listOf(BackupMemberEntry(profileData))))
        } else {
            json.encodeToString(BackupPayload.serializer(), BackupPayload(SINGLE_SCHEMA_VERSION, profileData.profile.profileId, currentInstant(), profileData))
        }.encodeToByteArray()
        try {
            if (plain.size.toLong() + SEAL_OVERHEAD_BYTES > MAX_BACKUP_WRITE_BYTES) failMessage(L10nKey.BACKUP_TOO_LARGE)
            PassphraseBackupCodec.seal(plain, passphrase)
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Backs up every profile with its vitals and original source files (schema 4, 5 or 6 when see [householdSchema]). The file must stay under
     * [MAX_BACKUP_WRITE_BYTES] (files are Base64 inside the sealed payload); beyond that, [L10nKey.BACKUP_TOO_LARGE] and nothing is written.
     */
    @OptIn(ExperimentalEncodingApi::class)
    suspend fun createHousehold(members: List<BackupMember>, passphrase: CharArray): ByteArray = withContext(Dispatchers.Default) {
        require(members.isNotEmpty()) { "Nothing to back up" }
        val estimate = members.sumOf { member -> member.files.values.sumOf { (it.size + 2L) / 3 * 4 } }
        if (estimate > MAX_BACKUP_WRITE_BYTES) failMessage(L10nKey.BACKUP_TOO_LARGE)
        val payload = HouseholdPayload(
            members.maxOf { if (it.omitted.isNotEmpty()) OMISSION_SCHEMA_VERSION else it.data.householdSchema() }, currentInstant(),
            members.map { member ->
                BackupMemberEntry(member.data, member.files.map { (documentId, bytes) -> BackupFileEntry(documentId, Base64.encode(bytes)) }, member.omitted)
            },
        )
        val plain = json.encodeToString(HouseholdPayload.serializer(), payload).encodeToByteArray()
        try {
            if (plain.size.toLong() + SEAL_OVERHEAD_BYTES > MAX_BACKUP_WRITE_BYTES) failMessage(L10nKey.BACKUP_TOO_LARGE)
            PassphraseBackupCodec.seal(plain, passphrase).also { if (it.size > MAX_BACKUP_WRITE_BYTES) failMessage(L10nKey.BACKUP_TOO_LARGE) }
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Bytes a household backup of [members] occupies besides the Base64 of its files: the records, the framing of each file
     * entry (give every file an empty array), the omission records and the seal's header, nonce and tag. Nothing is read or
     * encrypted, so it is cheap; a backup fits when this plus the encoded files is at most [MAX_BACKUP_WRITE_BYTES].
     */
    fun fixedOverhead(members: List<BackupMember>): Long {
        val payload = HouseholdPayload(
            OMISSION_SCHEMA_VERSION, currentInstant(),
            members.map { m -> BackupMemberEntry(m.data, m.files.keys.map { BackupFileEntry(it, "") }, m.omitted) },
        )
        return json.encodeToString(HouseholdPayload.serializer(), payload).encodeToByteArray().size.toLong() + SEAL_OVERHEAD_BYTES
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
                // The profile id is only known from the payload, so the prefix is checked here and the full value after decoding.
                if (!envelope.associatedData.decodeToString().startsWith(LEGACY_BACKUP_AAD_PREFIX)) failMessage(L10nKey.BACKUP_INVALID_CONTENT)
                val plain = try {
                    localVault.decrypt(envelope)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    failMessage(L10nKey.BACKUP_SAME_DEVICE_UNAVAILABLE)
                }
                val members = decodePayload(plain)
                // The associated data names the profile the old service bound the file to.
                val expected = "$LEGACY_BACKUP_AAD_PREFIX${members.first().data.profile.profileId}".encodeToByteArray()
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
                SCHEMA_VERSION, NEWER_SCHEMA_VERSION, APPOINTMENTS_SCHEMA_VERSION, OMISSION_SCHEMA_VERSION -> json.decodeFromJsonElement(HouseholdPayload.serializer(), element).members.map { entry ->
                    if (entry.files.map { it.documentId }.toSet().size != entry.files.size) failMessage(L10nKey.BACKUP_INVALID_CONTENT)
                    BackupMember(entry.data, entry.files.associate { it.documentId to Base64.decode(it.content) }, entry.omitted)
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
        if (data.allDocuments().any { PAGE_LIKE_ID.matches(it.documentId) || it.pageVaultKeys.size > MAX_PAGES }) return false
        val allowed = data.allDocuments().flatMapTo(HashSet()) { it.fileIds() }
        val vaultDocuments = data.vaultDocuments.associateBy { it.source.documentId }
        return files.keys.all { it in allowed } && omitted.size <= MAX_OMITTED_DOCUMENTS && omitted.all { omission ->
            val document = vaultDocuments[omission.documentId]?.source
            document != null && omission.displayName.isNotBlank() &&
                omission.displayName == app.vezuna.domain.DocumentTags.cleanName(omission.displayName) &&
                omission.displayName == app.vezuna.domain.DocumentTags.cleanName(document.displayName) &&
                document.fileIds().none { it in files }
        } && omitted.map { it.documentId }.toSet().size == omitted.size
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
         * 4 is the household payload (several profiles, original source files). 5 is the household payload written when a profile has vault documents or health records; 6 when a profile has appointments. Versions 1-7 are all accepted.
         */
        private const val LEGACY_BACKUP_AAD_PREFIX = "Vezuna backup v1:"
        const val SCHEMA_VERSION = 4

        /**
         * Household payload written only when a profile holds vault documents or health records (#96): builds that predate
         * them then answer "unsupported" instead of "damaged". Everything else is still written as 3 or 4. Versions 1-5 are accepted.
         */
        const val NEWER_SCHEMA_VERSION = 5

        /** Household payload written only when a profile holds appointments (#101); everything else keeps its lower number. Versions 1-6 are accepted. */
        const val APPOINTMENTS_SCHEMA_VERSION = 6

        /**
         * What [create] writes for a profile without vault documents or health records; with them it writes
         * [NEWER_SCHEMA_VERSION], or [APPOINTMENTS_SCHEMA_VERSION] with appointments (see [needsNewerSchema]), so older builds answer "unsupported" instead of "damaged".
         */
        const val SINGLE_SCHEMA_VERSION = 3
        /**
         * Household payload written only when a profile has documents left out of it (#100). Older builds cannot read the
         * omission record, so they answer "unsupported" instead of "damaged" or silently dropping it. Versions 1-7 are accepted.
         */
        const val OMISSION_SCHEMA_VERSION = 7

        /** Files from released builds can be this large (64 MiB), so opening still accepts them. */
        const val MAX_BACKUP_READ_BYTES = 64 * 1024 * 1024

        /** New files stay smaller: the payload is held in memory several times over (bytes, Base64, JSON, plaintext, ciphertext). */
        const val MAX_BACKUP_WRITE_BYTES = 24 * 1024 * 1024
        const val MAX_OMITTED_DOCUMENTS = 100
        const val FILE_EXTENSION = "vhbak"

        /** Magic, version, key derivation, iterations, salt, nonce and authentication tag of [PassphraseBackupCodec], rounded up, plus a safety margin. */
        private const val SEAL_OVERHEAD_BYTES = 4 * 1024
    }
}

private fun ProfileData.needsNewerSchema(): Boolean = vaultDocuments.isNotEmpty() || healthRecords.isNotEmpty() || appointments.isNotEmpty()

/** The household payload version this profile needs: 6 with appointments, 5 with vault documents or health records, else 4. */
private fun ProfileData.householdSchema(): Int = when {
    appointments.isNotEmpty() -> BackupService.APPOINTMENTS_SCHEMA_VERSION
    needsNewerSchema() -> BackupService.NEWER_SCHEMA_VERSION
    else -> BackupService.SCHEMA_VERSION
}
