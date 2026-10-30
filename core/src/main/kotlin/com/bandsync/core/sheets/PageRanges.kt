package com.bandsync.core.sheets

/** The "1-3, 5" text a person types, as one-based page numbers, to and from zero-based page indexes. */
object PageRanges {
    /** Blank means the whole file and returns an empty list. Returns null when [text] is not valid. */
    fun parse(text: String, pageCount: Int): List<Int>? {
        if (text.isBlank()) return emptyList()
        val pages = mutableListOf<Int>()
        for (part in text.split(',')) {
            val bounds = part.trim().split('-').map { it.trim().toIntOrNull() ?: return null }
            val (first, last) = when (bounds.size) {
                1 -> bounds[0] to bounds[0]
                2 -> bounds[0] to bounds[1]
                else -> return null
            }
            if (first < 1 || last > pageCount || first > last) return null
            pages += (first - 1)..(last - 1)
        }
        return pages
    }

    fun format(pages: List<Int>): String {
        if (pages.isEmpty()) return ""
        val ranges = mutableListOf<IntRange>()
        for (page in pages) {
            val current = ranges.lastOrNull()
            if (current != null && page == current.last + 1) {
                ranges[ranges.lastIndex] = current.first..page
            } else {
                ranges += page..page
            }
        }
        return ranges.joinToString(", ") { range ->
            if (range.first == range.last) "${range.first + 1}" else "${range.first + 1}-${range.last + 1}"
        }
    }
}
