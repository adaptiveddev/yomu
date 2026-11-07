package app.yomu.domain.catalog

import app.yomu.domain.model.ChapterInfo

/**
 * Reading order of online chapters, shared by the title page, the reader, "continue reading" and the downloader.
 *
 * Sources list chapters in either direction (Mihon/Tachiyomi extensions newest first, many novel and OPDS sites oldest
 * first), leave some chapters unnumbered ("Prologue", "Extra"), restart the numbering in every volume and list the same
 * chapter once per scanlation group. The order here:
 * - detects the direction of the source list from the numbers (or upload dates);
 * - sorts by chapter number when the numbers are trustworthy, placing unnumbered chapters between their neighbours
 *   in the source list instead of at the end;
 * - falls back to the source order when the numbers repeat inside one group (numbering restarted per volume);
 * - keeps releases of the same chapter by different groups next to each other ("slots"), so navigation can skip them.
 */
object ChapterOrder {
    private const val EDGE_STEP = 0.001

    /** Chapters from the first to the last one to read. */
    fun reading(chapters: List<ChapterInfo>): List<ChapterInfo> {
        if (chapters.size < 2) return chapters
        val bySource = bySource(chapters)
        if (!numbersReliable(bySource)) return bySource
        val keys = effectiveNumbers(bySource)
        // Stable sort: same-number releases keep the order of the source list.
        return bySource.indices.sortedWith(compareBy<Int> { keys[it] }.thenBy { it }).map { bySource[it] }
    }

    /** Chapters in the order of the source list, turned so that the first chapter to read comes first. */
    fun bySource(chapters: List<ChapterInfo>): List<ChapterInfo> {
        val listed = chapters.withIndex().sortedWith(compareBy<IndexedValue<ChapterInfo>> { it.value.sourceOrder }.thenBy { it.index }).map { it.value }
        return if (isNewestFirst(listed)) listed.asReversed().toList() else listed
    }

    /** Whether the source lists [listed] (in source order) newest first. Ties fall back to upload dates, then to "newest first". */
    fun isNewestFirst(listed: List<ChapterInfo>): Boolean {
        var down = 0
        var up = 0
        var prev = Float.NaN
        listed.forEach { c ->
            if (c.number >= 0f) {
                if (!prev.isNaN()) {
                    if (c.number < prev) down++ else if (c.number > prev) up++
                }
                prev = c.number
            }
        }
        if (down != up) return down > up
        var prevDate = 0L
        listed.forEach { c ->
            if (c.uploadDate > 0) {
                if (prevDate > 0) {
                    if (c.uploadDate < prevDate) down++ else if (c.uploadDate > prevDate) up++
                }
                prevDate = c.uploadDate
            }
        }
        return down >= up
    }

    /**
     * Numbers are trusted unless they repeat inside one group: several "Chapter 1" of the same scanlator mean that the
     * numbering restarts every volume, and sorting by number would mix the volumes. Occasional re-uploads are tolerated.
     */
    internal fun numbersReliable(reading: List<ChapterInfo>): Boolean {
        val numbered = reading.filter { it.number >= 0f }
        if (numbered.isEmpty()) return false
        val seen = HashSet<Pair<String, Float>>()
        var repeats = 0
        numbered.forEach { c -> if (!seen.add(c.scanlator.orEmpty().trim().lowercase() to c.number)) repeats++ }
        return repeats < 2 || repeats * 10 < numbered.size
    }

    /** Sort keys: the chapter number, or a value between the numbers of the neighbours in the source list. */
    internal fun effectiveNumbers(reading: List<ChapterInfo>): DoubleArray {
        val n = reading.size
        val keys = DoubleArray(n) { reading[it].number.toDouble() }
        var i = 0
        while (i < n) {
            if (reading[i].number >= 0f) { i++; continue }
            var j = i
            while (j < n && reading[j].number < 0f) j++
            // Unnumbered run [i, j): between the numbered chapters at i - 1 and j.
            val before = if (i > 0) keys[i - 1] else null
            val after = if (j < n) keys[j] else null
            val len = j - i
            for (k in 1..len) {
                val idx = i + k - 1
                keys[idx] = when {
                    before != null && after != null && after > before -> before + (after - before) * k / (len + 1)
                    before != null -> before + EDGE_STEP * k / (len + 1)
                    after != null -> after - EDGE_STEP * (len + 1 - k) / (len + 1)
                    else -> idx.toDouble()
                }
            }
            i = j
        }
        return keys
    }

    /** Same chapter: both numbered with the same number. */
    fun sameChapter(a: ChapterInfo, b: ChapterInfo): Boolean = a.number >= 0f && a.number == b.number

    /** Groups [ordered] (reading order) into slots: neighbouring releases of the same chapter by different groups. */
    fun slots(ordered: List<ChapterInfo>): List<List<ChapterInfo>> {
        val out = ArrayList<List<ChapterInfo>>()
        var current = ArrayList<ChapterInfo>()
        ordered.forEach { c ->
            if (current.isNotEmpty() && !sameChapter(current.last(), c)) {
                out += current
                current = ArrayList()
            }
            current += c
        }
        if (current.isNotEmpty()) out += current
        return out
    }

