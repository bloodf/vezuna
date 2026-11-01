package app.vezuna.llm

import vezuna.shared.generated.resources.Res

/**
 * Reads the catalog packaged with the app. Both platforms ship the same file
 * (`shared/src/commonMain/composeResources/files/model-packs.json`); `[]` means no model is configured.
 * Throws if the file is missing or invalid, so callers can report it instead of hiding it.
 */
suspend fun loadBundledModelCatalog(): ModelCatalog =
    ModelCatalog.fromJson(Res.readBytes("files/model-packs.json").decodeToString())
