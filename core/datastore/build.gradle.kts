plugins {
    id("yomu.android.library")
    alias(libs.plugins.kotlin.serialization)
}
dependencies {
    api(project(":domain"))
    api(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
}
