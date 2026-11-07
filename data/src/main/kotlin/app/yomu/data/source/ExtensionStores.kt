package app.yomu.data.source

import app.yomu.compat.tachiyomi.TachiyomiCompat
import app.yomu.source.await
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.util.zip.GZIPInputStream

enum class ContentWarning { SAFE, MIXED, NSFW }

/** An extension repository ("store"): its index location, display name and the key its extensions are signed with. */
data class StoreInfo(
    val url: String,
    val indexUrl: String,
    val name: String,
    val badge: String,
    val signingKey: String?,
    val website: String?,
    val isLegacy: Boolean,
    val extensionListUrl: String?,
)

data class AvailableSource(val id: Long, val name: String, val lang: String, val baseUrl: String)

/** An extension offered by a store. */
data class AvailableExtension(
    val storeUrl: String,
    val storeName: String,
    val name: String,
    val pkg: String,
    val apkUrl: String,
    val iconUrl: String?,
    val lang: String,
    val versionCode: Long,
    val versionName: String,
    val libVersion: Double?,
    val contentWarning: ContentWarning,
    val sources: List<AvailableSource>,
) {
    /** Tachiyomi/Mihon extensions declare lib 1.x; Yomu's own extensions use the Yomu API instead. */
    val isSupported: Boolean get() = libVersion == null || TachiyomiCompat.isLibVersionSupported(libVersion)
    val isNsfw: Boolean get() = contentWarning == ContentWarning.NSFW
    val languages: Set<String> get() = sources.map { it.lang }.toSet().ifEmpty { setOf(lang) }
}

/**
 * Reads extension stores in the formats used by Mihon/Tachiyomi repositories:
 * - legacy `index.min.json` (+ `repo.json` with name and signing key next to it),
 * - `repo.json` pointing to an `index_v2`,
 * - index v2: protobuf (optionally gzip) or JSON `NetworkExtensionStore`, with the list embedded or at a separate URL.
 */
