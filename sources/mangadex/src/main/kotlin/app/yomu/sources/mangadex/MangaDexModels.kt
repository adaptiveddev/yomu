package app.yomu.sources.mangadex

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class MdList(val data: List<MdEntity> = emptyList(), val limit: Int = 0, val offset: Int = 0, val total: Int = 0)

@Serializable
data class MdSingle(val data: MdEntity)

@Serializable
data class MdEntity(
    val id: String,
    val type: String,
    val attributes: MdAttributes? = null,
    val relationships: List<MdRelationship> = emptyList(),
)

@Serializable
data class MdRelationship(val id: String, val type: String, val attributes: MdAttributes? = null)

/**
 * Union of manga, chapter, cover, author, group and tag attributes (all optional).
 *
 * Fields whose shape differs between entity types stay [JsonElement]: `description` is a
 * localized map for manga, a plain string for covers and scanlation groups and sometimes `[]`
 * for tags; a strict `Map<String, String>` made whole pages fail with "Unexpected JSON token".
 */
@Serializable
data class MdAttributes(
    val title: JsonElement? = null,
    val altTitles: JsonElement? = null,
    val description: JsonElement? = null,
    val status: String? = null,
    val tags: List<MdEntity> = emptyList(),
    val name: JsonElement? = null,
    val group: String? = null,
    val fileName: String? = null,
    val volume: String? = null,
    val chapter: String? = null,
    val translatedLanguage: String? = null,
    val publishAt: String? = null,
    val readableAt: String? = null,
    val pages: Int = 0,
    val externalUrl: String? = null,
    val originalLanguage: String? = null,
)

@Serializable
data class MdAtHome(val baseUrl: String, val chapter: MdAtHomeChapter)

@Serializable
data class MdAtHomeChapter(val hash: String, val data: List<String> = emptyList(), val dataSaver: List<String> = emptyList())
