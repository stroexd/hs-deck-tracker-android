package com.stroexd.hsdecktracker.core.vision

import kotlin.math.abs

/**
 * Reads Hearthstone's own collection pages: card names and the "x2" badge below a card.
 * A read only counts once two frames in a row agree, so page turn animations add nothing.
 */
class CollectionScanner(private val index: CardNameIndex) {
    /** A card on a page; [dbfIds] are all printings sharing its name. */
    data class Tile(val dbfIds: List<Int>, val name: String, val copies: Int)

    private val pages = mutableListOf<MutableMap<List<Int>, Int>>()
    private var previous: Map<List<Int>, Int>? = null

    var decisionLog: ((String) -> Unit)? = null

    var lastPage: List<Tile> = emptyList()
        private set

    val pageCount: Int get() = pages.size

    /** Copies per card name; normal and golden copies are separate tiles and add up. */
    fun totals(): Map<List<Int>, Int> {
        val result = HashMap<List<Int>, Int>()
        pages.forEach { page -> page.forEach { (ids, copies) -> result.merge(ids, copies, Int::plus) } }
        return result
    }

    /** Returns true when the frame added cards or copies. */
    fun onFrame(frame: OcrFrame): Boolean {
        val tiles = readTiles(frame.lines)
        val read = HashMap<List<Int>, Int>()
        tiles.forEach { read.merge(it.dbfIds, it.copies, Int::plus) }
        val stable = read == previous
        previous = read
        if (!stable || read.isEmpty()) return false
        lastPage = tiles
        // A page read again (revisited, or one more name recognized) merges instead of counting twice
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

    private fun readTiles(lines: List<OcrLine>): List<Tile> {
        val found = lines.mapNotNull { line ->
            if (line.text.trimEnd().endsWith('.')) null else index.match(line.text)?.let { line to it }
        }
        // More names in one column than a page has rows: a list (like a deck), not the card grid
        val grid = found.filter { (line, _) ->
            found.count { sameColumn(it.first, line) } <= MAX_ROWS && !line.continuesText(lines)
        }
        val copies = IntArray(grid.size) { 1 }
        for (line in lines) {
            val count = copiesBadge(line.text) ?: continue
            val owner = grid.indices
                .filter { i -> sameColumn(grid[i].first, line) && line.top - grid[i].first.bottom in 0f..MAX_BADGE_GAP }
                .minByOrNull { i -> line.top - grid[i].first.bottom }
                ?: continue
            copies[owner] = count
        }
        return grid.mapIndexed { i, (_, match) -> Tile(match.dbfIds.sorted(), match.key, copies[i]) }
    }

    private fun sameColumn(a: OcrLine, b: OcrLine): Boolean = abs(a.centerX - b.centerX) < COLUMN_TOLERANCE

    companion object {
        private const val MAX_ROWS = 3
        private const val COLUMN_TOLERANCE = 0.05f
        private const val MAX_BADGE_GAP = 0.3f
        private val badge = Regex("^[x×✕*]\\s?(\\d{1,2})$|^(\\d{1,2})\\s?[x×✕]$", RegexOption.IGNORE_CASE)

        fun copiesBadge(text: String): Int? {
            val match = badge.matchEntire(text.trim()) ?: return null
            return match.groupValues[1].ifEmpty { match.groupValues[2] }.toIntOrNull()?.takeIf { it > 0 }
        }
    }
}
