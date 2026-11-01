package app.vezuna.platform

import kotlinx.datetime.Instant
import java.security.MessageDigest
import java.util.UUID

actual fun currentInstant(): Instant = Instant.fromEpochMilliseconds(System.currentTimeMillis())
actual fun randomId(): String = UUID.randomUUID().toString()
actual fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
