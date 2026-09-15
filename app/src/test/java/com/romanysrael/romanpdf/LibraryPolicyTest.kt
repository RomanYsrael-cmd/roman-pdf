package com.romanysrael.romanpdf

import com.romanysrael.romanpdf.data.DocumentEntity
import com.romanysrael.romanpdf.data.DocumentKinds
import com.romanysrael.romanpdf.ui.LibrarySort
import com.romanysrael.romanpdf.ui.LibrarySortPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPolicyTest {
    private val documents = listOf(
        DocumentEntity(id = 1, title = "Bravo", uri = "content://bravo", mimeType = "application/pdf", kind = DocumentKinds.PDF, pageCount = 12, createdAt = 10L, lastOpenedAt = 30L),
        DocumentEntity(id = 2, title = "alpha", uri = "content://alpha", mimeType = "application/pdf", kind = DocumentKinds.PDF, pageCount = 2, createdAt = 30L, lastOpenedAt = 10L),
        DocumentEntity(id = 3, title = "Charlie", uri = "content://charlie", mimeType = "application/pdf", kind = DocumentKinds.PDF, pageCount = 4, createdAt = 20L, lastOpenedAt = 20L)
    )

    @Test
    fun everySortUsesTheExpectedMetadata() {
        assertEquals(listOf(2L, 1L, 3L), ids(LibrarySort.NAME_ASC))
        assertEquals(listOf(3L, 1L, 2L), ids(LibrarySort.NAME_DESC))
        assertEquals(listOf(1L, 3L, 2L), ids(LibrarySort.RECENTLY_OPENED))
        assertEquals(listOf(2L, 3L, 1L), ids(LibrarySort.OLDEST_OPENED))
        assertEquals(listOf(2L, 3L, 1L), ids(LibrarySort.RECENTLY_IMPORTED))
        assertEquals(listOf(1L, 3L, 2L), ids(LibrarySort.OLDEST_IMPORTED))
        assertEquals(listOf(2L, 3L, 1L), ids(LibrarySort.PAGE_COUNT_ASC))
        assertEquals(listOf(1L, 3L, 2L), ids(LibrarySort.PAGE_COUNT_DESC))
    }

    @Test
    fun invalidStoredSortFallsBackToRecentlyOpened() {
        assertEquals(LibrarySort.RECENTLY_OPENED, LibrarySort.fromStored("unknown"))
        assertEquals(LibrarySort.NAME_ASC, LibrarySort.fromStored("name_asc"))
    }

    private fun ids(sort: LibrarySort): List<Long> = LibrarySortPolicy.sort(documents, sort).map { it.id }
}
