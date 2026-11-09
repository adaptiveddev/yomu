plugins { id("yomu.android.feature") }
dependencies {
    implementation(project(":feature:annotations"))
    implementation(project(":feature:reader-manga"))
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.process)
}
