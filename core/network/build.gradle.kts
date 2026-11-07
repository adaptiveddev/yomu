plugins {
    id("yomu.android.library")
}
dependencies {
    implementation(project(":core:common"))
    api(libs.okhttp)
    implementation(libs.okhttp.logging)
    testImplementation(libs.okhttp.mockwebserver)
}
