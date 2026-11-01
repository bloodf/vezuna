package app.vezuna

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.vezuna.security.PassphraseBackupCodec
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/**
 * Golden backup made by Python hashlib + cryptography. Opening it on a device checks the Android PBKDF2 (Mac based),
 * NFC normalization and AES-GCM against the same bytes the JVM and iOS tests use.
 */
@RunWith(AndroidJUnit4::class)
class BackupVectorsInstrumentedTest {
    private val golden = "VlpCSwEBAAknwBAAAQIDBAUGBwgJCgsMDQ4PDKChoqOkpaanqKmqqwjkjnhrpA8RLx9TKTMNw8PQY7w+8Ldn5Qd8la/2CrEk3O4sdjDpOKpUCmJe8G9x12dKuOY4RshQ2uOa6AR7yhZmNnrKqgDxo6e8dUn2SjMwxtCOAnziTp3CGXPuEmnMiTP7pdYwaWMR6jkYX5jJqcZoXCX7wPiCtmhLrI41V/F1gP69lkMMssxc26MboTDb4diQE6jrNiNqJw6wNrnCC+PuUhtOyMPloIubISU4w/C20UlRzSBGJVun2COqzDF53F2I4mC0EzzbsndQOOfbpwA="
    private val nfc = "p\u00e4ssw\u00f6rd correct horse"
    private val nfd = "pa\u0308sswo\u0308rd correct horse"

    @Test
    fun goldenBackupOpensWithNfcAndNfdPassphrases() {
        val file = Base64.getDecoder().decode(golden)
        for (passphrase in listOf(nfc, nfd)) {
            val plain = PassphraseBackupCodec.open(file, passphrase.toCharArray()).decodeToString()
            assertTrue(plain.contains("golden-profile"))
        }
    }

    @Test
    fun roundTripWithNonAsciiPassphrase() {
        val sealed = PassphraseBackupCodec.seal("records".encodeToByteArray(), nfd.toCharArray())
        assertEquals("records", PassphraseBackupCodec.open(sealed, nfc.toCharArray()).decodeToString())
    }
}
