plugins { id("yomu.android.feature") }
dependencies {
    implementation(project(":core:network"))
    api(libs.media3.session)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
}
