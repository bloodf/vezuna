package app.vezuna

import app.vezuna.data.StorageSchema
import app.vezuna.data.StorageSchemaTooNewException
import app.vezuna.data.VaultBackedRepository
import app.vezuna.domain.ConfirmationStatus
import app.vezuna.domain.LabObservation
import app.vezuna.domain.Profile
import app.vezuna.domain.ProfileData
import app.vezuna.domain.ReferenceRange
import app.vezuna.security.CipherEnvelope
import app.vezuna.security.CipherEnvelopeCodec
import app.vezuna.security.SecureVault
import app.vezuna.security.VaultFailure
import app.vezuna.security.VaultUnreadableException
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-memory vault with toy crypto and a previous generation per key, like the Android file store. */
internal class GenerationalMemoryVault : SecureVault {
    val current = mutableMapOf<String, ByteArray>()
    val previous = mutableMapOf<String, ByteArray>()
    val quarantined = mutableMapOf<String, ByteArray>()

    override suspend fun encrypt(plainText: ByteArray, associatedData: ByteArray) =
        CipherEnvelope("test-xor", "test-key", byteArrayOf(7), plainText.xor(), associatedData)

    override suspend fun decrypt(envelope: CipherEnvelope): ByteArray {
        if (envelope.algorithm != "test-xor") throw VaultUnreadableException(VaultFailure.CORRUPT)
        return envelope.cipherText.xor()
    }

    override suspend fun write(key: String, ciphertext: ByteArray) {
        current[key]?.let { previous[key] = it }
        current[key] = ciphertext.copyOf()
    }

    override suspend fun read(key: String): ByteArray? = current[key]?.copyOf() ?: previous[key]?.copyOf()
    override suspend fun readPrevious(key: String): ByteArray? = previous[key]?.copyOf()
    override suspend fun delete(key: String) { current.remove(key); previous.remove(key) }
    override suspend fun quarantineCurrent(key: String): Boolean {
        val file = current.remove(key) ?: return false
        quarantined[key] = file
        return true
    }
    override suspend fun quarantineAll(): Boolean {
        quarantined.putAll(current); current.clear(); previous.clear()
        return true
    }

    private fun ByteArray.xor() = ByteArray(size) { (this[it].toInt() xor 0x5a).toByte() }
}

private val t = Instant.parse("2026-01-01T00:00:00Z")
private const val INDEX = "repository-profile-index-v1"
private const val LEGACY = "repository-v1"
private fun profileKey(id: String) = "repository-profile-$id-v1"
private val json = Json { encodeDefaults = true }

/** Writes a file the way builds before the binary envelope did: JSON envelope, JSON int arrays. */
private suspend fun GenerationalMemoryVault.writeLegacyJson(key: String, associatedData: String, plain: String) {
    val envelope = encrypt(plain.encodeToByteArray(), associatedData.encodeToByteArray())
    write(key, json.encodeToString(CipherEnvelope.serializer(), envelope).encodeToByteArray())
}

class VaultBackedRepositoryStorageTest {
    private val vault = GenerationalMemoryVault()

    private suspend fun open(persistSelection: Boolean = false) = VaultBackedRepository(vault, persistSelection = persistSelection).also { it.load() }

