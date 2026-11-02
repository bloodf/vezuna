package app.vezuna.security

import kotlinx.serialization.json.Json

/**
 * On-disk form of a [CipherEnvelope].
 *
 * Version 1 was JSON, where `ByteArray` becomes a list of ints (3-4x bigger). Version 2 is binary:
 * `0x02, len8 algorithm, len8 keyId, len8 iv, len16 associatedData, cipherText` (lengths big-endian, fields in that order,
 * cipherText is the rest). A JSON file starts with `{`, so [decode] reads both and [encode] always writes version 2.
 */
object CipherEnvelopeCodec {
    private const val VERSION: Byte = 2
    private val legacyJson = Json

    fun encode(envelope: CipherEnvelope): ByteArray {
        val algorithm = envelope.algorithm.encodeToByteArray()
        val keyId = envelope.keyId.encodeToByteArray()
        require(algorithm.size <= 0xFF && keyId.size <= 0xFF && envelope.iv.size <= 0xFF && envelope.associatedData.size <= 0xFFFF) {
            "Envelope field too long"
        }
        val out = ByteArray(1 + 1 + algorithm.size + 1 + keyId.size + 1 + envelope.iv.size + 2 + envelope.associatedData.size + envelope.cipherText.size)
        var at = 0
        out[at++] = VERSION
        fun put(bytes: ByteArray, wide: Boolean = false) {
            if (wide) out[at++] = (bytes.size ushr 8).toByte()
            out[at++] = bytes.size.toByte()
            bytes.copyInto(out, at)
            at += bytes.size
        }
        put(algorithm); put(keyId); put(envelope.iv); put(envelope.associatedData, wide = true)
        envelope.cipherText.copyInto(out, at)
        return out
    }

    /** Throws on anything that is neither a version 2 file nor a legacy JSON envelope. */
    fun decode(bytes: ByteArray): CipherEnvelope {
        if (bytes.firstOrNull() != VERSION) return legacyJson.decodeFromString(CipherEnvelope.serializer(), bytes.decodeToString())
        var at = 1
        fun take(wide: Boolean = false): ByteArray {
            var length = 0
            if (wide) { require(at < bytes.size); length = (bytes[at++].toInt() and 0xFF) shl 8 }
            require(at < bytes.size) { "Truncated envelope" }
            length = length or (bytes[at++].toInt() and 0xFF)
            require(at + length <= bytes.size) { "Truncated envelope" }
            return bytes.copyOfRange(at, at + length).also { at += length }
        }
        val algorithm = take().decodeToString()
        val keyId = take().decodeToString()
        val iv = take()
        val associatedData = take(wide = true)
        return CipherEnvelope(algorithm, keyId, iv, bytes.copyOfRange(at, bytes.size), associatedData)
    }
}
