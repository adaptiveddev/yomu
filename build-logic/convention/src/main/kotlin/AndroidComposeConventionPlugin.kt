import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        val ext: CommonExtension<*, *, *, *, *, *> =
            extensions.findByType(ApplicationExtension::class.java) ?: extensions.getByType(LibraryExtension::class.java)
        ext.buildFeatures.compose = true
        dependencies {
            val bom = platform(libs.lib("compose-bom"))
            add("implementation", bom)
            add("androidTestImplementation", bom)
            add("implementation", libs.lib("compose-ui"))
            add("implementation", libs.lib("compose-foundation"))
            add("implementation", libs.lib("compose-animation"))
            add("implementation", libs.lib("compose-material3"))
            add("implementation", libs.lib("compose-material-icons"))
            add("implementation", libs.lib("compose-ui-tooling-preview"))
            add("debugImplementation", libs.lib("compose-ui-tooling"))
        }
        tasks.withType<KotlinCompile>().configureEach {
            compilerOptions.freeCompilerArgs.addAll(
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
                "-opt-in=androidx.compose.animation.ExperimentalSharedTransitionApi",
                "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            )
        }
    }
}
