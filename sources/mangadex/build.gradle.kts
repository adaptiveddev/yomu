plugins {
    id("yomu.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}
dependencies {
    api(project(":source-api"))
    testImplementation(libs.okhttp.mockwebserver)
}
