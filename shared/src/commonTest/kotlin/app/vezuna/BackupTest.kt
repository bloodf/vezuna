package app.vezuna

import app.vezuna.data.InMemoryVezunaRepository
import app.vezuna.domain.ChatMessage
import app.vezuna.domain.DoseEvent
import app.vezuna.domain.DoseSchedule
import app.vezuna.domain.LabObservation
import app.vezuna.domain.LabReport
import app.vezuna.domain.Medication
import app.vezuna.domain.OcrCandidate
import app.vezuna.domain.OcrRevision
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.Recurrence
import app.vezuna.domain.SourceDocument
import app.vezuna.domain.isInternallyConsistent
import app.vezuna.domain.keepingOnlyAvailableSourceFiles
import app.vezuna.domain.remappedAsNew
import app.vezuna.localization.L10nKey
import app.vezuna.localization.VitaMessageException
import app.vezuna.security.BackupKind
import app.vezuna.security.BackupService
import app.vezuna.security.PassphraseBackupCodec
import app.vezuna.security.PassphraseStrength
import app.vezuna.security.PassphraseStrengthLevel
import app.vezuna.security.aesGcmDecrypt
import app.vezuna.security.aesGcmEncrypt
import app.vezuna.security.pbkdf2HmacSha256
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun hex(value: String) = ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
private val at = Instant.parse("2026-01-01T00:00:00Z")
private const val PASS = "correct horse battery"

internal fun sampleProfileData(prefix: String = "OLD"): ProfileData {
    val p = "$prefix-profile"
    val schedule = DoseSchedule(p, "$prefix-schedule", "$prefix-med", Recurrence.DAILY, startDate = LocalDate(2026, 1, 1), timesLocal = listOf("08:00"))
    return ProfileData(
        profile = Profile(p, "Ana", createdAt = at),
        documents = listOf(SourceDocument(p, "$prefix-doc", "lab.pdf", "application/pdf", "abc", at, vaultKey = "source-$prefix-doc")),
        reports = listOf(LabReport(p, "$prefix-report", "$prefix-doc", importedAt = at)),
        observations = listOf(
            LabObservation(p, "$prefix-obs", "$prefix-report", "GLU", "Glucose", 90.0, "mg/dL", 90.0, "mg/dL", app.vezuna.domain.ReferenceRange(70.0, 99.0), at),
        ),
        ocrCandidates = listOf(
            OcrCandidate(p, "$prefix-cand", "$prefix-doc", "GLU", "Glucose", "90", "mg/dL", collectedAt = at,
                revisions = listOf(OcrRevision(p, "$prefix-rev", at, "9O", "90"))),
        ),
        medications = listOf(Medication(p, "$prefix-med", "Metformin", schedules = listOf(schedule))),
        doseEvents = listOf(DoseEvent(p, "$prefix-event", "$prefix-med", "$prefix-schedule", at)),
        chat = listOf(ChatMessage(p, "$prefix-msg", ChatMessage.Role.USER, "hi", at)),
    )
}

@OptIn(ExperimentalEncodingApi::class)
class BackupCryptoVectorsTest {
    @Test
    fun pbkdf2MatchesRfc7914Vectors() {
        assertContentEquals(
            hex("55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783"),
            pbkdf2HmacSha256("passwd".encodeToByteArray(), "salt".encodeToByteArray(), 1, 64),
        )
        assertContentEquals(
            hex("4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d"),
            pbkdf2HmacSha256("Password".encodeToByteArray(), "NaCl".encodeToByteArray(), 80_000, 64),
        )
    }

    @Test
    fun pbkdf2MatchesProductionParameterVector() {
        // Generated with Python hashlib.pbkdf2_hmac('sha256', ..., 600000, 32)
        assertContentEquals(
            hex("bb06c8c0b1dd5bfd4e40f4e297a2d0e64da7ef94b4b8ec20989021c8b41536ad"),
            pbkdf2HmacSha256("correct horse battery".encodeToByteArray(), ByteArray(16) { it.toByte() }, 600_000, 32),
        )
    }

    @Test
    fun pbkdf2NonAsciiNfcVector() {
        // Python: hashlib.pbkdf2_hmac('sha256', NFC('p\u00e4ssw\u00f6rd').encode(), b'salt', 4096, 32)
        assertContentEquals(
            hex(GoldenBackup.PBKDF2_NON_ASCII_4096_HEX),
            pbkdf2HmacSha256("p\u00e4ssw\u00f6rd".encodeToByteArray(), "salt".encodeToByteArray(), 4096, 32),
        )
    }

