package com.stroexd.hsdecktracker.core.vision

import kotlin.math.abs
import kotlin.math.max

/** A card name on screen; [dbfIds] are all printings sharing the name. */
data class CardTile(val line: OcrLine, val dbfIds: List<Int>, val name: String, val copies: Int)

/**
 * Finds card names and their "x2" badges (the game's "x{0}"): a badge belongs to the nearest card above it in
 * the same column, or to the card left of it on the same row. [grid] skips lists (a deck) and card texts.
 */
internal fun readCardTiles(index: CardNameIndex, lines: List<OcrLine>, grid: Boolean = true, skip: (OcrLine) -> Boolean = { false }): List<CardTile> {
    val found = lines.mapNotNull { line ->
        if (skip(line) || line.text.trimEnd().endsWith('.')) null else index.match(line.text)?.let { line to it }
    }
    val cards = if (!grid) {
        found
    } else {
        // More names in one column than a page has rows: a list, not the card grid
        found.filter { (line, _) -> found.count { sameColumn(it.first, line) } <= MAX_ROWS && !line.continuesText(lines) }
    }
    val copies = IntArray(cards.size) { 1 }
    for (line in lines) {
        val count = CollectionScanner.copiesBadge(line.text) ?: continue
        val below = cards.indices
            .filter { i -> sameColumn(cards[i].first, line) && line.top - cards[i].first.bottom in 0f..badgeGap(cards[i].first) }
            .minByOrNull { i -> line.top - cards[i].first.bottom }
        val beside = cards.indices
            .filter { i -> abs(cards[i].first.centerY - line.centerY) < cards[i].first.height && line.left - cards[i].first.right in 0f..MAX_BADGE_GAP }
            .minByOrNull { i -> line.left - cards[i].first.right }
        val owner = below ?: beside ?: continue
        copies[owner] = count
    }
    return cards.mapIndexed { i, (line, match) -> CardTile(line, match.dbfIds.sorted(), match.key, copies[i]) }
}

private const val MAX_ROWS = 3
private const val COLUMN_TOLERANCE = 0.05f
private const val MAX_BADGE_GAP = 0.3f

private fun sameColumn(a: OcrLine, b: OcrLine): Boolean = abs(a.centerX - b.centerX) < COLUMN_TOLERANCE

/** Enlarged cards (crafting) have their badge further below the name. */
private fun badgeGap(name: OcrLine): Float = max(MAX_BADGE_GAP, 8 * name.height)

/**
 * Reads Hearthstone's own collection pages. A read only counts once two frames in a row agree,
 * so page turn animations add nothing; a page read again merges instead of counting twice.
 */
class CollectionScanner(private val index: CardNameIndex, private val grid: Boolean = true) {
    private val pages = mutableListOf<MutableMap<List<Int>, Int>>()
    private var previous: Map<List<Int>, Int>? = null

    var decisionLog: ((String) -> Unit)? = null

    var lastPage: List<CardTile> = emptyList()
        private set

    val pageCount: Int get() = pages.size

    /** Copies per card name; normal and golden copies are separate tiles and add up. */
    fun totals(): Map<List<Int>, Int> {
        val result = HashMap<List<Int>, Int>()
        pages.forEach { page -> page.forEach { (ids, copies) -> result.merge(ids, copies, Int::plus) } }
        return result
    }

    /** For views that list every card once, like the summary after opening many packs. */
    fun maxima(): Map<List<Int>, Int> {
        val result = HashMap<List<Int>, Int>()
        pages.forEach { page -> page.forEach { (ids, copies) -> result.merge(ids, copies, ::maxOf) } }
        return result
    }

    /** Returns true when the frame added cards or copies. */
    fun onFrame(frame: OcrFrame): Boolean {
        val tiles = readCardTiles(index, frame.lines, grid)
        val read = HashMap<List<Int>, Int>()
        tiles.forEach { read.merge(it.dbfIds, it.copies, Int::plus) }
        val stable = read == previous
        previous = read
        if (!stable || read.isEmpty()) return false
        lastPage = tiles
        val page = pages.firstOrNull { overlaps(it.keys, read.keys) }
        if (page == null) {
            pages += read.toMutableMap()
            decisionLog?.invoke("Page ${pages.size}: " + tiles.joinToString { "${it.name} ×${it.copies}" })
            return true
        }
        var changed = false
        for ((ids, copies) in read) {
            if (copies > (page[ids] ?: 0)) {
                page[ids] = copies
                changed = true
            }
        }
        return changed
    }

    private fun overlaps(a: Set<List<Int>>, b: Set<List<Int>>): Boolean {
        val common = a.count { it in b }
        return common > 0 && common * 2 >= minOf(a.size, b.size)
    }

    companion object {
        private val badge = Regex("^[x×✕*]\\s?(\\d{1,2})\\+?$|^(\\d{1,2})\\s?[x×✕]$", RegexOption.IGNORE_CASE)

        fun copiesBadge(text: String): Int? {
            val match = badge.matchEntire(text.trim()) ?: return null
            return match.groupValues[1].ifEmpty { match.groupValues[2] }.toIntOrNull()?.takeIf { it > 0 }
        }
    }
}
