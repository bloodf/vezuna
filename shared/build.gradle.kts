import org.gradle.api.file.DirectoryProperty
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    id("org.jetbrains.compose") version libs.versions.composeMultiplatform.get()
}

kotlin {
    android {
        namespace = "app.vezuna.shared"
        compileSdk = 37
        minSdk = 26
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    jvm()
    iosArm64 {
        binaries.framework {
            baseName = "VezunaShared"
            isStatic = true
            linkerOpts.add("-mios-version-min=18.5")
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "VezunaShared"
            isStatic = true
            linkerOpts.add("-mios-simulator-version-min=18.5")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.material.icons.extended)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.llamatik.core)
        }
        iosMain.dependencies {
            implementation(libs.llamatik.core)
        }
    }
}

afterEvaluate {
    tasks.named("copyAndroidMainComposeResourcesToAndroidAssets").configure {
        (property("outputDirectory") as DirectoryProperty).set(layout.buildDirectory.dir("intermediates/assets/androidMain"))
    }
    tasks.named("syncComposeResourcesForIos").configure {
        (property("outputDir") as DirectoryProperty).set(rootProject.file("iosApp/.ios-build/compose-resources"))
    }
}
