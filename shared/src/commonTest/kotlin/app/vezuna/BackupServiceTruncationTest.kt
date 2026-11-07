package app.vezuna

import app.vezuna.localization.L10nKey
import app.vezuna.localization.VitaMessageException
import app.vezuna.security.BackupService
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalEncodingApi::class)
class BackupServiceTruncationTest {
    @Test
    fun truncatedPortableBackupReturnsAUserErrorAndLeavesTheOriginalReadable() = runTest {
        val service = BackupService()
        val original = Base64.decode(GoldenBackup.FILE_BASE64)
        val passphrase = GoldenBackup.PASSPHRASE_NFC.toCharArray()

        // Version 1 needs 28 header bytes, a 12-byte nonce and at least a 16-byte tag.
        for (length in 0 until 56) {
            val failure = assertFailsWith<VitaMessageException>("prefix length $length") {
                service.open(original.copyOf(length), passphrase.copyOf())
            }
            assertEquals(L10nKey.BACKUP_NOT_A_BACKUP, failure.userText.key, "prefix length $length")
        }

        // A complete header with a shortened authenticated body must fail authentication.
        val failure = assertFailsWith<VitaMessageException> {
            service.open(original.copyOf(original.size - 1), passphrase.copyOf())
        }
        assertEquals(L10nKey.BACKUP_WRONG_PASSPHRASE, failure.userText.key)
        assertEquals(GoldenBackup.PROFILE_ID, service.open(original, passphrase.copyOf()).data.profile.profileId)
    }
}
