plugins {
    id("yomu.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}
dependencies {
    api(project(":source-api"))
    testImplementation(libs.okhttp.mockwebserver)
}
tasks.withType<Test>().configureEach {
    // NovelSourcesLiveTest reads the real sites: run it explicitly with -Pyomu.liveTests=true.
    systemProperty("yomu.liveTests", providers.gradleProperty("yomu.liveTests").getOrElse("false"))
}
