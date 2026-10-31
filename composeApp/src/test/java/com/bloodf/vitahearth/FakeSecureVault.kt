package com.bloodf.vitahearth

import com.bloodf.vitahearth.security.CipherEnvelope
import com.bloodf.vitahearth.security.SecureVault

class FakeSecureVault : SecureVault {
    private val files = mutableMapOf<String, ByteArray>()

    override suspend fun encrypt(plainText: ByteArray, associatedData: ByteArray): CipherEnvelope =
        CipherEnvelope("test-xor", "test-key", byteArrayOf(7), plainText.xor(0x5a), associatedData)

    override suspend fun decrypt(envelope: CipherEnvelope): ByteArray {
        require(envelope.algorithm == "test-xor" && envelope.keyId == "test-key")
        return envelope.cipherText.xor(0x5a)
    }

    override suspend fun write(key: String, ciphertext: ByteArray) { files[key] = ciphertext.copyOf() }
    override suspend fun read(key: String): ByteArray? = files[key]?.copyOf()
    override suspend fun delete(key: String) { files.remove(key) }

    private fun ByteArray.xor(mask: Int): ByteArray = ByteArray(size) { index -> (this[index].toInt() xor mask).toByte() }
}