    @Test
    fun missingProfileRecordIsSkippedInsteadOfLockingOutEveryProfile() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = t))
        repository.createProfile(Profile("b", "Bia", createdAt = t))
        vault.delete(profileKey("a"))

        val reopened = open()

        assertEquals(listOf("b"), reopened.profiles.value.map { it.profileId })
        assertEquals("Bia", reopened.readProfile("b").profile.displayName)
        // The healthy record is untouched and the user can keep working.
        reopened.createProfile(Profile("c", "Cy", createdAt = t))
        assertEquals(listOf("b", "c"), open().profiles.value.map { it.profileId })
    }

    @Test
    fun recordWrittenBeforeACrashedIndexWriteIsIgnored() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = t))
        repository.createProfile(Profile("b", "Bia", createdAt = t))
        // The crash hit after b's record was written but before the index followed.
        vault.current[INDEX] = vault.previous.getValue(INDEX)

        assertEquals(listOf("a"), open().profiles.value.map { it.profileId })
    }

    @Test
    fun savedRecordsAreVersionedAndBinary() = runTest {
        val repository = open()
        repository.restoreProfile(sampleProfileData("P").copy(vitals = app.vezuna.domain.ProfileVitals()))

        val stored = vault.current.getValue(profileKey("P-profile"))
        assertEquals(2, stored[0].toInt(), "binary envelope version byte")
        val envelope = CipherEnvelopeCodec.decode(stored)
        val document = json.parseToJsonElement(vault.decrypt(envelope).decodeToString()) as JsonObject
        assertEquals(StorageSchema.CURRENT, StorageSchema.versionOf(document))
    }

    @Test
    fun iosStyleVersion1FilesWithJsonEnvelopesLoadLosslessly() = runTest {
        val data = sampleProfileData("P").copy(vitals = app.vezuna.domain.ProfileVitals())
        val profile = data.profile
        vault.writeLegacyJson(profileKey(profile.profileId), "Vezuna repository profile v1:${profile.profileId}", json.encodeToString(ProfileData.serializer(), data))
        vault.writeLegacyJson(INDEX, "Vezuna repository index v1", json.encodeToString(ListSerializer(Profile.serializer()), listOf(profile)))
        vault.write("repository-health-link-v1", profile.profileId.encodeToByteArray())

        val repository = open()

        assertEquals(data, repository.readProfile(profile.profileId))
        assertEquals(profile.profileId, repository.linkedHealthProfileId())
        // The next write upgrades just the touched record to the binary, versioned form.
        repository.addChatMessage(app.vezuna.domain.ChatMessage(profile.profileId, "m2", app.vezuna.domain.ChatMessage.Role.USER, "again", t))
        assertEquals(2, vault.current.getValue(profileKey(profile.profileId))[0].toInt())
        assertEquals(2, open().readProfile(profile.profileId).chat.size)
    }

    @Test
    fun dataFromANewerSchemaFailsSafelyAndIsNeverOverwritten() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = t))
        val newer = buildJsonObject {
            put(StorageSchema.FIELD, StorageSchema.CURRENT + 1)
            put("profiles", json.encodeToJsonElement(ListSerializer(Profile.serializer()), listOf(Profile("a", "Ana", createdAt = t))))
        }
        val before = vault.current.getValue(INDEX).copyOf()
        val envelope = vault.encrypt(newer.toString().encodeToByteArray(), "Vezuna repository index v1".encodeToByteArray())
        val newerBytes = CipherEnvelopeCodec.encode(envelope)
        vault.current[INDEX] = newerBytes

        val failure = assertFailsWith<VaultUnreadableException> { open() }

        assertEquals(VaultFailure.SCHEMA_NEWER, failure.failure)
        assertTrue(failure.cause is StorageSchemaTooNewException)
        assertTrue(failure.cause!!.message!!.contains("Update Vezuna"))
        assertContentEquals(newerBytes, vault.current.getValue(INDEX), "newer data stays byte-identical")
        assertFalse(before.contentEquals(newerBytes))
        assertTrue(vault.quarantined.isEmpty(), "newer data is not treated as corrupt and not quarantined")
    }

    @Test
    fun androidSingleBlobMigratesToPerProfileFilesWithoutLosingAnything() = runTest {
        val ana = sampleProfileData("A")
        val bia = sampleProfileData("B").let { it.copy(profile = it.profile.copy(displayName = "Bia")) }
        val blob = """{"records":{"A-profile":${json.encodeToString(ProfileData.serializer(), ana)},"B-profile":${json.encodeToString(ProfileData.serializer(), bia)}},"healthLinkedProfileId":"B-profile"}"""
        vault.writeLegacyJson(LEGACY, "Vezuna repository v1", blob)

        val repository = open()

        assertEquals(listOf("A-profile", "B-profile"), repository.profiles.value.map { it.profileId })
        assertEquals(ana.copy(vitals = ana.vitals.migrated()), repository.readProfile("A-profile"))
        assertEquals(bia.copy(vitals = bia.vitals.migrated()), repository.readProfile("B-profile"))
        assertEquals("B-profile", repository.linkedHealthProfileId())
        assertNull(vault.read(LEGACY), "the single blob is gone once the per-profile files are authoritative")
        assertNotNull(vault.current[profileKey("A-profile")])
        assertNotNull(vault.current[INDEX])
        // Cold start on the new layout gives the same data.
        assertEquals(repository.readProfile("A-profile"), open().readProfile("A-profile"))
    }

    @Test
    fun interruptedAndroidMigrationIsRetriedFromTheUntouchedBlob() = runTest {
        val ana = sampleProfileData("A")
        val blob = """{"records":{"A-profile":${json.encodeToString(ProfileData.serializer(), ana)}}}"""
        vault.writeLegacyJson(LEGACY, "Vezuna repository v1", blob)
        // Crash after a profile record was written but before the index: the blob is still the only authority.
        vault.writeLegacyJson(profileKey("A-profile"), "Vezuna repository profile v1:A-profile", "{ half written")

        val repository = open()

        assertEquals(ana.copy(vitals = ana.vitals.migrated()), repository.readProfile("A-profile"))
        assertNull(vault.read(LEGACY))
    }

    @Test
    fun leftoverBlobAfterACompletedMigrationNeverResurrectsDeletedProfiles() = runTest {
        val ana = sampleProfileData("A")
        val blob = """{"records":{"A-profile":${json.encodeToString(ProfileData.serializer(), ana)}}}"""
        vault.writeLegacyJson(LEGACY, "Vezuna repository v1", blob)
        val migrated = open()
        migrated.createProfile(Profile("n", "Nu", createdAt = t))
        migrated.deleteProfile("A-profile")
        // Pretend the blob delete had failed earlier.
        vault.writeLegacyJson(LEGACY, "Vezuna repository v1", blob)

        val reopened = open()

        assertEquals(listOf("n"), reopened.profiles.value.map { it.profileId })
        assertNull(vault.read(LEGACY))
    }

    @Test
    fun corruptBlobFallsBackToItsPreviousGenerationThenMigrates() = runTest {
        val ana = sampleProfileData("A")
        val blob = """{"records":{"A-profile":${json.encodeToString(ProfileData.serializer(), ana)}}}"""
        vault.writeLegacyJson(LEGACY, "Vezuna repository v1", blob)
        vault.writeLegacyJson(LEGACY, "Vezuna repository v1", blob)
        vault.current[LEGACY] = "{truncated".encodeToByteArray()

        val repository = open()

        assertTrue(repository.openedFromPreviousGeneration.value)
        assertEquals(1, repository.profiles.value.size)
        assertEquals("{truncated", vault.quarantined.getValue(LEGACY).decodeToString())
    }

    @Test
    fun corruptProfileRecordFallsBackToItsPreviousGeneration() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = t))
        repository.updateProfile(Profile("a", "Ana B", createdAt = t))
        vault.current[profileKey("a")] = "garbage".encodeToByteArray()

        val reopened = open()

        assertTrue(reopened.openedFromPreviousGeneration.value)
        assertEquals("Ana", reopened.readProfile("a").profile.displayName)
        assertEquals("garbage", vault.quarantined.getValue(profileKey("a")).decodeToString())
    }

    @Test
    fun corruptRecordWithNoPreviousGenerationReportsCorruptInsteadOfDroppingIt() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = t))
        vault.current[profileKey("a")] = "garbage".encodeToByteArray()
        vault.previous.remove(profileKey("a"))

        assertEquals(VaultFailure.CORRUPT, assertFailsWith<VaultUnreadableException> { open() }.failure)
    }

    @Test
    fun reviewedResultsUnitsDosesAndCorrectionsSurviveARoundTrip() = runTest {
        val data = sampleProfileData("R")
        val confirmed = data.observations.single().copy(confirmation = ConfirmationStatus.CONFIRMED, originalUnit = "mmol/L", originalValue = 5.0)
        val repository = open()
        repository.restoreProfile(data.copy(observations = listOf(confirmed)))

        val back = open().readProfile("R-profile")

        assertEquals(confirmed, back.observations.single())
        assertEquals(data.doseEvents, back.doseEvents)
        assertEquals(data.ocrCandidates.single().revisions, back.ocrCandidates.single().revisions)
    }

    @Test
    fun committingFiveThousandObservationsIsFastAndOnlyRewritesOneProfile() = runTest {
        val repository = open()
        repository.createProfile(Profile("a", "Ana", createdAt = t))
        repository.createProfile(Profile("b", "Bia", createdAt = t))
        repository.addDocument(app.vezuna.domain.SourceDocument("a", "d", "x.pdf", "application/pdf", "h", t))
        repository.addReport(app.vezuna.domain.LabReport("a", "r", "d", importedAt = t))
        val big = (0 until 5_000).map {
            LabObservation("a", "o$it", "r", "T$it", "Test $it", it.toDouble(), "mg/dL", it.toDouble(), "mg/dL", ReferenceRange(0.0, 10_000.0), t)
        }
        repository.restoreProfile(repository.readProfile("a").copy(observations = big))
        val otherBefore = vault.current.getValue(profileKey("b")).copyOf()

        val started = kotlin.time.TimeSource.Monotonic.markNow()
        repository.addChatMessage(app.vezuna.domain.ChatMessage("a", "m", app.vezuna.domain.ChatMessage.Role.USER, "hi", t))
        val elapsed = started.elapsedNow()

        assertContentEquals(otherBefore, vault.current.getValue(profileKey("b")))
        // The issue's target is 200 ms on a device; the bound here only guards against a rewrite of everything.
        assertTrue(elapsed.inWholeMilliseconds < 2_000, "commit took $elapsed")
        assertEquals(5_000, open().readProfile("a").observations.size)
    }
}

