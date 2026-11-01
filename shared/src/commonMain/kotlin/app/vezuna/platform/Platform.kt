package app.vezuna.platform

import kotlinx.datetime.Instant

expect fun currentInstant(): Instant
expect fun randomId(): String
expect fun sha256(bytes: ByteArray): ByteArray

fun ByteArray.toHex(): String = joinToString("") { byte ->
    val value = byte.toInt() and 0xff
    HEX[value ushr 4].toString() + HEX[value and 0x0f]
}

private const val HEX = "0123456789abcdef"
