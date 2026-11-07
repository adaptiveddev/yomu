package eu.kanade.tachiyomi

import app.yomu.compat.tachiyomi.TachiyomiCompat

/**
 * Used by extensions.
 */
@Suppress("UNUSED")
object AppInfo {
    /**
     * Version code of the host application. May be useful for sharing as User-Agent information.
     * Note that this value differs between forks so logic should not rely on it.
     *
     * @since extension-lib 1.3
     */
    fun getVersionCode(): Int = TachiyomiCompat.versionCode

    /**
     * Version name of the host application. May be useful for sharing as User-Agent information.
     * Note that this value differs between forks so logic should not rely on it.
     *
     * @since extension-lib 1.3
     */
    fun getVersionName(): String = TachiyomiCompat.versionName

    /**
     * A list of supported image MIME types by the reader.
     *
     * @since extension-lib 1.5
     */
    fun getSupportedImageMimeTypes(): List<String> =
        listOf("image/jpeg", "image/png", "image/gif", "image/webp", "image/avif", "image/heif", "image/jxl")
}
