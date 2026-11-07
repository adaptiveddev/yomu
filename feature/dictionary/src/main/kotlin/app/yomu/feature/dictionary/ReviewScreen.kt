package app.yomu.feature.dictionary

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.yomu.core.ui.components.EmptyState
import app.yomu.core.ui.components.SkeletonBox
import app.yomu.core.ui.components.rememberHaptic
import app.yomu.core.ui.theme.Dimens
import app.yomu.domain.model.ReviewMode
import app.yomu.feature.translator.Speaker
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import kotlin.math.abs
import kotlin.math.roundToInt
import app.yomu.core.ui.R as UiR

@Composable
fun ReviewScreen(viewModel: ReviewViewModel, onBack: () -> Unit) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val speaker: Speaker = koinInject()
    val haptic = rememberHaptic()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(UiR.string.back)) } },
                title = {
                    Column {
                        Text(stringResource(R.string.review_title))
                        if (!s.loading && s.queue.isNotEmpty()) Text(stringResource(R.string.review_progress, (s.index + 1).coerceAtMost(s.queue.size), s.queue.size), style = MaterialTheme.typography.bodySmall)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(horizontal = Dimens.l)) {
                Text(stringResource(R.string.review_daily, s.reviewedToday, s.goal), style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(progress = { (s.reviewedToday.toFloat() / s.goal.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = Dimens.xs))
            }
            when {
                s.loading -> Box(Modifier.fillMaxSize().padding(Dimens.xl), contentAlignment = Alignment.Center) { SkeletonBox(Modifier.fillMaxWidth().height(280.dp)) }
                s.queue.isEmpty() -> EmptyState(
                    title = stringResource(R.string.review_nothing_due),
                    message = stringResource(R.string.review_nothing_due_text),
                    icon = Icons.Outlined.EmojiEvents,
                    actionLabel = stringResource(R.string.review_practice),
                    onAction = { viewModel.load(practice = true) },
                )
                s.finished -> EmptyState(
                    title = stringResource(R.string.review_done),
                    message = stringResource(R.string.review_done_text, s.known, s.unknown),
                    icon = Icons.Outlined.EmojiEvents,
                    actionLabel = stringResource(UiR.string.done),
                    onAction = onBack,
                )
                else -> {
                    val card = s.current!!
                    SwipeCard(
                        key = s.index,
                        flipped = s.flipped,
                        onFlip = viewModel::flip,
                        onSwiped = { knew -> haptic.perform(); viewModel.answer(knew) },
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(Dimens.l),
                    ) {
                        CardFace(card, s.flipped, onSpeak = { speaker.speak(card.word.term, card.word.lang) })
                    }
                    Text(
                        stringResource(if (s.flipped) R.string.review_hint_answer else R.string.review_hint_flip),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth().padding(Dimens.l), horizontalArrangement = Arrangement.spacedBy(Dimens.m)) {
                        OutlinedButton(onClick = { haptic.perform(); viewModel.answer(false) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                            Icon(Icons.Default.Close, null)
                            Text(stringResource(R.string.review_dont_know), Modifier.padding(start = 6.dp))
                        }
                        Button(
                            onClick = { haptic.perform(); viewModel.answer(true) },
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                            colors = ButtonDefaults.buttonColors(),
                        ) {
                            Icon(Icons.Default.Check, null)
                            Text(stringResource(R.string.review_know), Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeCard(key: Int, flipped: Boolean, onFlip: () -> Unit, onSwiped: (Boolean) -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    val offset = remember(key) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 120.dp.toPx() }
    val rotation by animateFloatAsState(if (flipped) 180f else 0f, tween(280), label = "flip")
    val density = LocalDensity.current.density
    Box(
        modifier
            .offset { IntOffset(offset.value.roundToInt(), 0) }
            .graphicsLayer {
                rotationZ = offset.value / 40f
                rotationY = rotation
                cameraDistance = 12f * density
            }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { d -> scope.launch { offset.snapTo(offset.value + d) } },
                onDragStopped = {
                    if (abs(offset.value) > threshold) {
                        val knew = offset.value > 0
                        offset.animateTo(if (knew) threshold * 4 else -threshold * 4, tween(180))
                        onSwiped(knew)
                    } else {
                        offset.animateTo(0f, tween(180))
                    }
                },
            )
            .clickable(onClick = onFlip),
        contentAlignment = Alignment.Center,
    ) {
        Card(Modifier.fillMaxSize().graphicsLayer { rotationY = if (rotation > 90f) 180f else 0f }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Box(Modifier.fillMaxSize().padding(Dimens.xl), contentAlignment = Alignment.Center) { content() }
        }
    }
    LaunchedEffect(key) { offset.snapTo(0f) }
}

@Composable
private fun CardFace(card: ReviewCard, flipped: Boolean, onSpeak: () -> Unit) {
    val w = card.word
    val meaning = w.translation ?: w.definition ?: ""
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 520.dp)) {
        Text(stringResource(card.mode.label()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(Dimens.l))
        when (card.mode) {
            ReviewMode.WORD_TO_TRANSLATION -> {
                Text(w.term, style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
                if (flipped) Answer(meaning)
            }
            ReviewMode.TRANSLATION_TO_WORD -> {
                Text(meaning, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                if (flipped) Answer(w.term)
            }
            ReviewMode.CLOZE -> {
                val ctx = w.context.orEmpty()
                val blanked = ctx.replace(Regex(Regex.escape(w.term), RegexOption.IGNORE_CASE), "_____")
                Text(if (flipped) ctx else blanked, style = MaterialTheme.typography.titleLarge.copy(fontStyle = FontStyle.Italic), textAlign = TextAlign.Center)
                if (flipped) Answer(w.term + if (meaning.isNotBlank()) " — $meaning" else "") else if (meaning.isNotBlank()) Text(meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Dimens.m))
            }
        }
        IconButton(onClick = onSpeak, modifier = Modifier.padding(top = Dimens.m)) { Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.dict_speak)) }
        w.bookTitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
    }
}

@Composable
private fun Answer(text: String) {
    val desc = stringResource(R.string.review_answer)
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = Dimens.l).semantics { contentDescription = "$desc: $text" },
    )
}

fun ReviewMode.label(): Int = when (this) {
    ReviewMode.WORD_TO_TRANSLATION -> R.string.review_mode_word
    ReviewMode.TRANSLATION_TO_WORD -> R.string.review_mode_translation
    ReviewMode.CLOZE -> R.string.review_mode_cloze
}
