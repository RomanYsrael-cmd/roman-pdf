package com.romanysrael.romanpdf

import com.romanysrael.romanpdf.data.SearchQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {
    @Test
    fun userTextBecomesPrefixAndTermsQuery() {
        assertEquals("roman* AND pdf*", SearchQuery.toMatchQuery("roman pdf"))
    }

    @Test
    fun punctuationCannotInjectAnFtsOperator() {
        val query = SearchQuery.toMatchQuery("hello OR *")
        assertTrue(query == "hello* AND OR*")
        assertTrue(!query.contains('"'))
    }
}