class StorageSchemaTest {
    private fun doc(version: Int?, vararg fields: Pair<String, String>) = JsonObject(
        buildMap {
            version?.let { put(StorageSchema.FIELD, JsonPrimitive(it)) }
            fields.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
        },
    )

    @Test
    fun everyVersionBelowCurrentHasExactlyOneMigrationStep() {
        assertEquals((1 until StorageSchema.CURRENT).toSet(), StorageSchema.steps.keys)
    }

    @Test
    fun step1To2StampsTheVersionAndKeepsEveryField() {
        val migrated = StorageSchema.migrate(doc(null, "a" to "x"))
        assertEquals(StorageSchema.CURRENT, StorageSchema.versionOf(migrated))
        assertEquals(JsonPrimitive("x"), migrated["a"])
    }

    @Test
    fun registryStepsRunInOrderOneVersionAtATime() {
        val registry = mapOf<Int, (JsonObject) -> JsonObject>(
            1 to { d -> JsonObject(d + ("trail" to JsonPrimitive("1->2"))) },
            2 to { d -> JsonObject(d + ("trail" to JsonPrimitive((d["trail"] as JsonPrimitive).content + ",2->3"))) },
        )
        val migrated = StorageSchema.migrate(doc(null), current = 3, registry = registry)
        assertEquals(JsonPrimitive("1->2,2->3"), migrated["trail"])
        assertEquals(3, StorageSchema.versionOf(migrated))
        val fromTwo = StorageSchema.migrate(doc(2, "trail" to "seed"), current = 3, registry = registry)
        assertEquals(JsonPrimitive("seed,2->3"), fromTwo["trail"])
    }

