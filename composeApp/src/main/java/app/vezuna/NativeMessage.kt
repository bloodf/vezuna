package app.vezuna

import android.content.Context
import app.vezuna.health.VitalsSyncOutcome
import app.vezuna.health.VitalsSyncStatus

enum class NativeKey {
    PRIVACY_POLICY, PRIVACY_POLICY_UNAVAILABLE,
    THIRD_PARTY_NOTICES, THIRD_PARTY_NOTICES_UNAVAILABLE,
    MEDICATION_CHANNEL_NAME, MEDICATION_CHANNEL_DESCRIPTION, MEDICATION_REMINDER_TITLE,
    TAKEN, SKIP, MINUTES_SNOOZE, REMINDERS_ENABLED, NOTIFICATION_PERMISSION_DENIED,
    HEALTH_CONNECT_PERMISSION_DENIED, HEALTH_CONNECT_UPDATE_REQUIRED, HEALTH_CONNECT_UNAVAILABLE,
    HEALTH_CONNECT_IMPORT_COMPLETE, HEALTH_CONNECT_IMPORT_FAILED, HEALTH_CONNECT_IMPORT_PARTIAL,
    HEALTH_CONNECT_IMPORT_STOPPED, HEALTH_CONNECT_IMPORT_UNCERTAIN, REMINDER_SCHEDULES_ACTIVE,
    EXPORT_SAVE_FAILED,
    PLAY_MODEL_DELIVERY, PLAY_WAITING_WIFI, PLAY_DOWNLOADING, CLOSE,
    DEVICE_INTEGRATION, OK,
    TAKE_PHOTO, ADD_ANOTHER_PHOTO, PHOTOS_DONE, PHOTO_ADDED_MESSAGE,
    IMAGE_EMPTY, IMAGE_UNREADABLE, PDF_EMPTY, PDF_READ_FAILED,
    MODEL_PREMIUM_UNAVAILABLE, MODEL_PACK_UNAVAILABLE, MODEL_DELIVERY_FAILED, MODEL_CONFIRMATION_NEEDED,
    ASSET_PACK_UNAVAILABLE, ASSET_PATH_UNAVAILABLE, GGUF_MISSING,
    SAVE_ALREADY_ACTIVE, DESTINATION_OPEN_FAILED, SHARE_CHOOSER,
    DOSE_RECORD_UNAVAILABLE, MEDICATION_REMINDER_GENERIC_TEXT, REMINDER_FOR_PROFILE,
    SUPPLY_LOW_TITLE, SUPPLY_LOW_TEXT, SUPPLY_LOW_GENERIC_TEXT,
    APPOINTMENT_CHANNEL_NAME, APPOINTMENT_CHANNEL_DESCRIPTION, APPOINTMENT_REMINDER_TITLE, APPOINTMENT_REMINDER_TEXT, APPOINTMENT_REMINDER_GENERIC_TEXT,
    APP_LOCK_PROMPT_TITLE, APP_LOCK_PROMPT_SUBTITLE, APP_LOCK_USE_PASSCODE,
}

sealed interface NativeText {
    data class Key(val key: NativeKey, val args: List<Any> = emptyList()) : NativeText
    data class Plural(val key: NativeKey, val quantity: Int, val args: List<Any> = emptyList()) : NativeText
}

class NativeMessageException(val userText: NativeText.Key) : IllegalArgumentException(userText.key.name)

fun nativeMessage(key: NativeKey, vararg args: Any) = NativeText.Key(key, args.toList())
fun nativePlural(key: NativeKey, quantity: Int, vararg args: Any) = NativeText.Plural(key, quantity, args.toList())
/** Only a clean terminal receipt may announce completion. Counts alone are not completion evidence. */
fun VitalsSyncOutcome.toHealthConnectMessage(): NativeText = when (status) {
    VitalsSyncStatus.COMPLETE -> nativePlural(NativeKey.HEALTH_CONNECT_IMPORT_COMPLETE, storedCount)
    VitalsSyncStatus.UNRESOLVED,
    VitalsSyncStatus.BUDGET,
    VitalsSyncStatus.REBUILD -> nativeMessage(NativeKey.HEALTH_CONNECT_IMPORT_PARTIAL)
    VitalsSyncStatus.REJECTED,
    VitalsSyncStatus.BLOCKED -> nativeMessage(NativeKey.HEALTH_CONNECT_IMPORT_STOPPED)
    VitalsSyncStatus.UNCERTAIN -> nativeMessage(NativeKey.HEALTH_CONNECT_IMPORT_UNCERTAIN)
}

fun failNative(key: NativeKey, vararg args: Any): Nothing = throw NativeMessageException(nativeMessage(key, *args))

fun Throwable.toNativeText(fallback: NativeKey = NativeKey.EXPORT_SAVE_FAILED): NativeText = when (this) {
    is NativeMessageException -> userText
    else -> nativeMessage(fallback)
}

fun NativeText.resolve(context: Context): String = when (this) {
    is NativeText.Key -> context.getString(context.nativeResource(key), *args.toTypedArray())
    is NativeText.Plural -> context.resources.getQuantityString(context.nativeResource(key), quantity, quantity, *args.toTypedArray())
}

private fun Context.nativeResource(key: NativeKey): Int {
    val name = key.name.lowercase()
    val type = if (key == NativeKey.HEALTH_CONNECT_IMPORT_COMPLETE) "plurals" else "string"
    return resources.getIdentifier(name, type, packageName).also {
        check(it != 0) { "Missing native localization: $name" }
    }
}
