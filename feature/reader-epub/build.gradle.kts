plugins {
    id("yomu.android.feature")
    alias(libs.plugins.kotlin.serialization)
}
dependencies { implementation(libs.androidx.webkit) }
