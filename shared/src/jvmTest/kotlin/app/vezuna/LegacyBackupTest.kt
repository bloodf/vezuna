package app.vezuna

import app.vezuna.localization.L10nKey
import app.vezuna.localization.VitaMessageException
import app.vezuna.security.BackupKind
import app.vezuna.security.BackupPayload
import app.vezuna.security.BackupService
import app.vezuna.security.CipherEnvelope
import app.vezuna.security.JvmSecureVault
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LegacyBackupTest {
    private val json = Json { encodeDefaults = true }
    private val data = sampleProfileData()

    /** Exactly what the pre-passphrase BackupService wrote: a vault envelope serialized as JSON. */
    private suspend fun legacyFile(vault: JvmSecureVault, schema: Int = 1): ByteArray {
        val payload = BackupPayload(schema, data.profile.profileId, Instant.parse("2026-01-01T00:00:00Z"), data)
        val plain = json.encodeToString(BackupPayload.serializer(), payload).encodeToByteArray()
        val envelope = vault.encrypt(plain, "Vezuna backup v1:${data.profile.profileId}".encodeToByteArray())
        return json.encodeToString(CipherEnvelope.serializer(), envelope).encodeToByteArray()
    }

    @Test
    fun legacySameDeviceBackupStillRestoresOnTheSameDevice() = runTest {
        val vault = JvmSecureVault(Files.createTempDirectory("vault").toFile())
        val file = legacyFile(vault)
        val service = BackupService(vault)
        assertEquals(BackupKind.SAME_DEVICE, service.kind(file))
        val opened = service.open(file, null)
        assertEquals(BackupKind.SAME_DEVICE, opened.kind)
        assertEquals(data, opened.data)
    }

    @Test
    fun legacyBackupFromAnotherDeviceFailsWithAClearMessage() = runTest {
        val file = legacyFile(JvmSecureVault(Files.createTempDirectory("vault-a").toFile()))
        val other = BackupService(JvmSecureVault(Files.createTempDirectory("vault-b").toFile()))
        assertEquals(L10nKey.BACKUP_SAME_DEVICE_UNAVAILABLE, assertFailsWith<VitaMessageException> { other.open(file, null) }.userText.key)
    }

    @Test
    fun legacyEnvelopeBoundToAnotherProfileIsRejected() = runTest {
        val vault = JvmSecureVault(Files.createTempDirectory("vault").toFile())
        val payload = BackupPayload(1, data.profile.profileId, Instant.parse("2026-01-01T00:00:00Z"), data)
        val plain = json.encodeToString(BackupPayload.serializer(), payload).encodeToByteArray()
        val envelope = vault.encrypt(plain, "Vezuna backup v1:someone-else".encodeToByteArray())
        val file = json.encodeToString(CipherEnvelope.serializer(), envelope).encodeToByteArray()
        assertEquals(L10nKey.BACKUP_INVALID_CONTENT, assertFailsWith<VitaMessageException> { BackupService(vault).open(file, null) }.userText.key)
    }

    @Test
    fun legacyUnsupportedSchemaIsRejected() = runTest {
        val vault = JvmSecureVault(Files.createTempDirectory("vault").toFile())
        val file = legacyFile(vault, schema = 99)
        assertEquals(L10nKey.BACKUP_UNSUPPORTED_SCHEMA, assertFailsWith<VitaMessageException> { BackupService(vault).open(file, null) }.userText.key)
    }
}
