package app.yomu.data.audio

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import app.yomu.data.library.ExtractedMeta
import timber.log.Timber

/** Tags of an audio file (ID3, MP4, Vorbis comments) through the platform's retriever. */
object AudioMeta {
    data class Tags(
        val title: String?,
        val album: String?,
        val artist: String?,
        val narrator: String?,
        val durationMs: Long,
        val picture: ByteArray?,
    )

    fun read(context: Context, uri: Uri): Tags? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            if (uri.scheme == "file") r.setDataSource(uri.path) else r.setDataSource(context, uri)
            fun get(key: Int) = r.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            Tags(
                title = get(MediaMetadataRetriever.METADATA_KEY_TITLE),
                album = get(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                artist = get(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: get(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: get(MediaMetadataRetriever.METADATA_KEY_AUTHOR),
                narrator = get(MediaMetadataRetriever.METADATA_KEY_COMPOSER),
                durationMs = get(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                picture = r.embeddedPicture,
            )
        } finally {
            runCatching { r.release() }
        }
    }.onFailure { Timber.d(it, "No audio tags for $uri") }.getOrNull()

    /**
     * Library metadata of an audiobook whose first track is [first]: a single file is named by its title tag, a
     * folder by the album tag (the tracks' own titles name the chapters).
     */
    fun extract(context: Context, first: Uri?, fallbackTitle: String, multiTrack: Boolean, cover: ByteArray? = null): ExtractedMeta {
        val tags = first?.let { read(context, it) }
        val title = (if (multiTrack) tags?.album else tags?.title ?: tags?.album) ?: fallbackTitle
        return ExtractedMeta(
            title = title,
            authors = listOfNotNull(tags?.artist).flatMap { it.split(';', '/') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            cover = tags?.picture ?: cover,
        )
    }
}
