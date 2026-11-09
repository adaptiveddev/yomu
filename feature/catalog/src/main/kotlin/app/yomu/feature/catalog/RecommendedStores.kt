package app.yomu.feature.catalog

/**
 * Well-known extension repositories offered in the Repositories tab, so the user does not have to look them up.
 * Base addresses: the store reader finds `repo.json` and the current index format there. Adding one still goes
 * through the usual preview (name, size, signing key) and confirmation.
 */
internal data class RecommendedStore(val name: String, val url: String, val description: Int, val website: String)

internal val RECOMMENDED_STORES = listOf(
    RecommendedStore("Keiyoushi", "https://raw.githubusercontent.com/keiyoushi/extensions/repo", R.string.ext_rec_keiyoushi, "https://github.com/keiyoushi/extensions"),
    RecommendedStore("Yūzōnō", "https://raw.githubusercontent.com/yuzono/manga-repo/repo", R.string.ext_rec_yuzono, "https://github.com/yuzono/tachiyomi-extensions"),
    RecommendedStore("Zosetsu", "https://raw.githubusercontent.com/zosetsu-repo/tachi-repo/repo", R.string.ext_rec_zosetsu, "https://github.com/zosetsu-repo/tachi-repo"),
    RecommendedStore("Kavita", "https://raw.githubusercontent.com/Kareadita/tach-extension/repo", R.string.ext_rec_kavita, "https://github.com/Kareadita/tach-extension"),
    RecommendedStore("Suwayomi", "https://raw.githubusercontent.com/suwayomi/tachiyomi-extension/repo", R.string.ext_rec_suwayomi, "https://github.com/Suwayomi/tachiyomi-extension"),
)

/** Whether [storeUrl] (raw, github.com or jsDelivr address, with or without a trailing part) is the same repository as [rec]. */
internal fun RecommendedStore.matches(storeUrl: String): Boolean {
    val key = url.removePrefix("https://raw.githubusercontent.com/").substringBeforeLast("/").lowercase()
    val s = storeUrl.lowercase().replace("@", "/")
    return Regex("(^|/)${Regex.escape(key)}(/|$)").containsMatchIn(s)
}
