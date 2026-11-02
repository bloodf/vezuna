package app.vezuna

import android.content.Context

enum class NativeKey {
    PRIVACY_POLICY, PRIVACY_POLICY_UNAVAILABLE,
    THIRD_PARTY_NOTICES, THIRD_PARTY_NOTICES_UNAVAILABLE,
    MEDICATION_CHANNEL_NAME, MEDICATION_CHANNEL_DESCRIPTION, MEDICATION_REMINDER_TITLE,
    TAKEN, SKIP, MINUTES_SNOOZE, REMINDERS_ENABLED, NOTIFICATION_PERMISSION_DENIED,
    HEALTH_CONNECT_PERMISSION_DENIED, HEALTH_CONNECT_UPDATE_REQUIRED, HEALTH_CONNECT_UNAVAILABLE,
    HEALTH_CONNECT_IMPORT_COMPLETE, HEALTH_CONNECT_IMPORT_FAILED, REMINDER_SCHEDULES_ACTIVE,
    BACKUP_SELECT_PROFILE, BACKUP_CREATE_FAILED, SHARE_EXPORT_FAILED, EXPORT_SAVED, EXPORT_SAVE_FAILED,
    HEALTH_CONNECT_IMPORT_MENU, MEDICATION_REMINDERS_MENU, ENCRYPTED_PROFILE_BACKUP_MENU,
    PLAY_MODEL_DELIVERY, PLAY_WAITING_WIFI, PLAY_DOWNLOADING, CLOSE,
    EXPORT_READY, EXPORT_READY_MESSAGE, SHARE, SAVE,
    OFFLINE_SOURCE_EXTRACTION, EXTRACTION_EMBEDDED, EXTRACTION_OCR, EXTRACTION_PAGE,
    EMBEDDED_TEXT, OCR, NO_TEXT_PAGE, DEVICE_INTEGRATION, OK,
    IMPORT_SOURCE_TITLE, IMPORT_SOURCE_MESSAGE, TAKE_PHOTO, ADD_ANOTHER_PHOTO, PHOTOS_DONE, PHOTO_ADDED_MESSAGE, CHOOSE_IMAGE, CHOOSE_PDF,
    SOURCE_TOO_LARGE, SOURCE_OPEN_FAILED, IMAGE_EMPTY, IMAGE_UNREADABLE, PDF_EMPTY, PDF_READ_FAILED,
    MODEL_PREMIUM_UNAVAILABLE, MODEL_PACK_UNAVAILABLE, MODEL_DELIVERY_FAILED, MODEL_CONFIRMATION_NEEDED,
    ASSET_PACK_UNAVAILABLE, ASSET_PATH_UNAVAILABLE, GGUF_MISSING,
    SAVE_ALREADY_ACTIVE, SOURCE_IMPORT_ACTIVE, DESTINATION_OPEN_FAILED, SHARE_CHOOSER,
    DOSE_RECORD_UNAVAILABLE, MEDICATION_REMINDER_GENERIC_TEXT,
    APP_LOCK_PROMPT_TITLE, APP_LOCK_PROMPT_SUBTITLE, APP_LOCK_USE_PASSCODE,
}

sealed interface NativeText {
    data class Key(val key: NativeKey, val args: List<Any> = emptyList()) : NativeText
    data class Plural(val key: NativeKey, val quantity: Int, val args: List<Any> = emptyList()) : NativeText
}

class NativeMessageException(val userText: NativeText.Key) : IllegalArgumentException(userText.key.name)

fun nativeMessage(key: NativeKey, vararg args: Any) = NativeText.Key(key, args.toList())
fun nativePlural(key: NativeKey, quantity: Int, vararg args: Any) = NativeText.Plural(key, quantity, args.toList())
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
