package app.yomu.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import app.yomu.core.common.AppError
import app.yomu.core.ui.theme.YomuTheme

@PreviewLightDark
@PreviewFontScale
@Composable
private fun BookCardsPreview() = YomuTheme {
    Surface {
        Column {
            BookGridItem("The Brothers Karamazov", "Fyodor Dostoevsky", null, 0.4f, Modifier.width(140.dp), badge = "EPUB", onClick = {})
            BookListItem("Вишнёвый сад", "Антон Чехов", null, 0.75f, meta = "PDF · 2.1 MB", onClick = {})
        }
    }
}

@PreviewLightDark
@Composable
private fun StatesPreview() = YomuTheme {
    Surface {
        Column {
            EmptyState("Library is empty", message = "Import books to start reading", actionLabel = "Import", onAction = {})
        }
    }
}

@Preview
@PreviewFontScale
@Composable
private fun ErrorPreview() = YomuTheme { Surface { ErrorState(AppError.NoNetwork(), onRetry = {}) } }

@PreviewLightDark
@Composable
private fun SkeletonPreview() = YomuTheme { Surface { SkeletonList(count = 4) } }

@PreviewLightDark
@PreviewFontScale
@Composable
private fun InputsPreview() = YomuTheme {
    Surface {
        Column {
            SearchField("dune", {}, "Search")
            FilterChipsRow(listOf(ChipItem("EPUB", true, {}), ChipItem("PDF", false, {}), ChipItem("Manga", false, {})))
            PreferenceSwitch("Dynamic colours", true, {}, summary = "Use wallpaper colours", icon = Icons.Outlined.Palette)
            PreferenceSlider("Font size", 100f, 50f..250f, {}, { "${it.toInt()}%" })
        }
    }
}
