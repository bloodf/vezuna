package app.vezuna.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Raised when stored data was written by a newer build; the data is left untouched. */
class StorageSchemaTooNewException(val found: Int, val supported: Int) :
    Exception("Stored data uses storage schema $found but this version of Vezuna supports up to $supported. Update Vezuna to open it.")

/**
 * Versioning for the plaintext documents kept in the vault (profile records, profile index).
 *
 * Version 1 is every document written before versioning existed (no `schemaVersion` field).
 * [steps] maps a version to the function that upgrades a document from that version to the next one;
 * a new schema bumps [CURRENT] and registers exactly one step.
 */
object StorageSchema {
    const val CURRENT = 4
    const val FIELD = "schemaVersion"

    val steps: Map<Int, (JsonObject) -> JsonObject> = mapOf(
        // 1 -> 2: no field changes; the version stamp itself is the change.
        1 to { document -> document },
        // 2 -> 3: optional allergy, condition, vaccination and vault document lists; older documents simply have none. The bump makes an
        // older build refuse these documents as "too new" instead of treating them as damaged.
        2 to { document -> document },
        // 3 -> 4: optional appointment list; older documents simply have none. The bump makes a build that predates it refuse these
        // documents as "too new" (a clear recovery screen, nothing quarantined) instead of treating them as damaged.
        3 to { document -> document },
    )

    fun versionOf(document: JsonObject): Int = (document[FIELD] as? JsonPrimitive)?.intOrNull ?: 1

    /** Upgrades [document] to [current]; refuses (never rewrites) documents from a newer schema. */
    fun migrate(
        document: JsonObject,
        current: Int = CURRENT,
        registry: Map<Int, (JsonObject) -> JsonObject> = steps,
    ): JsonObject {
        var version = versionOf(document)
        if (version > current) throw StorageSchemaTooNewException(version, current)
        var migrated = document
        while (version < current) {
            val step = registry[version] ?: error("No storage migration from schema $version")
            migrated = step(migrated)
            version++
        }
        return stamp(migrated, current)
    }

    fun stamp(document: JsonObject, version: Int = CURRENT): JsonObject =
        JsonObject(document + (FIELD to JsonPrimitive(version)))

    fun unstamped(document: JsonObject): JsonObject = JsonObject(document - FIELD)
}
