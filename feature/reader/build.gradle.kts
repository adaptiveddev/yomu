plugins { id("yomu.android.feature") }
dependencies {
    implementation(project(":feature:reader-epub"))
    implementation(project(":feature:reader-pdf"))
    implementation(project(":feature:reader-manga"))
    implementation(project(":feature:annotations"))
    implementation(project(":feature:dictionary"))
    implementation(project(":feature:translator"))
    implementation(libs.telephoto.zoomable.coil)
}
