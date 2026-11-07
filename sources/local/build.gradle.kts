plugins {
    id("yomu.android.library")
    alias(libs.plugins.kotlin.serialization)
}
dependencies {
    api(project(":source-api"))
    implementation(project(":core:common"))
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.serialization.json)
}
