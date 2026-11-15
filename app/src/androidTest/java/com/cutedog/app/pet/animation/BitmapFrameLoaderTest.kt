package com.cutedog.app.pet.animation

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue

/** Generated colored PNGs are explicit test buffers, not character resources or screenshots. */
@RunWith(AndroidJUnit4::class)
class BitmapFrameLoaderTest {
    private fun png(color: Int, width: Int = 64): ByteArray {
        val bitmap = Bitmap.createBitmap(width, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle(); output.toByteArray() }
    }
    private fun waitUntil(condition: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (!condition() && System.nanoTime() < end) Thread.sleep(10)
        assertTrue("Background decode did not complete", condition())
    }

    @Test fun decodingIsOffMainThreadCacheIsBoundedAndPinnedFrameIsNotReused() {
        val bytes = (0..15).associate { "fixture-$it" to png(Color.rgb(it * 15, 20, 30)) }
        val threads = ConcurrentLinkedQueue<Thread>()
        val budget = 64L * 64 * 4 * 6
        BitmapFrameLoader(FrameAssetSource { path -> threads += Thread.currentThread(); ByteArrayInputStream(bytes.getValue(path)) }, 64, 64, budget).use { loader ->
            loader.pin(setOf("fixture-0")); loader.prefetch(listOf("fixture-0"))
            waitUntil { loader.ready("fixture-0") != null }
            val current = loader.ready("fixture-0")!!
            val expected = current.getPixel(0, 0)
            loader.prefetch((1..15).map { "fixture-$it" })
            waitUntil { loader.stats().decodedFrames >= 16 }
            assertEquals(expected, current.getPixel(0, 0))
            assertFalse(current.isRecycled)
            assertTrue(loader.stats().allocatedBytes <= budget)
            assertTrue(loader.stats().cachedFrames <= 6)
            assertTrue(loader.stats().reusedBuffers >= 10)
            assertTrue(threads.all { it != Looper.getMainLooper().thread && it.name == "CuteDog-frame-decode" })
        }
    }

    @Test fun invalidOrMissingFrameReportsFailureWithoutInventingPixels() {
        val wrongSize = png(Color.RED, width = 32)
        BitmapFrameLoader(FrameAssetSource { path ->
            if (path == "missing") throw java.io.FileNotFoundException()
            ByteArrayInputStream(wrongSize)
        }, 64, 64, 64L * 64 * 4 * 4).use { loader ->
            loader.prefetch(listOf("missing", "wrong-dimensions"))
            waitUntil { loader.error("missing") != null && loader.error("wrong-dimensions") != null }
            assertNull(loader.ready("missing")); assertNull(loader.ready("wrong-dimensions"))
            assertEquals(0, loader.stats().cachedFrames)
            assertEquals(0L, loader.stats().allocatedBytes)
        }
    }
}
