package com.romanysrael.romanpdf

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.romanysrael.romanpdf.ui.PageBitmapCache
import com.romanysrael.romanpdf.ui.PageBitmapKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageBitmapCacheTest {
    @Test
    fun pinnedBitmapIsNotRecycledWhenEvictedAndRecycledAfterLeaseRelease() {
        val cache = PageBitmapCache(maxEntries = 1)
        val firstKey = PageBitmapKey(0, 100, 100)
        val secondKey = PageBitmapKey(1, 100, 100)
        val first = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

        try {
            cache.put(firstKey, first)
            val lease = cache.acquire(firstKey)
            assertNotNull(lease)
            cache.put(secondKey, second)

            assertFalse(cache.contains(firstKey))
            assertTrue(first.isRecycled.not())
            assertEquals(1, cache.entryCount())

            cache.release(lease!!)
            cache.release(lease)
            assertTrue(first.isRecycled)
        } finally {
            cache.clear()
            if (!first.isRecycled) first.recycle()
            if (!second.isRecycled) second.recycle()
        }
    }
}
