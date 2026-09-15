package com.romanysrael.romanpdf

import com.romanysrael.romanpdf.ui.LibraryLayout
import com.romanysrael.romanpdf.ui.LibraryLayoutPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLayoutTest {
    @Test
    fun unknownPreferenceSafelyUsesList() {
        assertEquals(LibraryLayout.LIST, LibraryLayout.fromStored("unknown"))
        assertEquals(LibraryLayout.GRID, LibraryLayout.fromStored("grid"))
    }

    @Test
    fun gridSpanAdaptsToTabletWidth() {
        val portrait = LibraryLayoutPolicy.spanCount(800, 1f)
        val landscape = LibraryLayoutPolicy.spanCount(1280, 1f)

        assertTrue(landscape > portrait)
        assertTrue(portrait >= 2)
    }
}
