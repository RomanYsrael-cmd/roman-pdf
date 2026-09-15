package com.romanysrael.romanpdf.ui

import com.romanysrael.romanpdf.data.DocumentEntity
import java.util.Locale

enum class LibrarySort {
    NAME_ASC,
    NAME_DESC,
    RECENTLY_OPENED,
    OLDEST_OPENED,
    RECENTLY_IMPORTED,
    OLDEST_IMPORTED,
    PAGE_COUNT_ASC,
    PAGE_COUNT_DESC;

    companion object {
        fun fromStored(value: String?): LibrarySort = values().firstOrNull {
            it.name.equals(value, ignoreCase = true)
        } ?: RECENTLY_OPENED
    }
}

object LibrarySortPolicy {
    const val PREFERENCE_KEY = "library_sort"

    fun sort(documents: List<DocumentEntity>, sort: LibrarySort): List<DocumentEntity> = when (sort) {
        LibrarySort.NAME_ASC -> documents.sortedWith(
            compareBy<DocumentEntity> { it.title.lowercase(Locale.ROOT) }.thenBy { it.id }
        )
        LibrarySort.NAME_DESC -> documents.sortedWith(
            compareByDescending<DocumentEntity> { it.title.lowercase(Locale.ROOT) }.thenByDescending { it.id }
        )
        LibrarySort.RECENTLY_OPENED -> documents.sortedWith(
            compareByDescending<DocumentEntity> { it.lastOpenedAt }.thenByDescending { it.id }
        )
        LibrarySort.OLDEST_OPENED -> documents.sortedWith(
            compareBy<DocumentEntity> { it.lastOpenedAt }.thenBy { it.id }
        )
        LibrarySort.RECENTLY_IMPORTED -> documents.sortedWith(
            compareByDescending<DocumentEntity> { it.createdAt }.thenByDescending { it.id }
        )
        LibrarySort.OLDEST_IMPORTED -> documents.sortedWith(
            compareBy<DocumentEntity> { it.createdAt }.thenBy { it.id }
        )
        LibrarySort.PAGE_COUNT_ASC -> documents.sortedWith(
            compareBy<DocumentEntity> { it.pageCount }.thenBy { it.title.lowercase(Locale.ROOT) }.thenBy { it.id }
        )
        LibrarySort.PAGE_COUNT_DESC -> documents.sortedWith(
            compareByDescending<DocumentEntity> { it.pageCount }.thenBy { it.title.lowercase(Locale.ROOT) }.thenByDescending { it.id }
        )
    }
}
