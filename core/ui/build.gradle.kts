plugins {
    id("yomu.android.library")
    id("yomu.android.compose")
}
dependencies {
    api(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.coil.compose)
    api(libs.compose.material3.adaptive.navigation.suite)
    api(libs.compose.material3.window.size)
}
