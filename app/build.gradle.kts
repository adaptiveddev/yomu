import com.android.build.api.variant.HostTestBuilder

plugins {
    id("yomu.android.application")
    id("yomu.android.compose")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi)
}

val releaseKeystore: String? = System.getenv("YOMU_KEYSTORE_PATH")

// "-nightly.42", "-beta.1": appended to versionName by the GitHub workflows (-Pyomu.versionSuffix=…), see docs/RELEASING.md.
val versionSuffix: String? = providers.gradleProperty("yomu.versionSuffix").orNull?.trim()?.takeIf { it.isNotEmpty() }
// OAuth client ids of the tracking services (2.0.0, docs/TRACKING.md): -PYOMU_ANILIST_CLIENT_ID=… or the environment
// variable of the same name. Empty = the user enters their own in Settings → Tracking.
fun trackerKey(name: String): String = (providers.gradleProperty(name).orNull ?: System.getenv(name)).orEmpty().trim().replace("\"", "")
// gradle/debug.keystore (password "android"): the shared key of debug builds, see signingConfigs.
val sharedDebugKey: File? = rootProject.file("gradle/debug.keystore").takeIf { it.isFile }

android {
    namespace = "app.yomu"
    defaultConfig {
        applicationId = "app.yomu.reader"
        versionCode = 18
        versionName = "2.0.6"
        versionSuffix?.let { versionNameSuffix = it }
        buildConfigField("String", "ANILIST_CLIENT_ID", "\"${trackerKey("YOMU_ANILIST_CLIENT_ID")}\"")
        buildConfigField("String", "MAL_CLIENT_ID", "\"${trackerKey("YOMU_MAL_CLIENT_ID")}\"")
        buildConfigField("String", "SHIKIMORI_CLIENT_ID", "\"${trackerKey("YOMU_SHIKIMORI_CLIENT_ID")}\"")
        buildConfigField("String", "SHIKIMORI_CLIENT_SECRET", "\"${trackerKey("YOMU_SHIKIMORI_CLIENT_SECRET")}\"")
    }
    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("en", "ru")
    }
    signingConfigs {
        // One debug key for every computer and CI run (1.9.0), so a debug APK installs over the previous one instead
        // of asking for a clean install. The key is public on purpose: it signs only the ".debug" app, never a
        // release (a release built without the secrets keeps the local throwaway key, see docs/RELEASING.md).
        if (sharedDebugKey != null) {
            create("sharedDebug") {
                storeFile = sharedDebugKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("YOMU_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("YOMU_KEY_ALIAS")
                keyPassword = System.getenv("YOMU_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (sharedDebugKey != null) signingConfig = signingConfigs.getByName("sharedDebug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
        // The debug app built like a release (R8, not debuggable), `./gradlew assembleFast` (1.9.0). A debug build is
        // too slow to judge animations by; this one installs over it (same package and key) and keeps its data.
        create("fast") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-fast"
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName(if (sharedDebugKey != null) "sharedDebug" else "debug")
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }
    buildFeatures { buildConfig = true }
}

androidComponents {
    // Its code is the release code: unit tests of its own would only run the same tests once more.
    beforeVariants(selector().withBuildType("fast")) { variant ->
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1400m"
    systemProperty("robolectric.logging", "stdout")
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:ui"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:network"))
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":source-api"))
    implementation(project(":sources:opds"))
    implementation(project(":sources:mangadex"))
    implementation(project(":sources:novels"))
    implementation(project(":sources:local"))
    implementation(project(":compat:tachiyomi"))
    implementation(project(":feature:library"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:annotations"))
    implementation(project(":feature:dictionary"))
    implementation(project(":feature:translator"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:catalog"))
    implementation(project(":feature:downloads"))
    implementation(project(":feature:settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.biometric)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.timber)

    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.paging.runtime)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.espresso.core)
}
