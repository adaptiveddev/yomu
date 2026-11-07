package app.yomu.data.backup

import android.content.res.Configuration
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FolderSyncProblemTest {
    @Test fun conflictCanBeShownInRussianWithoutDiscardingTechnicalCause() {
        val configuration = Configuration(RuntimeEnvironment.getApplication().resources.configuration)
        configuration.setLocale(Locale.forLanguageTag("ru"))
        val context = RuntimeEnvironment.getApplication().createConfigurationContext(configuration)
        val original = FolderSyncProblem(FolderSyncProblem.Kind.SIMILAR, "Манга", "different identity")
        val shown = original.forDisplay(context)
        assertTrue(shown.message!!.contains("Манга"))
        assertTrue(shown.message!!.contains("Сохраните обе копии"))
        assertSame(original, shown.cause)
    }
}
