package app.yomu.domain.text

/**
 * Compact Japanese deinflector in the spirit of Yomichan/10ten: produces dictionary forms for common verb and
 * adjective conjugations. Combined with longest-prefix dictionary matching it works as a lightweight tokenizer.
 */
object JapaneseDeinflector {
    private data class Rule(val from: String, val to: String)

    private val rules = listOf(
        // polite / negative / past / te-forms of ichidan verbs
        Rule("ませんでした", "る"), Rule("ました", "る"), Rule("ません", "る"), Rule("ます", "る"), Rule("ましょう", "る"),
        Rule("なかった", "る"), Rule("ない", "る"), Rule("た", "る"), Rule("て", "る"), Rule("られる", "る"), Rule("させる", "る"),
        Rule("よう", "る"), Rule("れば", "る"), Rule("ろ", "る"), Rule("たい", "る"), Rule("ている", "る"), Rule("ていた", "る"),
        // godan verbs: past / te
        Rule("った", "う"), Rule("った", "つ"), Rule("った", "る"), Rule("って", "う"), Rule("って", "つ"), Rule("って", "る"),
        Rule("んだ", "む"), Rule("んだ", "ぶ"), Rule("んだ", "ぬ"), Rule("んで", "む"), Rule("んで", "ぶ"), Rule("んで", "ぬ"),
        Rule("いた", "く"), Rule("いて", "く"), Rule("いだ", "ぐ"), Rule("いで", "ぐ"), Rule("した", "す"), Rule("して", "す"),
        Rule("行った", "行く"), Rule("行って", "行く"),
        // godan verbs: masu-stem
        Rule("います", "う"), Rule("きます", "く"), Rule("ぎます", "ぐ"), Rule("します", "す"), Rule("ちます", "つ"),
        Rule("にます", "ぬ"), Rule("びます", "ぶ"), Rule("みます", "む"), Rule("ります", "る"),
        Rule("いました", "う"), Rule("きました", "く"), Rule("しました", "す"), Rule("みました", "む"), Rule("りました", "る"),
        // godan negative
        Rule("わない", "う"), Rule("かない", "く"), Rule("がない", "ぐ"), Rule("さない", "す"), Rule("たない", "つ"),
        Rule("なない", "ぬ"), Rule("ばない", "ぶ"), Rule("まない", "む"), Rule("らない", "る"),
        // godan potential / volitional / conditional
        Rule("える", "う"), Rule("ける", "く"), Rule("げる", "ぐ"), Rule("せる", "す"), Rule("てる", "つ"), Rule("める", "む"),
        Rule("れる", "る"), Rule("べる", "ぶ"), Rule("おう", "う"), Rule("こう", "く"), Rule("そう", "す"), Rule("もう", "む"),
        Rule("ろう", "る"), Rule("えば", "う"), Rule("けば", "く"), Rule("せば", "す"), Rule("めば", "む"), Rule("れば", "る"),
        // suru / kuru
        Rule("しました", "する"), Rule("します", "する"), Rule("しない", "する"), Rule("した", "する"), Rule("して", "する"),
        Rule("きた", "くる"), Rule("きて", "くる"), Rule("こない", "くる"), Rule("来た", "来る"), Rule("来て", "来る"),
        // i-adjectives
        Rule("くなかった", "い"), Rule("くない", "い"), Rule("かった", "い"), Rule("くて", "い"), Rule("く", "い"), Rule("さ", "い"),
        Rule("ければ", "い"), Rule("そう", "い"),
    )

    fun deinflect(word: String): List<String> {
        val out = LinkedHashSet<String>()
        out += word
        var frontier = listOf(word)
        repeat(2) {
            val next = mutableListOf<String>()
            for (w in frontier) for (r in rules) {
                if (w.length > r.from.length && w.endsWith(r.from)) {
                    val cand = w.dropLast(r.from.length) + r.to
                    if (out.add(cand)) next += cand
                }
            }
            frontier = next
        }
        return out.toList()
    }

    /** Prefixes of [text] from longest to shortest, used for longest-match lookup at a tap position. */
    fun prefixes(text: String, max: Int = 12): List<String> = (minOf(max, text.length) downTo 1).map { text.take(it) }
}