    @Test
    fun nfdAndNfcPassphrasesDeriveTheSameKey() {
        val salt = ByteArray(16) { it.toByte() }
        val nfc = PassphraseBackupCodec.deriveKey(GoldenBackup.PASSPHRASE_NFC.toCharArray(), salt, 600_000)
        val nfd = PassphraseBackupCodec.deriveKey(GoldenBackup.PASSPHRASE_NFD.toCharArray(), salt, 600_000)
        assertContentEquals(hex(GoldenBackup.KEY_HEX), nfc)
        assertContentEquals(nfc, nfd)
    }

    @Test
    fun goldenBackupOpensWithEitherUnicodeForm() {
        val file = Base64.decode(GoldenBackup.FILE_BASE64)
        for (passphrase in listOf(GoldenBackup.PASSPHRASE_NFC, GoldenBackup.PASSPHRASE_NFD)) {
            val payload = PassphraseBackupCodec.open(file, passphrase.toCharArray()).decodeToString()
            assertTrue(GoldenBackup.PROFILE_ID in payload)
        }
    }

    @Test
    fun aesGcmMatchesNistVector() {
        val key = hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
        val nonce = hex("cafebabefacedbaddecaf888")
        val aad = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
        val plain = hex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39")
        val sealed = hex(
            "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662" +
                "76fc6ece0f4e1768cddf8853bb2d551b",
        )
        assertContentEquals(plain, aesGcmDecrypt(key, nonce, sealed, aad))
        assertFailsWith<Exception> { aesGcmDecrypt(key, nonce, sealed, aad.copyOf().also { it[0] = 0 }) }
    }

    @Test
    fun aesGcmRoundTripUsesFreshNonces() {
        val key = ByteArray(32) { it.toByte() }
        val a = aesGcmEncrypt(key, "x".encodeToByteArray(), byteArrayOf(1))
        val b = aesGcmEncrypt(key, "x".encodeToByteArray(), byteArrayOf(1))
        assertEquals(12, a.nonce.size)
        assertFalse(a.nonce.contentEquals(b.nonce))
        assertContentEquals("x".encodeToByteArray(), aesGcmDecrypt(key, a.nonce, a.cipherTextAndTag, byteArrayOf(1)))
    }
}

class PassphraseBackupCodecTest {
    private val plain = "secret records".encodeToByteArray()
    private val sealed = PassphraseBackupCodec.seal(plain, PASS.toCharArray())

    private fun key(block: () -> Unit): L10nKey = assertFailsWith<VitaMessageException> { block() }.userText.key

    @Test
    fun roundTripAndHeaderLayout() {
        assertContentEquals(plain, PassphraseBackupCodec.open(sealed, PASS.toCharArray()))
        assertEquals("VZBK", sealed.copyOfRange(0, 4).decodeToString())
        assertEquals(1, sealed[4].toInt())
        assertEquals(1, sealed[5].toInt())
        assertEquals(600_000, ((sealed[6].toInt() and 0xff) shl 24) or ((sealed[7].toInt() and 0xff) shl 16) or ((sealed[8].toInt() and 0xff) shl 8) or (sealed[9].toInt() and 0xff))
        assertEquals(16, sealed[10].toInt())
        assertEquals(12, sealed[27].toInt())
        assertEquals(28 + 12 + plain.size + 16, sealed.size)
    }

    @Test
    fun sealingTwiceDiffers() {
        assertFalse(sealed.contentEquals(PassphraseBackupCodec.seal(plain, PASS.toCharArray())))
    }

    @Test
    fun wrongPassphraseIsRejectedWithoutCrash() {
        assertEquals(L10nKey.BACKUP_WRONG_PASSPHRASE, key { PassphraseBackupCodec.open(sealed, "wrong passphrase!".toCharArray()) })
    }

    @Test
    fun tamperedCiphertextAndTagAreRejected() {
        for (index in listOf(40, sealed.size - 1)) {
            val copy = sealed.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertEquals(L10nKey.BACKUP_WRONG_PASSPHRASE, key { PassphraseBackupCodec.open(copy, PASS.toCharArray()) })
        }
    }

