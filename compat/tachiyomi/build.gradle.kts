// Runtime for Mihon/Tachiyomi extensions (extensions-lib 1.4–1.6).
// The eu.kanade.tachiyomi.* sources are adapted from Mihon (Apache-2.0), see NOTICE.
plugins {
    id("yomu.android.library")
    alias(libs.plugins.kotlin.serialization)
}
dependencies {
    // Everything extensions expect the host to provide at runtime ("common" bundle of extensions-source).
    api(libs.okhttp)
    api(libs.okhttp.brotli)
    api(libs.okhttp.zstd)
    api(libs.jsoup)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.serialization.json.okio)
    api(libs.kotlinx.serialization.protobuf)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.android)
    api(libs.injekt)
    api(libs.rxjava)
    api(libs.quickjs)
    api(libs.androidx.preference)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.core.ktx)
}
