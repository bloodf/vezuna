import org.gradle.api.artifacts.ConfigurablePublishArtifact

plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName = "vezuna_ai"
    dynamicDelivery { deliveryType = "on-demand" }
}

// These are explicit local inputs, never downloads or committed model bytes.
val modelSource = providers.gradleProperty("VEZUNA_MODEL_SOURCE")
val modelMetadata = providers.gradleProperty("VEZUNA_MODEL_METADATA")
val modelLicense = providers.gradleProperty("VEZUNA_MODEL_LICENSE")
val generatedAssets = layout.projectDirectory.dir("src/main/assets")
val stagingScript = rootProject.layout.projectDirectory.file("tools/release/stage_android_model.py")

val stageModelAssets = tasks.register<Exec>("stageModelAssets") {
    group = "distribution"
    description = "Verify and stage the explicitly supplied local model before asset-pack consumption."
    // Always verify bytes: an unchanged pathname is not an integrity receipt.
    outputs.upToDateWhen { false }
    doFirst {
        val source = modelSource.orNull
        val metadata = modelMetadata.orNull
        val license = modelLicense.orNull
        check(!source.isNullOrBlank() && !metadata.isNullOrBlank() && !license.isNullOrBlank()) {
            "Asset-pack bundles require VEZUNA_MODEL_SOURCE, VEZUNA_MODEL_METADATA and VEZUNA_MODEL_LICENSE; see docs/android-asset-pack.md."
        }
        commandLine("python3", stagingScript.asFile.absolutePath,
            "--source", source, "--output-dir", generatedAssets.asFile.absolutePath,
            "--metadata", metadata, "--license-file", license)
    }
    doLast {
        val metadata = groovy.json.JsonSlurper().parse(file(modelMetadata.get())) as Map<*, *>
        val name = metadata["fileName"] as String
        val expected = setOf(name, "$name.LICENSE.txt", "$name.provenance.json")
        val entries = generatedAssets.asFile.listFiles()?.toList().orEmpty()
        check(entries.all { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) } &&
            entries.map { it.name }.toSet() == expected) {
            "Asset directory contains unexpected files or recovery state; inspect it before packaging."
        }
    }
}

// AGP 9.5 publishes src/main/assets through packElements. Bind the producer to
// that actual outgoing directory so every consumer waits for verified staging.
configurations.named("packElements") {
    outgoing.artifacts.configureEach {
        if (file == generatedAssets.asFile) {
            (this as ConfigurablePublishArtifact).builtBy(stageModelAssets)
        }
    }
}