    /**
     * Number of every chapter in reading order, by chapter id: 1 for the first chapter up to the number of distinct
     * chapters. Releases of the same chapter by different groups share their number.
     */
    fun positions(chapters: List<ChapterInfo>): Map<Long, Int> {
        val out = HashMap<Long, Int>(chapters.size * 2)
        slots(reading(chapters)).forEachIndexed { i, slot -> slot.forEach { out[it.id] = i + 1 } }
        return out
    }

    /** The group the user reads: the one of the latest read chapter, otherwise the group with most chapters. */
    fun preferredScanlator(ordered: List<ChapterInfo>): String? {
        ordered.lastOrNull { (it.read || it.lastPageRead > 0) && !it.scanlator.isNullOrBlank() }?.let { return it.scanlator }
        return ordered.mapNotNull { it.scanlator?.takeIf { s -> s.isNotBlank() } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }

    /** The release to use from a slot: the preferred group (downloaded first), then any downloaded one, then the first. */
    fun pick(slot: List<ChapterInfo>, scanlator: String?): ChapterInfo =
        slot.firstOrNull { it.scanlator == scanlator && it.downloaded }
            ?: slot.firstOrNull { it.scanlator == scanlator }
            ?: slot.firstOrNull { it.downloaded }
            ?: slot.first()

    /** One release per chapter, in reading order. */
    fun distinct(ordered: List<ChapterInfo>, scanlator: String? = preferredScanlator(ordered)): List<ChapterInfo> =
        slots(ordered).map { pick(it, scanlator) }

    /**
     * Next (or previous) chapter after [currentId] in [ordered]: other releases of the current chapter are skipped and
     * the current chapter's group is preferred.
     */
    fun adjacent(ordered: List<ChapterInfo>, currentId: Long, forward: Boolean): ChapterInfo? {
        val slots = slots(ordered)
        val index = slots.indexOfFirst { s -> s.any { it.id == currentId } }
        if (index < 0) return null
        val current = slots[index].first { it.id == currentId }
        val target = slots.getOrNull(if (forward) index + 1 else index - 1) ?: return null
        return pick(target, current.scanlator ?: preferredScanlator(ordered))
    }

    /**
     * The chapter to continue with: one that was started after the last read chapter, otherwise the first unread chapter
     * after it; the last chapter when everything was read. A chapter counts as read when any of its releases was read.
     */
    fun continueWith(ordered: List<ChapterInfo>): ChapterInfo? {
        val slots = slots(ordered)
        if (slots.isEmpty()) return null
        val scanlator = preferredScanlator(ordered)
        val lastRead = slots.indexOfLast { s -> s.any { it.read } }
        slots.drop(lastRead + 1).firstNotNullOfOrNull { s -> s.firstOrNull { !it.read && it.lastPageRead > 0 } }?.let { return it }
        val next = slots.getOrNull(lastRead + 1) ?: return pick(slots.last(), scanlator)
        return pick(next, scanlator)
    }

    /** The first chapter after [chapterId] that is not read (in any release), or null. */
    fun nextUnread(ordered: List<ChapterInfo>, chapterId: Long): ChapterInfo? {
        val slots = slots(ordered)
        val index = slots.indexOfFirst { s -> s.any { it.id == chapterId } }
        if (index < 0) return null
        val scanlator = slots[index].first { it.id == chapterId }.scanlator ?: preferredScanlator(ordered)
        return slots.drop(index + 1).firstOrNull { s -> s.none { it.read } }?.let { pick(it, scanlator) }
    }

    /**
     * Up to [count] chapters to download after [afterId] (after the last read chapter when null), one release each.
     * Chapters already downloaded in any release are skipped and do not count when [skipDownloaded]; otherwise they
     * count but are not returned ("keep the next N chapters downloaded").
     */
    fun upcoming(ordered: List<ChapterInfo>, afterId: Long?, count: Int, skipDownloaded: Boolean = true): List<ChapterInfo> {
        if (count <= 0) return emptyList()
        val slots = slots(ordered)
        val start = if (afterId != null) {
            slots.indexOfFirst { s -> s.any { it.id == afterId } }.takeIf { it >= 0 } ?: return emptyList()
        } else {
            slots.indexOfLast { s -> s.any { it.read } }
        }
        val scanlator = (if (afterId != null) ordered.firstOrNull { it.id == afterId }?.scanlator else null) ?: preferredScanlator(ordered)
        val out = ArrayList<ChapterInfo>()
        var taken = 0
        for (s in slots.drop(start + 1)) {
            if (taken >= count) break
            val downloaded = s.any { it.downloaded }
            if (downloaded && skipDownloaded) continue
            taken++
            if (!downloaded) out += pick(s, scanlator)
        }
        return out
    }
}
