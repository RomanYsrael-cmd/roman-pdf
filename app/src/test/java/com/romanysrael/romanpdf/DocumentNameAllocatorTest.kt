package com.romanysrael.romanpdf

import com.romanysrael.romanpdf.data.DocumentNameAllocator
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentNameAllocatorTest {
    @Test
    fun duplicatePdfNamesGetSuffixBeforeExtension() {
        val existing = listOf("Accounting.pdf", "Accounting (1).pdf", "Accounting (2).pdf")

        assertEquals("Accounting (3).pdf", DocumentNameAllocator.allocate("Accounting.pdf", existing))
    }

    @Test
    fun duplicateImageNamesAreCaseInsensitive() {
        assertEquals(
            "Lecture (1).jpg",
            DocumentNameAllocator.allocate("Lecture.jpg", listOf("lecture.jpg"))
        )
    }

    @Test
    fun imageDocumentTitleUsesFirstPageAndCount() {
        assertEquals("Page1 (4 pages)", DocumentNameAllocator.suggestedImageTitle("Page1.jpg", 4))
    }
}
