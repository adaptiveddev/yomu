plugins {
    id("yomu.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}
room { schemaDirectory("$projectDir/schemas") }
dependencies {
    api(libs.androidx.room.runtime)
    api(libs.androidx.room.ktx)
    api(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
}
