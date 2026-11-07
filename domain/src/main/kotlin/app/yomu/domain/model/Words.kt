package app.yomu.domain.model

data class SrsState(
    val ease: Double = 2.5,
    val intervalDays: Int = 0,
    val repetitions: Int = 0,
    val dueAt: Long = 0,
    val lapses: Int = 0,
    val lastReviewedAt: Long? = null,
)

data class Word(
    val id: Long = 0,
    val term: String,
    val lang: String? = null,
    val translation: String? = null,
    val definition: String? = null,
    val context: String? = null,
    val bookId: Long? = null,
    val bookTitle: String? = null,
    val locator: Locator? = null,
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** 0 = new … 5 = mastered. */
    val level: Int = 0,
    val srs: SrsState = SrsState(),
)

enum class ReviewMode { WORD_TO_TRANSLATION, TRANSLATION_TO_WORD, CLOZE }

enum class DictFormat { STARDICT, DSL, WIKTIONARY, JMDICT, ONLINE }

data class DictionaryInfo(
    val id: Long,
    val name: String,
    val format: DictFormat,
    val sourceLang: String? = null,
    val targetLang: String? = null,
    val entryCount: Int = 0,
    val enabled: Boolean = true,
    val order: Int = 0,
)

data class DictEntry(
    val headword: String,
    val reading: String? = null,
    val partOfSpeech: String? = null,
    val ipa: String? = null,
    val definitions: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    val dictionary: String = "",
)

data class LookupResult(
    val query: String,
    val matched: String?,
    val entries: List<DictEntry>,
    val online: Boolean = false,
)

data class ReadingStats(
    val totalMs: Long,
    val sessions: Int,
    val pages: Int,
    val chars: Long,
    val booksFinished: Int,
    val streakDays: Int,
    val bestStreak: Int,
    /** Epoch day -> milliseconds read. */
    val perDay: Map<Long, Long>,
) {
    /** Characters per minute (text books) or pages per hour when chars are unknown. */
    val charsPerMinute: Int get() = if (totalMs <= 0) 0 else (chars * 60_000 / totalMs).toInt()
    val pagesPerHour: Int get() = if (totalMs <= 0) 0 else (pages * 3_600_000L / totalMs).toInt()
}
