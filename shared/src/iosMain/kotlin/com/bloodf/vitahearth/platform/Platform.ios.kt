package com.bloodf.vitahearth.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.datetime.Instant
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Foundation.NSDate
import platform.Foundation.NSUUID
import platform.Foundation.timeIntervalSince1970
import platform.posix.memcpy

actual fun currentInstant(): Instant = Instant.fromEpochMilliseconds((NSDate().timeIntervalSince1970 * 1000).toLong())
actual fun randomId(): String = NSUUID.UUID().UUIDString

@OptIn(ExperimentalForeignApi::class)
actual fun sha256(bytes: ByteArray): ByteArray {
    val output = UByteArray(CC_SHA256_DIGEST_LENGTH)
    output.usePinned { out ->
        if (bytes.isEmpty()) {
            CC_SHA256(null, 0u, out.addressOf(0))
        } else {
            bytes.usePinned { input ->
                CC_SHA256(input.addressOf(0), bytes.size.toUInt(), out.addressOf(0))
            }
        }
    }
    return ByteArray(output.size) { output[it].toByte() }
}
