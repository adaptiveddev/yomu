package app.yomu.feature.catalog

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import app.yomu.data.source.SourceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The logo of a source: the icon of the extension that provides it, the bundled logo of a built-in source, a symbol
 * for the local folder and OPDS catalogs, or a coloured monogram when nothing else is available.
 */
@Composable
internal fun SourceIcon(info: SourceInfo, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val logo = builtInLogo(info.logo)
    when {
        info.icon != null -> PackageIcon(info.icon, info.name, modifier, size)
        logo != null -> Image(
            painterResource(logo),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(iconShape(size)),
        )
        info.kind == SourceInfo.Kind.LOCAL -> SymbolIcon(Icons.Filled.Folder, modifier, size)
        info.kind == SourceInfo.Kind.OPDS -> SymbolIcon(Icons.Filled.RssFeed, modifier, size)
        else -> Monogram(info.name, modifier, size)
    }
}

/**
 * Rendered extension icons, shared by every list. Drawing an adaptive icon into a bitmap takes a few milliseconds; done
 * on the main thread for each row that scrolled into view it made the source and extension lists stutter (1.8.0).
 */
private object PackageIcons {
    private data class Key(val drawable: Drawable, val px: Int)

    private val cache = object : LruCache<Key, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.byteCount
    }

    fun cached(drawable: Drawable, px: Int): ImageBitmap? = cache.get(Key(drawable, px))?.asImageBitmap()

    /** Renders [drawable] (off the main thread); null when it cannot be drawn. */
    fun render(drawable: Drawable, px: Int): ImageBitmap? {
        val key = Key(drawable, px)
        cache.get(key)?.let { return it.asImageBitmap() }
        // Drawn from an own instance: the same drawable may be on screen elsewhere, and a drawable keeps its bounds
        // while it draws (the image itself is shared). Without a copy, never let two threads draw it at once.
        val copy = runCatching { drawable.constantState?.newDrawable() }.getOrNull() ?: drawable
        val bitmap = runCatching { synchronized(drawable) { copy.toBitmap(px, px) } }.getOrNull() ?: return null
        cache.put(key, bitmap)
        return bitmap.asImageBitmap()
    }
}

/** The icon of an extension package; a monogram of [name] when the package has none or it cannot be drawn. */
@Composable
internal fun PackageIcon(drawable: Drawable?, name: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }.coerceIn(16, 512)
    // Already rendered: shown in the first frame. Otherwise a quiet placeholder until the background render is done.
    var bitmap by remember(drawable, px) { mutableStateOf(drawable?.let { PackageIcons.cached(it, px) }) }
    var failed by remember(drawable, px) { mutableStateOf(drawable == null) }
    if (bitmap == null && !failed && drawable != null) {
        LaunchedEffect(drawable, px) {
            val rendered = withContext(Dispatchers.Default) { PackageIcons.render(drawable, px) }
            if (rendered == null) failed = true else bitmap = rendered
        }
    }
    val image = bitmap
    when {
        image != null -> Image(image, contentDescription = null, modifier = modifier.size(size).clip(iconShape(size)))
        failed -> Monogram(name, modifier, size)
        else -> Box(modifier.size(size).clip(iconShape(size)).background(MaterialTheme.colorScheme.surfaceContainerHigh))
    }
}

/** A rounded square with the initials of [name] on a colour derived from it, so every source keeps its own colour. */
@Composable
internal fun Monogram(name: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.4f
    // Cyan through violet and pink to orange, like the cover placeholders: no greens next to the lavender accent.
    val hue = (200f + Math.floorMod(name.trim().lowercase().hashCode(), 180)) % 360f
    val background = if (dark) Color.hsl(hue, 0.30f, 0.27f) else Color.hsl(hue, 0.45f, 0.87f)
    val content = if (dark) Color.hsl(hue, 0.55f, 0.85f) else Color.hsl(hue, 0.60f, 0.27f)
    val letters = initials(name)
    Box(modifier.size(size).clip(iconShape(size)).background(background), contentAlignment = Alignment.Center) {
        Text(
            letters,
            color = content,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * if (letters.length > 1) 0.36f else 0.46f).sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun SymbolIcon(icon: ImageVector, modifier: Modifier, size: Dp) {
    Box(
        modifier.size(size).clip(iconShape(size)).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(size * 0.55f))
    }
}

internal fun iconShape(size: Dp): Shape = RoundedCornerShape(size * 0.26f)

/** "Weeb Central" → "WC", "AComics" → "A", "MangaDex (EN)" → "M". */
internal fun initials(name: String): String {
    val core = name.replace(Regex("""\(.*?\)|\[.*?]"""), " ").trim().ifEmpty { name.trim() }
    val words = core.split(' ', '-', '_').map { w -> w.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }
    val letters = when {
        words.size >= 2 && words[1].first().isLetter() -> "${words[0].first()}${words[1].first()}"
        words.isNotEmpty() -> words[0].take(1)
        else -> core.take(1)
    }
    return letters.uppercase().ifEmpty { "?" }
}

@DrawableRes
private fun builtInLogo(key: String?): Int? = when (key) {
    SourceInfo.LOGO_MANGADEX -> R.drawable.source_mangadex
    SourceInfo.LOGO_GUTENBERG -> R.drawable.source_gutenberg
    SourceInfo.LOGO_ARCHIVE -> R.drawable.source_archive
    SourceInfo.LOGO_OPEN_LIBRARY -> R.drawable.source_openlibrary
    SourceInfo.LOGO_WIKISOURCE -> R.drawable.source_wikisource
    else -> null
}
