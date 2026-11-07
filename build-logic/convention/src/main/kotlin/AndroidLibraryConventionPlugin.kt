import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.android")
        extensions.configure<LibraryExtension> {
            configureAndroid(this)
            defaultConfig { consumerProguardFiles("consumer-rules.pro") }
            val path = target.path.removePrefix(":").replace(":", ".").replace("-", ".")
            namespace = "app.yomu.$path"
        }
        dependencies {
            add("implementation", libs.lib("kotlinx-coroutines-android"))
            add("implementation", libs.lib("timber"))
        }
    }
}
