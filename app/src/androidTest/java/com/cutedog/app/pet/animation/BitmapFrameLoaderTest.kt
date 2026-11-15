package com.cutedog.app.pet.animation

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
            for (start in listOf(1, 5, 9, 13)) {
                val end = minOf(start + 3, 15)
                loader.prefetch((start..end).map { "fixture-$it" })
                waitUntil { loader.ready("fixture-$end") != null }
            }
            waitUntil { loader.stats().decodedFrames >= 16 && !loader.stats().activeDecoder }
            assertEquals(expected, current.getPixel(0, 0))
            assertFalse(current.isRecycled)
            assertTrue(loader.stats().allocatedBytes <= budget)
            assertTrue(loader.stats().cachedFrames <= 6)
            assertTrue(loader.stats().reusedBuffers >= 10)
            assertTrue(threads.all { it != Looper.getMainLooper().thread && it.name == "CuteDog-frame-decode" })
            logStats("reuse-budget", loader)
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
            logStats("invalid-frame", loader)
        }
    }

    @Test fun verifiedFramesAndPhaseDependenciesAreCheckedOffThreadBeforeBecomingReady() {
        val bytes = png(Color.GREEN)
        val actual = ClipIntegrity.sha256(bytes)
        val opened = ConcurrentLinkedQueue<Pair<String, Thread>>()
        BitmapFrameLoader(FrameAssetSource { path ->
            opened += path to Thread.currentThread()
            ByteArrayInputStream(bytes)
        }, 64, 64, 64L * 64 * 4 * 4,
            frameDigests = mapOf("good" to actual, "corrupt" to "0".repeat(64), "bridge" to actual),
            verificationDependencies = mapOf("bridge" to listOf(AssetDigest("wrong-source-anchor", "1".repeat(64)))))
            .use { loader ->
                loader.pin(setOf("good")); loader.prefetch(listOf("good", "corrupt", "bridge"))
                waitUntil { loader.isReady("good") && loader.error("corrupt") != null && loader.error("bridge") != null && !loader.stats().activeDecoder }
                assertEquals(Color.GREEN, loader.ready("good")!!.getPixel(0, 0))
                assertFalse(loader.isReady("corrupt")); assertFalse(loader.isReady("bridge"))
                assertEquals(1L, loader.stats().decodedFrames)
                assertEquals(64L * 64 * 4, loader.stats().allocatedBytes)
                assertTrue(opened.any { it.first == "wrong-source-anchor" })
                assertTrue(opened.all { it.second.name == "CuteDog-frame-decode" && it.second != Looper.getMainLooper().thread })
                logStats("phase-digest-dependency", loader)
            }
    }

    @Test fun memoryPressureDropsUnusedBuffersButNeverRecyclesDisplayedPixels() {
        val bytes = (0..15).associate { "fixture-$it" to png(Color.rgb(it * 15, 20, 30)) }
        val frameBytes = 64L * 64 * 4
        BitmapFrameLoader(FrameAssetSource { ByteArrayInputStream(bytes.getValue(it)) }, 64, 64, frameBytes * 8).use { loader ->
            loader.pin(setOf("fixture-0")); loader.prefetch((0..7).map { "fixture-$it" })
            waitUntil { loader.stats().decodedFrames == 8L && !loader.stats().activeDecoder }
            val displayed = loader.readyAndPin("fixture-0", setOf("fixture-0"))!!
            val formerDisplay = loader.ready("fixture-1")!!
            val originalPixel = displayed.getPixel(0, 0)
            val oldPixel = formerDisplay.getPixel(0, 0)
            val before = loader.stats()
            loader.trimMemory()
            val trimmed = loader.stats()
            assertEquals(1, trimmed.cachedFrames)
            assertEquals(frameBytes, trimmed.allocatedBytes)
            assertEquals(frameBytes * 6, trimmed.workingBudgetBytes)
            assertEquals(before.allocatedBytes - frameBytes, trimmed.trimmedBytes)
            assertEquals(1L, trimmed.trimCount)
            assertFalse(displayed.isRecycled); assertFalse(formerDisplay.isRecycled)
            assertEquals(originalPixel, displayed.getPixel(0, 0)); assertEquals(oldPixel, formerDisplay.getPixel(0, 0))
            logStats("trim-held-display-references", loader)
            loader.prefetch(listOf("fixture-0") + (8..15).map { "fixture-$it" })
            waitUntil { loader.ready("fixture-12") != null && !loader.stats().activeDecoder }
            assertTrue(loader.stats().allocatedBytes <= frameBytes * 6)
            assertEquals(originalPixel, displayed.getPixel(0, 0))
            assertFalse(displayed.isRecycled)
            logStats("pressure-working-budget", loader)
            loader.restoreMemoryBudget()
            assertEquals(frameBytes * 8, loader.stats().workingBudgetBytes)
        }
    }

    @Test fun cancellingPrefetchLetsOneDecodeFinishWithoutRefillingCache() {
        val data = png(Color.BLUE)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        BitmapFrameLoader(FrameAssetSource { path ->
            if (path == "slow" && first.compareAndSet(true, false)) {
                entered.countDown()
                if (!release.await(5, TimeUnit.SECONDS)) throw java.io.IOException("Fixture gate timed out")
            }
            ByteArrayInputStream(data)
        }, 64, 64, 64L * 64 * 4 * 6).use { loader ->
            try {
                loader.pin(setOf("current")); loader.prefetch(listOf("current"))
                waitUntil { loader.ready("current") != null }
                val current = loader.ready("current")!!
                loader.prefetch(listOf("slow", "unused"))
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                assertTrue(loader.stats().activeDecoder)
                loader.cancelPrefetch()
                release.countDown()
                waitUntil { !loader.stats().activeDecoder && loader.stats().pendingFrames == 0 }
                assertNull(loader.ready("slow")); assertNull(loader.ready("unused"))
                assertEquals(1, loader.stats().cachedFrames)
                assertEquals(1L, loader.stats().discardedFrames)
                assertFalse(current.isRecycled); assertEquals(Color.BLUE, current.getPixel(0, 0))
                logStats("cancelled-no-refill", loader)
            } finally { release.countDown() }
        }
    }

    private fun logStats(phase: String, loader: BitmapFrameLoader) {
        Log.i("CuteDogFrameCache", "fixture64x64 phase=$phase managed=${loader.stats()} processNativeHeapBytes=${Debug.getNativeHeapAllocatedSize()}")
    }
}
