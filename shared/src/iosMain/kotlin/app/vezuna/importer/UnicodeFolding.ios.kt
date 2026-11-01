package app.vezuna.importer

import platform.Foundation.NSString
import platform.Foundation.decomposedStringWithCompatibilityMapping

internal actual fun normalizeNfkd(value: String): String = (value as NSString).decomposedStringWithCompatibilityMapping