    @Test
    fun tamperedHeaderFieldsAreRejected() {
        // salt, nonce (iteration count changes are covered by the parser/KDF; nonce+salt exercise the AEAD binding)
        for (index in listOf(11, 20, 28, 35)) {
            val copy = sealed.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertEquals(L10nKey.BACKUP_WRONG_PASSPHRASE, key { PassphraseBackupCodec.open(copy, PASS.toCharArray()) })
        }
    }

    @Test
    fun unsupportedOrMalformedHeadersAreRejectedBeforeKeyDerivation() {
        assertEquals(L10nKey.BACKUP_UNSUPPORTED_VERSION, key { PassphraseBackupCodec.open(sealed.copyOf().also { it[4] = 2 }, PASS.toCharArray()) })
        assertEquals(L10nKey.BACKUP_UNSUPPORTED_VERSION, key { PassphraseBackupCodec.open(sealed.copyOf().also { it[5] = 9 }, PASS.toCharArray()) })
        // iterations downgraded to 1 (or inflated into a denial of service) are refused outright
        assertEquals(L10nKey.BACKUP_UNSUPPORTED_VERSION, key { PassphraseBackupCodec.open(sealed.copyOf().also { it[9] = 1; it[8] = 0; it[7] = 0; it[6] = 0 }, PASS.toCharArray()) })
        assertEquals(L10nKey.BACKUP_UNSUPPORTED_VERSION, key { PassphraseBackupCodec.open(sealed.copyOf().also { it[6] = 0x7f }, PASS.toCharArray()) })
        assertEquals(L10nKey.BACKUP_NOT_A_BACKUP, key { PassphraseBackupCodec.open(sealed.copyOf(20), PASS.toCharArray()) })
        assertEquals(L10nKey.BACKUP_NOT_A_BACKUP, key { PassphraseBackupCodec.open("hello".encodeToByteArray(), PASS.toCharArray()) })
        assertEquals(L10nKey.BACKUP_NOT_A_BACKUP, key { PassphraseBackupCodec.open(ByteArray(0), PASS.toCharArray()) })
    }

    @Test
    fun shortPassphraseIsRefused() {
        assertEquals(L10nKey.BACKUP_PASSPHRASE_TOO_SHORT, key { PassphraseBackupCodec.seal(plain, "short".toCharArray()) })
    }

    @Test
    fun strengthHints() {
        assertEquals(PassphraseStrengthLevel.TOO_SHORT, PassphraseStrength.evaluate("short".toCharArray()))
        assertEquals(PassphraseStrengthLevel.TOO_SHORT, PassphraseStrength.evaluate("            ".toCharArray()))
        assertEquals(PassphraseStrengthLevel.FAIR, PassphraseStrength.evaluate("alllowercase".toCharArray()))
        assertEquals(PassphraseStrengthLevel.STRONG, PassphraseStrength.evaluate("Mixed-Case-12".toCharArray()))
        assertEquals(PassphraseStrengthLevel.STRONG, PassphraseStrength.evaluate("a much longer phrase".toCharArray()))
    }
}

class BackupServiceTest {
    private val service = BackupService()
    private val data = sampleProfileData()

    @Test
    fun roundTripPreservesEveryRecord() = runTest {
        val file = service.create(data, PASS.toCharArray())
        assertEquals(BackupKind.PORTABLE, service.kind(file))
        val opened = service.open(file, PASS.toCharArray())
        assertEquals(BackupKind.PORTABLE, opened.kind)
        assertEquals(data, opened.data)
    }

    @Test
    fun wrongPassphraseAndMissingPassphraseAreUserErrors() = runTest {
        val file = service.create(data, PASS.toCharArray())
        assertEquals(L10nKey.BACKUP_WRONG_PASSPHRASE, assertFailsWith<VitaMessageException> { service.open(file, "not the passphrase".toCharArray()) }.userText.key)
        assertEquals(L10nKey.BACKUP_WRONG_PASSPHRASE, assertFailsWith<VitaMessageException> { service.open(file, null) }.userText.key)
    }

    @Test
    fun unknownFilesAreRejected() = runTest {
        assertEquals(BackupKind.UNKNOWN, service.kind("{}".encodeToByteArray()))
        assertEquals(BackupKind.UNKNOWN, service.kind(ByteArray(0)))
        assertEquals(L10nKey.BACKUP_NOT_A_BACKUP, assertFailsWith<VitaMessageException> { service.open("nope".encodeToByteArray(), null) }.userText.key)
    }

