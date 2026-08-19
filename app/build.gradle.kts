import java.net.IDN
import java.net.URI
import java.util.Locale
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val miniStorePlayDispenserUrl = providers
    .gradleProperty("miniStorePlayDispenserUrl")
    .orElse(providers.environmentVariable("MINI_STORE_PLAY_DISPENSER_URL"))
    .getOrElse("")
    .trim()
val miniStorePlayClientToken = providers
    .gradleProperty("miniStorePlayClientToken")
    .orElse(providers.environmentVariable("MINI_STORE_PLAY_CLIENT_TOKEN"))
    .getOrElse("")
    .trim()

require(miniStorePlayDispenserUrl.isEmpty() == miniStorePlayClientToken.isEmpty()) {
    "miniStorePlayDispenserUrl and miniStorePlayClientToken must be configured together"
}

if (miniStorePlayDispenserUrl.isNotEmpty()) {
    val dispenserUri = URI(miniStorePlayDispenserUrl)
    require(
        dispenserUri.scheme.equals("https", ignoreCase = true) &&
            !dispenserUri.host.isNullOrBlank() &&
            dispenserUri.userInfo == null &&
            dispenserUri.query == null &&
            dispenserUri.fragment == null,
    ) { "miniStorePlayDispenserUrl must be a plain HTTPS endpoint without credentials, query, or fragment" }
    val normalizedDispenserHost = IDN.toASCII(dispenserUri.host.trimEnd('.'))
        .lowercase(Locale.ROOT)
    require(
        normalizedDispenserHost != "auroraoss.com" &&
            !normalizedDispenserHost.endsWith(".auroraoss.com"),
    ) {
        "The public Aurora dispenser is not supported; configure an authorized private endpoint"
    }
    require(
        miniStorePlayClientToken.length >= 32 && miniStorePlayClientToken.none(Char::isWhitespace),
    ) { "miniStorePlayClientToken must contain at least 32 non-whitespace characters" }
}

val escapedMiniStorePlayDispenserUrl = miniStorePlayDispenserUrl
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
val escapedMiniStorePlayClientToken = miniStorePlayClientToken
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

val enableReleaseShrinking = providers
    .gradleProperty("enableReleaseShrinking")
    .map { it.toBooleanStrict() }
    .getOrElse(false)

// Release signing credentials live outside the project tree so they are never
// committed and never land in a normal project backup. Resolution order:
//   -PsigningPropertiesPath  ->  MAFTEACH_SIGNING_PROPERTIES  ->  default path.
// When the file is absent the release build stays unsigned instead of failing,
// so a checkout without the key can still compile.
val signingPropertiesPath = providers
    .gradleProperty("signingPropertiesPath")
    .orElse(providers.environmentVariable("MAFTEACH_SIGNING_PROPERTIES"))
    .getOrElse("C:\\projects\\SecureGuardMDM\\signing\\keystore.properties")

val releaseSigning: Map<String, String>? = run {
    val propertiesFile = File(signingPropertiesPath)
    if (!propertiesFile.isFile) return@run null
    val loaded = Properties().apply {
        propertiesFile.inputStream().use { stream -> load(stream) }
    }
    val required = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    val values = required.associateWith { (loaded.getProperty(it) ?: "").trim() }
    require(values.all { it.value.isNotEmpty() }) {
        "Signing properties at $signingPropertiesPath must define ${required.joinToString(", ")}"
    }
    require(File(values.getValue("storeFile")).isFile) {
        "Keystore was not found at ${values.getValue("storeFile")}"
    }
    values + mapOf("storeType" to (loaded.getProperty("storeType") ?: "PKCS12").trim())
}

// Shipping identity. Both values stay authoritative unless a build explicitly
// overrides them, which is only used to produce a lower-versioned "bridge" APK
// for verifying the self-update flow against a already installed release.
val shippingVersionCode = 7
val shippingVersionName = "0.6"
val effectiveVersionCode = providers
    .gradleProperty("overrideVersionCode")
    .map { it.trim().toInt() }
    .getOrElse(shippingVersionCode)
val effectiveVersionName = providers
    .gradleProperty("overrideVersionName")
    .map { it.trim() }
    .getOrElse(shippingVersionName)
require(effectiveVersionCode > 0) { "overrideVersionCode must be a positive integer" }
require(effectiveVersionName.isNotBlank()) { "overrideVersionName must not be blank" }

android {
    namespace = "com.secureguard.mdm"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.secureguard.mdm"
        minSdk = 23
        targetSdk = 34
        versionCode = effectiveVersionCode
        versionName = effectiveVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "MINI_STORE_PLAY_DISPENSER_URL",
            "\"$escapedMiniStorePlayDispenserUrl\"",
        )
        buildConfigField(
            "String",
            "MINI_STORE_PLAY_CLIENT_TOKEN",
            "\"$escapedMiniStorePlayClientToken\"",
        )
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    if (releaseSigning != null) {
        signingConfigs {
            create("release") {
                storeFile = File(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
                storeType = releaseSigning.getValue("storeType")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = enableReleaseShrinking
            isShrinkResources = enableReleaseShrinking
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Absent key means an unsigned build, which install-mafteach.ps1 and
            // the publish script both reject. It never silently falls back to
            // the debug key, because that would break signer continuity.
            signingConfig = signingConfigs.findByName("release")
        }
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
        viewBinding = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    applicationVariants.all {
        outputs.all {
            // The user-facing brand plus the effective version, so a downloaded
            // file identifies itself. Android identity still comes from the
            // manifest, so this name never affects Device Owner or signing.
            val effectiveVersionName = versionName
                ?: error("versionName must be set to build a named APK")
            val safeVersionName = effectiveVersionName
                .replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")
                .trim('_', '.')
            require(safeVersionName.isNotEmpty()) { "versionName produced an empty file name" }
            val apkName = "מפתח-$safeVersionName-${buildType.name}.apk"
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName = apkName
        }
    }
}

dependencies {
    val roomVersion = "2.8.4"
    val ktorVersion = "2.3.11"

    // Internal VPN engine spike. Source is vendored under third_party/firestack at this exact commit.
    implementation("com.github.celzero:firestack:61894b7fdba9405be49c593927f51470c0979797@aar")

    // Kotlin/Android Core
    implementation("androidx.core:core-ktx:1.12.0")

    // Room Database
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // General Android Views
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Jetpack Compose & UI
    implementation(platform("androidx.compose:compose-bom:2024.02.01"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.02.01"))
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Durable package-change update checks (Apache-2.0). Version 2.9.1 remains compatible with compileSdk 34.
    implementation("androidx.work:work-runtime:2.9.1")

    // Process-wide foreground/background signal, so navigating between screens is
    // not mistaken for the app leaving the foreground.
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")

    // Lifecycle & Navigation
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.59.2")
    kapt("com.google.dagger:hilt-compiler:2.59.2")

    // JSON & Network (Ktor)
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // Automatic Google Play update discovery and split delivery (GPL-3.0-or-later).
    implementation("com.auroraoss:gplayapi:3.6.4")

    // Security
    implementation("at.favre.lib:bcrypt:0.10.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")

    // Accompanist
    implementation("com.google.accompanist:accompanist-drawablepainter:0.32.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // date for kiosk
    implementation("com.kosherjava:zmanim:2.5.0")
}

kapt {
    correctErrorTypes = true
}
