pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // QuickJS for Mihon/Tachiyomi extensions (same artifact the reference host app ships)
        maven("https://jitpack.io") { content { includeGroup("com.github.zhanghai.quickjs-java") } }
    }
}

rootProject.name = "Yomu"

include(":app")
include(":core:common", ":core:ui", ":core:database", ":core:datastore", ":core:network")
include(":domain", ":data")
include(
    ":feature:library", ":feature:reader", ":feature:reader-epub", ":feature:reader-pdf", ":feature:reader-manga",
    ":feature:annotations", ":feature:dictionary", ":feature:translator",
    ":feature:editor", ":feature:catalog", ":feature:downloads", ":feature:settings", ":feature:audio",
)
include(":source-api", ":sources:opds", ":sources:mangadex", ":sources:novels", ":sources:local")
include(":compat:tachiyomi")
