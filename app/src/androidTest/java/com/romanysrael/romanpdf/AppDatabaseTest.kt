package com.romanysrael.romanpdf

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.romanysrael.romanpdf.data.AppDatabase
import com.romanysrael.romanpdf.data.DocumentEntity
import com.romanysrael.romanpdf.data.SearchEntryFts
import com.romanysrael.romanpdf.data.SearchSources
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun documentAndFtsEntryPersistAndSearch() = runBlocking {
        val documentId = database.documentDao().insert(
            DocumentEntity(title = "Offline guide", uri = "content://test/offline.pdf", mimeType = "application/pdf", kind = "PDF")
        )
        database.searchDao().insert(
            SearchEntryFts(documentId.toString(), "2", SearchSources.PDF_TEXT, "offline first search")
        )

        val hits = database.searchDao().search("offline*")
        assertEquals(1, hits.size)
        assertEquals(documentId.toString(), hits.single().documentId)
        assertTrue(hits.single().content.contains("offline"))
    }
}
