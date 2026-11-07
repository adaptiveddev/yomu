import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.v(name: String): String = findVersion(name).get().requiredVersion
internal fun VersionCatalog.lib(name: String) = findLibrary(name).get()

internal fun Project.configureAndroid(ext: CommonExtension<*, *, *, *, *, *>) {
    ext.apply {
        compileSdk = libs.v("compileSdk").toInt()
        defaultConfig {
            minSdk = libs.v("minSdk").toInt()
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            vectorDrawables { useSupportLibrary = true }
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        testOptions {
            unitTests {
                isReturnDefaultValues = true
                isIncludeAndroidResources = true
            }
        }
        lint {
            abortOnError = true
            checkReleaseBuilds = false
            disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "ObsoleteLintCustomCheck", "OldTargetApi")
        }
        packaging {
            resources {
                excludes += setOf(
                    "/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                    "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties",
                )
            }
        }
    }
    configureKotlin()
    addTestDependencies()
}

internal fun Project.configureKotlin() {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            optIns()
        }
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxHeapSize = "1g"
        systemProperty("robolectric.logging.enabled", "false")
    }
}

private fun KotlinJvmCompilerOptions.optIns() {
    freeCompilerArgs.addAll(
        "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        "-opt-in=kotlinx.coroutines.FlowPreview",
        "-Xannotation-default-target=param-property",
    )
}

internal fun Project.addTestDependencies() {
    dependencies {
        add("testImplementation", platform(libs.lib("junit-bom")))
        add("testImplementation", libs.lib("junit-jupiter"))
        add("testRuntimeOnly", libs.lib("junit-platform-launcher"))
        add("testImplementation", libs.lib("mockk"))
        add("testImplementation", libs.lib("turbine"))
        add("testImplementation", libs.lib("kotlinx-coroutines-test"))
    }
}
