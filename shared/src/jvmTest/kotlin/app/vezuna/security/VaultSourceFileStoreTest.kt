package app.vezuna.security

import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class VaultSourceFileStoreTest {
    private val directory = Files.createTempDirectory("vezuna-source-store").toFile()
    private val vault = JvmSecureVault(directory)
    private val store = VaultSourceFileStore(vault)

    @Test
    fun storesEncryptedAndReadsBackForItsProfileOnly() = runTest {
        val bytes = "%PDF-1.7 laudo".encodeToByteArray()
        val key = store.save("profile-a", "doc-1", bytes)

        assertFalse(vault.read(key)!!.decodeToString().contains("laudo"))
        assertContentEquals(bytes, store.read("profile-a", key))
        assertFailsWith<IllegalStateException> { store.read("profile-b", key) }
        assertFailsWith<IllegalStateException> { store.read("profile-a-2", key) }

        store.discard(key)
        assertNull(store.read("profile-a", key))
    }
}