    @Test
    fun legacyEnvelopeWithoutVaultIsReportedAsSameDevice() = runTest {
        val legacy = """{"algorithm":"AES-256-GCM","keyId":"k","iv":[1],"cipherText":[2],"associatedData":[3]}""".encodeToByteArray()
        assertEquals(BackupKind.SAME_DEVICE, service.kind(legacy))
        assertEquals(L10nKey.BACKUP_SAME_DEVICE_UNAVAILABLE, assertFailsWith<VitaMessageException> { service.open(legacy, null) }.userText.key)
    }
}

class ProfileRestoreTest {
    private val data = sampleProfileData()
    private val json = Json { encodeDefaults = true }

    @Test
    fun restoreAsNewRemapsEveryIdAndKeepsReferencesIntact() {
        var counter = 0
        val remapped = data.remappedAsNew { "NEW-${counter++}" }
        val text = json.encodeToString(ProfileData.serializer(), remapped)
        assertFalse("OLD-" in text, "old IDs leaked: $text")
        assertTrue(remapped.isInternallyConsistent())
        assertNotEqualsId(data.profile.profileId, remapped.profile.profileId)
        assertEquals(remapped.profile.profileId, remapped.documents.single().profileId)
        assertEquals(remapped.documents.single().documentId, remapped.reports.single().sourceDocumentId)
        assertEquals(remapped.reports.single().reportId, remapped.observations.single().reportId)
        assertEquals(remapped.medications.single().schedules.single().scheduleId, remapped.doseEvents.single().scheduleId)
        assertNull(remapped.documents.single().vaultKey)
        assertEquals(data.observations.single().originalValue, remapped.observations.single().originalValue)
        assertEquals(data.ocrCandidates.single().revisions.size, remapped.ocrCandidates.single().revisions.size)
    }

    private fun assertNotEqualsId(a: String, b: String) = assertFalse(a == b)

    @Test
    fun restoreAsNewNeverCollidesWithTheExistingProfile() = runTest {
        val repository = InMemoryVezunaRepository()
        repository.restoreProfile(data)
        var counter = 0
        repository.restoreProfile(data.remappedAsNew { "NEW-${counter++}" })
        assertEquals(2, repository.profiles.value.size)
        assertEquals(data, repository.readProfile(data.profile.profileId))
    }

    @Test
    fun replaceLeavesNoStaleRecords() = runTest {
        val repository = InMemoryVezunaRepository()
        val extra = data.copy(chat = data.chat + ChatMessage(data.profile.profileId, "OLD-extra", ChatMessage.Role.USER, "later", at))
        repository.restoreProfile(extra)
        repository.restoreProfile(data)
        assertEquals(data, repository.readProfile(data.profile.profileId))
        assertEquals(1, repository.profiles.value.size)
    }

    @Test
    fun missingSourceFilesAreDroppedUnlessThePlacedProfileStillHasThem() {
        assertNull(data.keepingOnlyAvailableSourceFiles(null).documents.single().vaultKey)
        assertEquals("source-OLD-doc", data.keepingOnlyAvailableSourceFiles(data).documents.single().vaultKey)
        assertNull(data.keepingOnlyAvailableSourceFiles(data.copy(documents = emptyList())).documents.single().vaultKey)
    }

    @Test
    fun duplicateIdsAndForeignRevisionsAreRejected() {
        assertFalse(data.copy(chat = data.chat + data.chat).isInternallyConsistent())
        assertFalse(data.copy(documents = data.documents + data.documents).isInternallyConsistent())
        val candidate = data.ocrCandidates.single()
        val foreignRevision = candidate.copy(revisions = listOf(candidate.revisions.single().copy(profileId = "other")))
        assertFalse(data.copy(ocrCandidates = listOf(foreignRevision)).isInternallyConsistent())
    }

    @Test
    fun inconsistentBackupsAreDetected() {
        val foreign = data.copy(chat = listOf(data.chat.single().copy(profileId = "someone-else")))
        assertFalse(foreign.isInternallyConsistent())
        assertFalse(data.copy(reports = listOf(data.reports.single().copy(sourceDocumentId = "missing"))).isInternallyConsistent())
    }
}
