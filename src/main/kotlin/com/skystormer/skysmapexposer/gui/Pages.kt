package com.skystormer.skysmapexposer.gui

/**
 * Where a list shown a page at a time is: which page, out of how many, kept within them as the
 * list changes, and the mouse wheel turning them. The player list, both share pickers and the
 * menu editor page the same way.
 */
internal class Pages {
    var page = 0
    var count = 1
        private set

    /** The [perPage] of [items] on the page shown, the page first kept within those there are now. */
    fun <T> of(items: List<T>, perPage: Int): List<T> {
        count = maxOf(1, (items.size + perPage - 1) / perPage)
        page = page.coerceIn(0, count - 1)
        return items.drop(page * perPage).take(perPage)
    }

    val hasPrevious: Boolean get() = page > 0
    val hasNext: Boolean get() = page < count - 1

    /** The wheel: down a notch for the next page, up for the one before; false if it did not turn. */
    fun scroll(scrollY: Double): Boolean {
        if (scrollY == 0.0) return false
        page += if (scrollY < 0) 1 else -1
        return true
    }
}
