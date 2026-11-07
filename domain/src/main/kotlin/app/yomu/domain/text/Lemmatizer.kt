package app.yomu.domain.text

import java.text.Normalizer

/**
 * Lightweight rule-based lemmatization used to find dictionary forms of inflected words.
 * Returns candidates ordered from most to least likely; the original (normalized) word is always first.
 */
object Lemmatizer {
    fun normalize(word: String): String {
        val lower = word.trim().lowercase()
            .replace('ё', 'е')
            .replace('’', '\'')
        val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
        // Drop combining accents (stress marks, diacritics) but keep Japanese dakuten (composed back).
        val stripped = decomposed.replace(Regex("[\\u0300-\\u036f]"), "")
        return Normalizer.normalize(stripped, Normalizer.Form.NFC).trim { !it.isLetterOrDigit() }
    }

    fun candidates(word: String, lang: String?): List<String> {
        val w = normalize(word)
        if (w.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        out += w
        when (lang?.take(2) ?: guessLang(w)) {
            "en" -> english(w, out)
            "ru", "uk", "be" -> russian(w, out)
            "ja" -> JapaneseDeinflector.deinflect(w).forEach { out += it }
            else -> generic(w, out)
        }
        return out.filter { it.length >= 1 }
    }

    fun guessLang(w: String): String = when {
        w.any { it in 'а'..'я' } -> "ru"
        w.any { Character.UnicodeBlock.of(it).let { b -> b == Character.UnicodeBlock.HIRAGANA || b == Character.UnicodeBlock.KATAKANA } } -> "ja"
        w.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } -> "zh"
        else -> "en"
    }

    private val englishIrregular = mapOf(
        "went" to "go", "gone" to "go", "was" to "be", "were" to "be", "been" to "be", "is" to "be", "are" to "be", "am" to "be",
        "had" to "have", "has" to "have", "did" to "do", "done" to "do", "does" to "do", "said" to "say", "made" to "make",
        "took" to "take", "taken" to "take", "came" to "come", "saw" to "see", "seen" to "see", "knew" to "know", "known" to "know",
        "got" to "get", "gotten" to "get", "gave" to "give", "given" to "give", "found" to "find", "thought" to "think",
        "told" to "tell", "became" to "become", "left" to "leave", "felt" to "feel", "brought" to "bring", "began" to "begin",
        "begun" to "begin", "kept" to "keep", "held" to "hold", "wrote" to "write", "written" to "write", "stood" to "stand",
        "heard" to "hear", "meant" to "mean", "met" to "meet", "ran" to "run", "paid" to "pay", "sat" to "sit", "spoke" to "speak",
        "spoken" to "speak", "lay" to "lie", "led" to "lead", "read" to "read", "grew" to "grow", "grown" to "grow", "lost" to "lose",
        "fell" to "fall", "fallen" to "fall", "sent" to "send", "built" to "build", "understood" to "understand", "drew" to "draw",
        "drawn" to "draw", "broke" to "break", "broken" to "break", "spent" to "spend", "rose" to "rise", "risen" to "rise",
        "drove" to "drive", "driven" to "drive", "bought" to "buy", "wore" to "wear", "worn" to "wear", "chose" to "choose",
        "chosen" to "choose", "ate" to "eat", "eaten" to "eat", "flew" to "fly", "flown" to "fly", "forgot" to "forget",
        "forgotten" to "forget", "sang" to "sing", "sung" to "sing", "swam" to "swim", "threw" to "throw", "thrown" to "throw",
        "men" to "man", "women" to "woman", "children" to "child", "feet" to "foot", "teeth" to "tooth", "mice" to "mouse",
        "people" to "person", "better" to "good", "best" to "good", "worse" to "bad", "worst" to "bad", "data" to "datum",
    )

    private fun english(w: String, out: MutableSet<String>) {
        englishIrregular[w]?.let { out += it }
        val stem = w.removeSuffix("'s").removeSuffix("'")
        out += stem
        fun add(s: String) { if (s.length >= 2) out += s }
        when {
            stem.endsWith("ies") -> add(stem.dropLast(3) + "y")
            stem.endsWith("ves") -> { add(stem.dropLast(3) + "f"); add(stem.dropLast(3) + "fe") }
            stem.endsWith("ses") || stem.endsWith("xes") || stem.endsWith("zes") || stem.endsWith("ches") || stem.endsWith("shes") -> add(stem.dropLast(2))
            stem.endsWith("s") && !stem.endsWith("ss") -> add(stem.dropLast(1))
        }
        if (stem.endsWith("ied")) add(stem.dropLast(3) + "y")
        if (stem.endsWith("ed")) {
            val base = stem.dropLast(2)
            add(base); add(base + "e")
            if (base.length > 2 && base.last() == base[base.length - 2]) add(base.dropLast(1))
        }
        if (stem.endsWith("ing")) {
            val base = stem.dropLast(3)
            add(base); add(base + "e")
            if (base.length > 2 && base.last() == base[base.length - 2]) add(base.dropLast(1))
            if (base.endsWith("y")) add(base.dropLast(1) + "ie")
        }
        if (stem.endsWith("ier")) add(stem.dropLast(3) + "y")
        if (stem.endsWith("iest")) add(stem.dropLast(4) + "y")
        if (stem.endsWith("er")) { add(stem.dropLast(2)); add(stem.dropLast(1)) }
        if (stem.endsWith("est")) { add(stem.dropLast(3)); add(stem.dropLast(2)) }
        if (stem.endsWith("ly")) add(stem.dropLast(2))
    }

    // Longest endings first. Covers noun, adjective, participle and verb inflection of Russian.
    private val ruEndings = listOf(
        "ующимися", "ающимися", "ившимися", "ующийся", "ающийся",
        "иями", "ями", "ами", "иях", "ях", "ах", "ого", "его", "ому", "ему", "ыми", "ими", "ешь", "ишь", "ете", "ите", "ует", "уют",
        "ают", "яют", "ует", "ется", "ится", "утся", "ются", "ался", "ался", "илась", "алась", "ались", "ились", "лась", "лись",
        "ов", "ев", "ей", "ой", "ий", "ый", "ая", "яя", "ое", "ее", "ые", "ие", "ую", "юю", "ым", "им", "ом", "ем", "ам", "ям",
        "ет", "ит", "ут", "ют", "ат", "ят", "ла", "ли", "ло", "ал", "ял", "ил", "ть", "ти", "чь", "сь", "ся",
        "а", "я", "о", "е", "ы", "и", "у", "ю", "ь", "й",
    )

    private fun russian(w: String, out: MutableSet<String>) {
        val word = w.removeSuffix("ся").removeSuffix("сь").let { if (it.length < 2) w else it }
        if (word != w) {
            out += word + "ся"
            out += word
        }
        for (end in ruEndings) {
            if (word.length - end.length >= 2 && word.endsWith(end)) {
                val stem = word.dropLast(end.length)
                out += stem
                for (suffix in listOf("", "а", "я", "о", "ь", "ть", "ий", "ый", "ой", "ать", "ять", "ить", "еть", "уть", "ться")) {
                    out += stem + suffix
                }
                if (out.size > 40) break
            }
        }
    }

    private fun generic(w: String, out: MutableSet<String>) {
        for (cut in 1..3) if (w.length - cut >= 3) out += w.dropLast(cut)
    }
}
