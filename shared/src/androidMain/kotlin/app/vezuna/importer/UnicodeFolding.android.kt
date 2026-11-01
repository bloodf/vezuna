package app.vezuna.importer

import java.text.Normalizer

internal actual fun normalizeNfkd(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
