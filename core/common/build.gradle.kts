plugins { id("yomu.android.library") }
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    api(libs.kotlinx.coroutines.core)
}
