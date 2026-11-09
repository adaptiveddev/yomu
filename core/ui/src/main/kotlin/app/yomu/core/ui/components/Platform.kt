package app.yomu.core.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import app.yomu.core.common.ScreenErrors
import app.yomu.core.ui.R
import app.yomu.core.ui.theme.Dimens
import kotlinx.coroutines.launch

/** Shared element support: NavHost provides both scopes, screens use [Modifier.sharedCover]. */
val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Whether the shared elements below may take part in a transition (1.9.0). False in a top-level tab that stays
 * composed while another one is on screen: its covers must not fly in from where they would be. A state read only when
 * a transition looks for its elements, so switching tabs does not recompose the covers.
 */
val LocalSharedElementsEnabled = staticCompositionLocalOf<State<Boolean>> { mutableStateOf(true) }

@Composable
fun Modifier.sharedCover(key: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val anim = LocalNavAnimatedScope.current ?: return this
    val enabled = LocalSharedElementsEnabled.current
    val config = remember(enabled) {
        object : SharedTransitionScope.SharedContentConfig {
            override val SharedTransitionScope.SharedContentState.isEnabled: Boolean get() = enabled.value
        }
    }
    return with(shared) { this@sharedCover.sharedElement(rememberSharedContentState("cover-$key", config), anim) }
}

/** Haptics that respect the user setting. */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

/** [perform] is the strong "something was saved" buzz; the others are lighter, for toggles, steps and thresholds. */
interface Haptic {
    fun perform()
    fun tick() = Unit
    fun confirm() = perform()
    fun toggle(on: Boolean) = tick()
}

@Composable
fun rememberHaptic(): Haptic {
    val enabled = LocalHapticsEnabled.current
    val h = LocalHapticFeedback.current
    return remember(enabled, h) {
        object : Haptic {
            private fun play(type: HapticFeedbackType) { if (enabled) h.performHapticFeedback(type) }
            override fun perform() = play(HapticFeedbackType.LongPress)
            override fun tick() = play(HapticFeedbackType.SegmentTick)
            override fun confirm() = play(HapticFeedbackType.Confirm)
            override fun toggle(on: Boolean) = play(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
        }
    }
}

/** One-time hints ("tap on a word", manga tap zones). Backed by the settings store in the app. */
interface HintStore {
    suspend fun isShown(key: String): Boolean
    suspend fun markShown(key: String)
}

val LocalHintStore = staticCompositionLocalOf<HintStore?> { null }

/** [after]: shown only once that hint has been dismissed, so a screen never stacks several hints at once. */
@Composable
fun OneTimeHint(key: String, text: String, modifier: Modifier = Modifier, after: String? = null) {
    val store = LocalHintStore.current ?: return
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(key, after) { visible = !store.isShown(key) && (after == null || store.isShown(after)) }
    if (!visible) return
    Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = MaterialTheme.shapes.medium, modifier = modifier.padding(Dimens.l)) {
        Column(Modifier.padding(Dimens.l)) {
            Text(text, color = MaterialTheme.colorScheme.inverseOnSurface, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { visible = false; scope.launch(ScreenErrors) { store.markShown(key) } }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.got_it), color = MaterialTheme.colorScheme.inversePrimary)
            }
        }
    }
}
