import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("yomu.android.library")
        pluginManager.apply("yomu.android.compose")
        dependencies {
            add("implementation", project(":core:common"))
            add("implementation", project(":core:ui"))
            add("implementation", project(":core:datastore"))
            add("implementation", project(":domain"))
            add("implementation", project(":data"))
            add("implementation", platform(libs.lib("koin-bom")))
            add("implementation", libs.lib("koin-android"))
            add("implementation", libs.lib("koin-compose"))
            add("implementation", libs.lib("androidx-core-ktx"))
            add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
            add("implementation", libs.lib("androidx-activity-compose"))
            add("implementation", libs.lib("coil-compose"))
            add("implementation", libs.lib("kotlinx-serialization-json"))
        }
    }
}
