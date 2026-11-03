package app.vezuna

import app.vezuna.security.CipherEnvelope
import app.vezuna.security.IosAesGcm
import app.vezuna.security.IosAesGcmBridge
import app.vezuna.security.secureRandomBytes
import kotlin.experimental.xor
import kotlin.native.EagerInitialization

/**
 * Kotlin/Native tests have no Swift shell, so the CryptoKit bridge is unavailable. This pure-Kotlin AES-256-GCM
 * stands in so backup and vault logic run on the real iOS runtime. It is verified against the NIST vector in
 * BackupCryptoVectorsTest and only exists in the iOS test source set; the app always uses CryptoKit.
 */
@OptIn(ExperimentalStdlibApi::class)
@EagerInitialization
@Suppress("unused")
private val installTestBridge = IosAesGcmBridge.install(TestAesGcm)

internal object TestAesGcm : IosAesGcm {
    override fun encrypt(key: ByteArray, plainText: ByteArray, associatedData: ByteArray): CipherEnvelope {
        val iv = secureRandomBytes(12)
        val sealed = seal(key, iv, plainText, associatedData)
        return CipherEnvelope("AES-256-GCM", "test", iv, sealed, associatedData)
    }

    override fun decrypt(key: ByteArray, envelope: CipherEnvelope): ByteArray {
        val data = envelope.cipherText
        require(data.size >= 16) { "ciphertext too short" }
        val body = data.copyOfRange(0, data.size - 16)
        val tag = data.copyOfRange(data.size - 16, data.size)
        val expected = tagFor(key, envelope.iv, body, envelope.associatedData)
        var diff = 0
        for (i in 0 until 16) diff = diff or (tag[i].toInt() xor expected[i].toInt())
        if (diff != 0) throw IllegalArgumentException("authentication failed")
        return ctr(key, envelope.iv, body)
    }

    private fun seal(key: ByteArray, iv: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val body = ctr(key, iv, plain)
        return body + tagFor(key, iv, body, aad)
    }

    private fun tagFor(key: ByteArray, iv: ByteArray, cipher: ByteArray, aad: ByteArray): ByteArray {
        val round = expandKey(key)
        val h = encryptBlock(round, ByteArray(16))
        var y = ByteArray(16)
        fun absorb(data: ByteArray) {
            var i = 0
            while (i < data.size) {
                val n = minOf(16, data.size - i)
                for (j in 0 until n) y[j] = y[j] xor data[i + j]
                y = gmul(y, h)
                i += 16
            }
        }
        absorb(aad); absorb(cipher)
        val lengths = ByteArray(16)
        writeLong(lengths, 0, aad.size.toLong() * 8); writeLong(lengths, 8, cipher.size.toLong() * 8)
        absorb(lengths)
        val mask = encryptBlock(round, counterBlock(iv, 1))
        return ByteArray(16) { y[it] xor mask[it] }
    }

    private fun ctr(key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
        val round = expandKey(key)
        val out = ByteArray(input.size)
        var counter = 2
        var i = 0
        while (i < input.size) {
            val stream = encryptBlock(round, counterBlock(iv, counter++))
            for (j in 0 until minOf(16, input.size - i)) out[i + j] = input[i + j] xor stream[j]
            i += 16
        }
        return out
    }

    private fun counterBlock(iv: ByteArray, counter: Int): ByteArray {
        require(iv.size == 12)
        return iv + byteArrayOf((counter ushr 24).toByte(), (counter ushr 16).toByte(), (counter ushr 8).toByte(), counter.toByte())
    }

    private fun writeLong(target: ByteArray, at: Int, value: Long) {
        for (i in 0 until 8) target[at + i] = (value ushr (56 - 8 * i)).toByte()
    }

    /** Multiplication in GF(2^128) with the GCM bit order (NIST SP 800-38D algorithm 1). */
    private fun gmul(x: ByteArray, y: ByteArray): ByteArray {
        val z = ByteArray(16)
        val v = y.copyOf()
        for (i in 0 until 128) {
            if ((x[i / 8].toInt() ushr (7 - i % 8)) and 1 == 1) for (j in 0 until 16) z[j] = z[j] xor v[j]
            val lsb = v[15].toInt() and 1
            for (j in 15 downTo 1) v[j] = (((v[j].toInt() and 0xff) ushr 1) or ((v[j - 1].toInt() and 1) shl 7)).toByte()
            v[0] = ((v[0].toInt() and 0xff) ushr 1).toByte()
            if (lsb == 1) v[0] = v[0] xor 0xe1.toByte()
        }
        return z
    }

    private val sbox: IntArray = IntArray(256).also { box ->
        var p = 1
        var q = 1
        do {
            p = p xor ((p shl 1) and 0xff) xor (if (p and 0x80 != 0) 0x1b else 0)
            q = q xor (q shl 1); q = q xor (q shl 2); q = q xor (q shl 4); q = q and 0xff
            if (q and 0x80 != 0) q = q xor 0x09
            val x = q xor rotl8(q, 1) xor rotl8(q, 2) xor rotl8(q, 3) xor rotl8(q, 4)
            box[p] = (x xor 0x63) and 0xff
        } while (p != 1)
        box[0] = 0x63
    }

    private fun rotl8(x: Int, n: Int) = ((x shl n) or (x ushr (8 - n))) and 0xff

    private fun xtime(x: Int) = ((x shl 1) xor (if (x and 0x80 != 0) 0x1b else 0)) and 0xff

    private fun expandKey(key: ByteArray): Array<IntArray> {
        require(key.size == 32) { "AES-256 key required" }
        val w = Array(60) { IntArray(4) }
        for (i in 0 until 8) for (j in 0 until 4) w[i][j] = key[4 * i + j].toInt() and 0xff
        var rcon = 1
        for (i in 8 until 60) {
            val t = w[i - 1].copyOf()
            if (i % 8 == 0) {
                val r = intArrayOf(sbox[t[1]], sbox[t[2]], sbox[t[3]], sbox[t[0]])
                r[0] = r[0] xor rcon; rcon = xtime(rcon)
                for (j in 0 until 4) t[j] = r[j]
            } else if (i % 8 == 4) {
                for (j in 0 until 4) t[j] = sbox[t[j]]
            }
            for (j in 0 until 4) w[i][j] = w[i - 8][j] xor t[j]
        }
        return Array(15) { r -> IntArray(16) { w[4 * r + it / 4][it % 4] } }
    }

    private fun encryptBlock(round: Array<IntArray>, block: ByteArray): ByteArray {
        var s = IntArray(16) { (block[it].toInt() and 0xff) xor round[0][it] }
        for (r in 1..14) {
            val sub = IntArray(16) { sbox[s[it]] }
            val shifted = IntArray(16) { sub[((it / 4 + it % 4) % 4) * 4 + it % 4] }
            s = if (r == 14) shifted else IntArray(16).also { out ->
                for (c in 0 until 4) {
                    val a = IntArray(4) { shifted[c * 4 + it] }
                    for (i in 0 until 4) {
                        out[c * 4 + i] = xtime(a[i]) xor (xtime(a[(i + 1) % 4]) xor a[(i + 1) % 4]) xor a[(i + 2) % 4] xor a[(i + 3) % 4]
                    }
                }
            }
            for (i in 0 until 16) s[i] = s[i] xor round[r][i]
        }
        return ByteArray(16) { s[it].toByte() }
    }
}
