import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose") version "1.12.2"
}

val lvlPublicKey = providers.gradleProperty("VITAHEARTH_LVL_PUBLIC_KEY").orElse("")

// Release signing comes from Gradle properties or same-named environment variables; never commit values.
fun signingValue(name: String): String? =
    providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("VEZUNA_UPLOAD_STORE_FILE")
val releaseStorePassword = signingValue("VEZUNA_UPLOAD_STORE_PASSWORD")
val releaseKeyAlias = signingValue("VEZUNA_UPLOAD_KEY_ALIAS")
val releaseKeyPassword = signingValue("VEZUNA_UPLOAD_KEY_PASSWORD")
val releaseSigningConfigured = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { it != null }
val releaseVersionCode = providers.gradleProperty("VEZUNA_VERSION_CODE").orNull?.toIntOrNull() ?: 1
val releaseVersionName = providers.gradleProperty("VEZUNA_VERSION_NAME").orNull ?: "1.0"

android {
    namespace = "com.bloodf.vitahearth"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bloodf.vitahearth"
        minSdk = 26
        targetSdk = 37
        versionCode = releaseVersionCode
        versionName = releaseVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "LVL_PUBLIC_KEY", "\"${lvlPublicKey.get()}\"")
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        debug {
            buildConfigField("boolean", "DEBUG_PREMIUM_BYPASS", "true")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "DEBUG_PREMIUM_BYPASS", "false")
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].assets.srcDir("${rootDir}/shared/build/intermediates/assets/androidMain")
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(compose.components.resources)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("com.google.android.play:asset-delivery:2.3.0")

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

tasks.named("preBuild") { dependsOn(":shared:copyAndroidMainComposeResourcesToAndroidAssets") }

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    val configured = releaseSigningConfigured
    val storeFileResolved = releaseStoreFile?.let { file(it) }
    doLast {
        check(configured) {
            "Release signing not configured. Set VEZUNA_UPLOAD_STORE_FILE, VEZUNA_UPLOAD_STORE_PASSWORD, VEZUNA_UPLOAD_KEY_ALIAS, VEZUNA_UPLOAD_KEY_PASSWORD (see docs/RELEASING.md)."
        }
        check(storeFileResolved!!.isFile) { "Keystore not found: $storeFileResolved" }
    }
}
tasks.configureEach {
    if (name == "packageRelease" || name == "bundleRelease") dependsOn(verifyReleaseSigning)
}