@OptIn(ExperimentalSerializationApi::class)
class ExtensionStores(private val client: () -> OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; coerceInputValues = true }
    private val protoBuf = ProtoBuf

    /**
     * Normalizes what the user typed: "Add to Mihon/Tachiyomi" links, GitHub repo pages, folders and bare hosts become
     * an index URL; a scheme pasted over another one ("https://https://…") and stray spaces are dropped.
     */
    fun normalize(input: String): String {
        var u = input.trim()
        // tachiyomi://add-repo?url=… and mihon://extension-store?url=… carry the address in a parameter
        Regex("""^(?:tachiyomi|mihon)://[^?]*\?(?:.*&)?url=([^&#]+)""", RegexOption.IGNORE_CASE).find(u)?.let { m ->
            u = runCatching { URLDecoder.decode(m.groupValues[1], "UTF-8") }.getOrDefault(m.groupValues[1])
        }
        u = u.replace(Regex("""\s+"""), "").removeSuffix("/")
        Regex("""^(?:https?://)+(https?://)""", RegexOption.IGNORE_CASE).find(u)?.let { m -> u = m.groupValues[1] + u.substring(m.range.last + 1) }
        Regex("""^(https?)://""", RegexOption.IGNORE_CASE).find(u)?.let { m -> u = m.groupValues[1].lowercase() + u.substring(m.groupValues[1].length) }
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u.removePrefix("//")
        // https://github.com/owner/repo/tree/branch -> raw files of that branch
        Regex("""^https://github\.com/([^/]+)/([^/]+)/(?:tree|blob)/([^/]+)/?(.*)$""").find(u)?.let { m ->
            val (owner, repo, branch, rest) = m.destructured
            u = "https://raw.githubusercontent.com/$owner/$repo/$branch" + if (rest.isNotEmpty()) "/$rest" else ""
        }
        val last = u.substringAfterLast('/')
        return if (last.contains('.') && !u.substringAfter("://").substringBefore('/').endsWith(last)) u else "$u/index.min.json"
    }

    suspend fun fetch(url: String): Pair<StoreInfo, List<AvailableExtension>> = resolve(url, url, depth = 0)

    private suspend fun resolve(original: String, indexUrl: String, depth: Int): Pair<StoreInfo, List<AvailableExtension>> {
        if (depth > 3) throw IOException("Too many redirects in store index")
        val bytes = get(indexUrl)
        val first = bytes.firstOrNull { !it.toInt().toChar().isWhitespace() }?.toInt()?.toChar()
        return when (first) {
            '[' -> {
                val list = json.decodeFromString<List<LegacyExtension>>(bytes.decodeToString())
                val base = indexUrl.substringBeforeLast('/')
                val meta = runCatching { json.decodeFromString<LegacyRepo>(get("$base/repo.json").decodeToString()) }.getOrNull()
                if (meta?.indexV2 != null && depth == 0) {
                    runCatching { return resolve(original, meta.indexV2, depth + 1) }
                }
                val store = StoreInfo(
                    url = original, indexUrl = indexUrl,
                    name = meta?.meta?.name ?: hostOf(indexUrl), badge = meta?.meta?.shortName ?: meta?.meta?.name ?: hostOf(indexUrl),
                    signingKey = meta?.meta?.signingKeyFingerprint?.lowercase(), website = meta?.meta?.website,
                    isLegacy = true, extensionListUrl = null,
                )
                store to list.filter { it.pkg.isNotBlank() }.map { it.toAvailable(store, base) }
            }
            '{' -> {
                val text = bytes.decodeToString()
                val legacy = runCatching { json.decodeFromString<LegacyRepo>(text) }.getOrNull()
                if (legacy != null) {
                    if (legacy.indexV2 != null) return resolve(original, legacy.indexV2, depth + 1)
                    // repo.json of a legacy store: the list is next to it
                    val base = indexUrl.substringBeforeLast('/')
                    return resolve(original, "$base/index.min.json", depth + 1)
                }
                fromNetworkStore(original, indexUrl, json.decodeFromString<NetworkStore>(text))
            }
            else -> fromNetworkStore(original, indexUrl, protoBuf.decodeFromByteArray(NetworkStore.serializer(), bytes))
        }
    }

    private suspend fun fromNetworkStore(original: String, indexUrl: String, s: NetworkStore): Pair<StoreInfo, List<AvailableExtension>> {
        val store = StoreInfo(
            url = original, indexUrl = indexUrl, name = s.name.ifBlank { hostOf(indexUrl) }, badge = s.badgeLabel.ifBlank { s.name },
            signingKey = s.signingKey.lowercase().ifBlank { null }, website = s.contact?.website, isLegacy = false,
            extensionListUrl = s.extensionListUrl,
        )
        val list = s.extensionList ?: s.extensionListUrl?.let { listUrl ->
            val bytes = get(listUrl)
            if (bytes.firstOrNull()?.toInt()?.toChar() == '{') {
                json.decodeFromString<NetworkStore.ExtensionList>(bytes.decodeToString())
            } else {
                protoBuf.decodeFromByteArray(NetworkStore.ExtensionList.serializer(), bytes)
            }
        }
        return store to list?.extensions.orEmpty().map { it.toAvailable(store) }
    }

    private suspend fun get(url: String): ByteArray {
        val response = client().newCall(Request.Builder().url(url).header("Accept", "*/*").build()).await()
        val bytes = response.use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            r.body.bytes()
        }
        return if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) GZIPInputStream(bytes.inputStream()).readBytes() else bytes
    }

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/')

    // region network models (same wire format as Mihon's extension stores)

    @Serializable
    data class LegacyRepo(@SerialName("index_v2") val indexV2: String? = null, val meta: Meta) {
        @Serializable
        data class Meta(val name: String, val shortName: String? = null, val website: String? = null, val signingKeyFingerprint: String? = null)
    }

    @Serializable
    data class LegacyExtension(
        val name: String = "",
        val pkg: String = "",
        val apk: String = "",
        val lang: String = "all",
        val code: Long = 0,
        val version: String = "",
        val nsfw: Int = 0,
        val sources: List<LegacySource>? = null,
    ) {
        fun toAvailable(store: StoreInfo, base: String) = AvailableExtension(
            storeUrl = store.url, storeName = store.name,
            name = name.removePrefix("Tachiyomi: ").removePrefix("Yomu: "),
            pkg = pkg,
            apkUrl = if (apk.startsWith("http")) apk else "$base/apk/$apk",
            iconUrl = "$base/icon/$pkg.png",
            lang = lang, versionCode = code, versionName = version,
            libVersion = if (pkg.startsWith("eu.kanade.tachiyomi.")) version.substringBeforeLast('.').toDoubleOrNull() else null,
            contentWarning = if (nsfw == 1) ContentWarning.NSFW else ContentWarning.SAFE,
            sources = sources.orEmpty().map { AvailableSource(it.id.toLongOrNull() ?: 0, it.name.ifBlank { name }, it.lang.ifBlank { lang }, it.baseUrl) },
        )
    }

    @Serializable
    data class LegacySource(val name: String = "", val lang: String = "", val id: String = "", val baseUrl: String = "")

    @Serializable
    data class NetworkStore(
        @ProtoNumber(1) val name: String = "",
        @ProtoNumber(2) val badgeLabel: String = "",
        @ProtoNumber(3) val signingKey: String = "",
        @ProtoNumber(4) val contact: Contact? = null,
        @ProtoNumber(101) val extensionList: ExtensionList? = null,
        @ProtoNumber(102) val extensionListUrl: String? = null,
    ) {
        @Serializable
        data class Contact(@ProtoNumber(1) val website: String = "", @ProtoNumber(2) val discord: String? = null)

        @Serializable
        data class ExtensionList(@ProtoNumber(1) val extensions: List<Extension> = emptyList())

        @Serializable
        data class Extension(
            @ProtoNumber(1) val name: String = "",
            @ProtoNumber(2) val packageName: String = "",
            @ProtoNumber(3) val resources: Resources = Resources(),
            @ProtoNumber(4) val extensionLib: String = "",
            @ProtoNumber(5) val versionCode: Long = 0,
            @ProtoNumber(6) val versionName: String = "",
            @ProtoNumber(7) val contentWarning: Int = 0,
            @ProtoNumber(8) val sources: List<Source> = emptyList(),
        ) {
            fun toAvailable(store: StoreInfo): AvailableExtension {
                val langs = sources.map { it.language }.toSet()
                return AvailableExtension(
                    storeUrl = store.url, storeName = store.name, name = name, pkg = packageName,
                    apkUrl = resources.apkUrl, iconUrl = resources.iconUrl.ifBlank { null },
                    lang = if (langs.size == 1) langs.first() else "all",
                    versionCode = versionCode, versionName = versionName, libVersion = extensionLib.toDoubleOrNull(),
                    contentWarning = when (contentWarning) { 2 -> ContentWarning.MIXED; 3 -> ContentWarning.NSFW; else -> ContentWarning.SAFE },
                    sources = sources.map { AvailableSource(it.id, it.name, it.language, it.homeUrl) },
                )
            }
        }

        @Serializable
        data class Resources(@ProtoNumber(1) val apkUrl: String = "", @ProtoNumber(2) val iconUrl: String = "")

        @Serializable
        data class Source(
            @ProtoNumber(1) val id: Long = 0,
            @ProtoNumber(2) val name: String = "",
            @ProtoNumber(3) val language: String = "",
            @ProtoNumber(4) val homeUrl: String = "",
            @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
            @ProtoNumber(7) val message: String? = null,
        )
    }
    // endregion
}
