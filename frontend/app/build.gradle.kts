import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Machine-specific config (SDK path, backend URL, OAuth client ID) lives in
// local.properties, which is gitignored. See local.properties.example.
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.reader().use { localProperties.load(it) }
}

// Blank values fall back to the default; quotes or backticks copied from the README are ignored.
fun localProperty(name: String, default: String = ""): String =
    localProperties.getProperty(name)?.trim()?.trim('"', '`')?.trim()?.ifEmpty { null } ?: default

// The team's Web OAuth client ID. It is public (it ships inside every APK and is listed
// in README.md), so a missing local.properties entry must not disable Google sign-in.
val defaultGoogleClientId = "543187156899-8i4lr8gsegf849ckevgjt1v3osipnei9.apps.googleusercontent.com"
val googleClientId = localProperty("GOOGLE_CLIENT_ID", defaultGoogleClientId)
if (!googleClientId.endsWith(".apps.googleusercontent.com")) {
    logger.warn("GOOGLE_CLIENT_ID in local.properties is not a Web OAuth client ID; Google sign-in will not work.")
}

android {
    namespace = "com.example.cpen321application"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.example.cpen321application"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Baked in at build time from local.properties (with the defaults above) — keep
        // them out of Kotlin source. Emulator reaches the host via 10.0.2.2, not localhost.
        buildConfigField(
            "String",
            "API_BASE_URL",
            "\"${localProperty("API_BASE_URL", "http://10.0.2.2:3000")}\""
        )
        buildConfigField(
            "String",
            "GOOGLE_CLIENT_ID",
            "\"$googleClientId\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Pins the JDK used to compile Kotlin and Java so the build does not depend on
// whichever JDK happens to be on the developer's PATH. Gradle downloads this
// JDK if it is missing (see the foojay resolver in settings.gradle.kts).
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("io.socket:socket.io-client:2.1.2") {
        exclude(group = "org.json", module = "json")
    }
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}