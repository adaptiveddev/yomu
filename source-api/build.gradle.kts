plugins {
    id("yomu.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}
dependencies {
    api(libs.okhttp)
    api(libs.jsoup)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
}