    @Test
    fun missingStepFailsLoudly() {
        assertFailsWith<IllegalStateException> { StorageSchema.migrate(doc(null), current = 3, registry = emptyMap()) }
    }

    @Test
    fun newerSchemaIsRefusedWithAClearMessage() {
        val error = assertFailsWith<StorageSchemaTooNewException> { StorageSchema.migrate(doc(StorageSchema.CURRENT + 1)) }
        assertEquals(StorageSchema.CURRENT + 1, error.found)
        assertTrue(error.message!!.contains("Update Vezuna"))
    }
}

class CipherEnvelopeCodecTest {
    private val envelope = CipherEnvelope("AES-256-GCM", "key-1", ByteArray(12) { (it * 21 - 100).toByte() }, ByteArray(5000) { (it % 251 - 120).toByte() }, "assoc".encodeToByteArray())

    @Test
    fun binaryRoundTripAndIsFarSmallerThanJson() {
        val bytes = CipherEnvelopeCodec.encode(envelope)
        val decoded = CipherEnvelopeCodec.decode(bytes)
        assertEquals(envelope.algorithm, decoded.algorithm)
        assertEquals(envelope.keyId, decoded.keyId)
        assertContentEquals(envelope.iv, decoded.iv)
        assertContentEquals(envelope.cipherText, decoded.cipherText)
        assertContentEquals(envelope.associatedData, decoded.associatedData)
        val legacy = Json.encodeToString(CipherEnvelope.serializer(), envelope).encodeToByteArray()
        assertTrue(bytes.size * 2 < legacy.size, "binary ${bytes.size} vs json ${legacy.size}")
    }

    @Test
    fun legacyJsonEnvelopeStillDecodes() {
        val legacy = Json.encodeToString(CipherEnvelope.serializer(), envelope).encodeToByteArray()
        assertContentEquals(envelope.cipherText, CipherEnvelopeCodec.decode(legacy).cipherText)
    }

    @Test
    fun truncatedOrGarbageInputIsRejected() {
        val bytes = CipherEnvelopeCodec.encode(envelope)
        assertFailsWith<Exception> { CipherEnvelopeCodec.decode(bytes.copyOf(8)) }
        assertFailsWith<Exception> { CipherEnvelopeCodec.decode(byteArrayOf(2)) }
        assertFailsWith<Exception> { CipherEnvelopeCodec.decode("garbage".encodeToByteArray()) }
    }
}
