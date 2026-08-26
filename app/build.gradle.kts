import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingProperties = Properties().apply {
    val propertiesFile = rootProject.file("signing.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use { load(it) }
}

fun signingValue(name: String): String? =
    providers.environmentVariable(name).orNull ?: signingProperties.getProperty(name)

val releaseStorePath = signingValue("PEC_KEYSTORE_PATH")
val releaseStorePassword = signingValue("PEC_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("PEC_KEY_ALIAS")
val releaseKeyPassword = signingValue("PEC_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.example.myvoiceboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.myvoiceboard"
        minSdk = 23
        targetSdk = 35
        versionCode = 7
        versionName = "0.7"
    }

    buildFeatures { viewBinding = true }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseStorePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
