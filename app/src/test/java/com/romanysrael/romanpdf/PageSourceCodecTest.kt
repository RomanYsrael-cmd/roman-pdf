package com.romanysrael.romanpdf

import com.romanysrael.romanpdf.data.DocumentEntity
import com.romanysrael.romanpdf.data.DocumentKinds
import com.romanysrael.romanpdf.data.PageSourceCodec
import com.romanysrael.romanpdf.data.pageSourceList
import org.junit.Assert.assertEquals
import org.junit.Test

class PageSourceCodecTest {
    @Test
    fun multipleImageSourcesKeepPickerOrderAndContentUriCharacters() {
        val sources = listOf(
            "content://picker/Page 1;notes.jpg?token=a,b",
            "content://picker/Page 2.jpg"
        )
        val document = DocumentEntity(
            title = "Pages",
            uri = "romanpdf:image-document:test",
            mimeType = "image/*",
            kind = DocumentKinds.IMAGE,
            pageCount = sources.size,
            pageSources = PageSourceCodec.encode(sources)
        )

        assertEquals(sources, document.pageSourceList().map { it.uri })
    }

    @Test
    fun legacySingleImageFallsBackToDocumentUri() {
        val document = DocumentEntity(
            title = "Legacy",
            uri = "content://picker/legacy.jpg",
            mimeType = "image/jpeg",
            kind = DocumentKinds.IMAGE
        )

        assertEquals(listOf(document.uri), document.pageSourceList().map { it.uri })
    }
}
