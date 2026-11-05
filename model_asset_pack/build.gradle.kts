import javax.inject.Inject
import org.gradle.api.artifacts.ConfigurablePublishArtifact
import org.gradle.process.ExecOperations

plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName = "vezuna_ai"
    dynamicDelivery { deliveryType = "on-demand" }
}

abstract class StageModelAssets : DefaultTask() {
    @get:InputFile abstract val modelSource: RegularFileProperty
    @get:InputFile abstract val modelMetadata: RegularFileProperty
    @get:InputFile abstract val modelLicense: RegularFileProperty
    @get:InputFile abstract val stagingScript: RegularFileProperty
    @get:OutputDirectory abstract val assetDirectory: DirectoryProperty
    @get:Inject abstract val execOperations: ExecOperations

    @TaskAction
    fun stage() {
        execOperations.exec {
            commandLine("python3", stagingScript.get().asFile.absolutePath,
                "--source", modelSource.get().asFile.absolutePath,
                "--output-dir", assetDirectory.get().asFile.absolutePath,
                "--metadata", modelMetadata.get().asFile.absolutePath,
                "--license-file", modelLicense.get().asFile.absolutePath)
        }
        val metadata = groovy.json.JsonSlurper().parse(modelMetadata.get().asFile) as Map<*, *>
        val name = metadata["fileName"] as String
        val expected = setOf(name, "$name.LICENSE.txt", "$name.provenance.json")
        val entries = assetDirectory.get().asFile.listFiles()?.toList().orEmpty()
        check(entries.all { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) } &&
            entries.map { it.name }.toSet() == expected) {
            "Asset directory contains unexpected files or recovery state; inspect it before packaging."
        }
    }
}

// These are explicit local inputs, never downloads or committed model bytes.
val sourceInput = providers.gradleProperty("VEZUNA_MODEL_SOURCE")
val metadataInput = providers.gradleProperty("VEZUNA_MODEL_METADATA")
val licenseInput = providers.gradleProperty("VEZUNA_MODEL_LICENSE")
val generatedAssets = layout.projectDirectory.dir("src/main/assets")
val stageModelAssets = tasks.register<StageModelAssets>("stageModelAssets") {
    group = "distribution"
    description = "Verify and stage the explicitly supplied local model before asset-pack consumption."
    modelSource.set(layout.file(sourceInput.map { require(File(it).isAbsolute); File(it) }))
    modelMetadata.set(layout.file(metadataInput.map { require(File(it).isAbsolute); File(it) }))
    modelLicense.set(layout.file(licenseInput.map { require(File(it).isAbsolute); File(it) }))
    stagingScript.set(rootProject.layout.projectDirectory.file("tools/release/stage_android_model.py"))
    assetDirectory.set(generatedAssets)
    // Always verify bytes: an unchanged pathname is not an integrity receipt.
    outputs.upToDateWhen { false }
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
