plugins { id("yomu.android.feature") }
dependencies {
    implementation(project(":feature:catalog"))
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.documentfile)
}
