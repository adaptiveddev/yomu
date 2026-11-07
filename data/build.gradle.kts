plugins {
    id("yomu.android.library")
    alias(libs.plugins.kotlin.serialization)
}
dependencies {
    api(project(":domain"))
    api(project(":source-api"))
    api(project(":core:common"))
    api(project(":core:database"))
    api(project(":core:datastore"))
    api(project(":core:network"))
    api(project(":sources:opds"))
    api(project(":sources:mangadex"))
    api(project(":sources:novels"))
    api(project(":sources:local"))
    api(project(":compat:tachiyomi"))
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.exifinterface)
    api(libs.androidx.work.runtime)
    api(libs.androidx.paging.runtime)
    api(libs.jsoup)
    api(libs.pdfium)
    implementation(libs.junrar)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    implementation(libs.mlkit.ocr.latin)
    implementation(libs.mlkit.ocr.japanese)
    implementation(libs.mlkit.ocr.chinese)
    implementation(libs.mlkit.ocr.korean)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit.vintage)
    testImplementation(libs.robolectric)
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "1400m"
    // Live checks of real extensions/stores hit the network: run them explicitly with -Pyomu.liveTests=true.
    systemProperty("yomu.liveTests", providers.gradleProperty("yomu.liveTests").getOrElse("false"))
    systemProperty("yomu.extJars", providers.gradleProperty("yomu.extJars").getOrElse(""))
    // Extensions bring OkHttp's zstd decoder; on a desktop JVM its native part comes from the zstd-kmp-jvm build.
    providers.gradleProperty("yomu.nativeLibDir").orNull?.let { dir ->
        systemProperty("java.library.path", listOf(dir, System.getProperty("java.library.path")).joinToString(File.pathSeparator))
    }
}
