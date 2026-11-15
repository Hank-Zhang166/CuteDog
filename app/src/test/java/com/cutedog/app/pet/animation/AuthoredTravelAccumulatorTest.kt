package com.cutedog.app.pet.animation

import org.junit.Assert.*
import org.junit.Test

class AuthoredTravelAccumulatorTest {
    @Test fun thirtyTwoHzHostAndTwentyHzWindowConsumeEveryAuthoredFrameExactlyOnce() {
        val pending = AuthoredTravelAccumulator()
        val cadence = FrameCadence()
        var expected = 0.0
        var consumed = 0.0
        var frame = 0
        // Separate host and consumer clocks: 24 authored frames, ~32Hz tick and 20Hz windows.
        for (ms in 0L..3100L) {
            if (ms > 0 && ms % 31L == 0L) {
                cadence.add(.031, 24)
                val delta = if (cadence.due(24)) {
                    cadence.consumed(24); frame++
                    (.001 + frame % 4 * .0002) * -1
                } else 0.0
                expected += delta * 200.0 * .9
                pending.record(delta, 200.0 * .9, -1, true, ms)
            }
            if (ms % 50L == 0L) {
                consumed += pending.consume(-1, true, ms)
                assertEquals(0f, pending.consume(-1, true, ms), 0f)
            }
        }
        assertTrue(frame > 70)
        assertEquals(expected, consumed, 1e-5)
    }

    @Test fun aTickWithoutANewFrameKeepsEarlierUnconsumedTravel() {
        val pending = AuthoredTravelAccumulator()
        pending.record(.01, 100.0, 1, true, 0)
        pending.record(0.0, 100.0, 1, true, 31)
        assertEquals(1f, pending.consume(1, true, 50), 0f)
    }

    @Test fun loadingOrPauseDiscardsTravelInsteadOfReplayingItOnResume() {
        val pending = AuthoredTravelAccumulator()
        pending.record(.01, 100.0, 1, true, 0)
        pending.record(0.0, 100.0, 1, false, 31)
        assertEquals(0f, pending.consume(1, true, 50), 0f)
        pending.record(.02, 100.0, 1, true, 62)
        assertEquals(0f, pending.consume(1, false, 70), 0f)
        assertEquals(0f, pending.consume(1, true, 80), 0f)
    }

    @Test fun directionChangeCannotMixOpposingStepsOrReuseThePreviousHeading() {
        val pending = AuthoredTravelAccumulator()
        pending.record(.01, 100.0, 1, true, 0)
        pending.record(-.02, 100.0, -1, true, 31)
        assertEquals(-2f, pending.consume(-1, true, 50), 0f)
        pending.record(-.02, 100.0, -1, true, 62)
        assertEquals(0f, pending.consume(1, true, 70), 0f)
        assertEquals(0f, pending.consume(-1, true, 80), 0f)
    }

    @Test fun staleWindowBacklogIsDroppedAndFreshFramesStartANewBudget() {
        val pending = AuthoredTravelAccumulator()
        pending.record(.01, 100.0, 1, true, 0)
        assertEquals(0f, pending.consume(1, true, 1000), 0f)
        pending.record(.01, 100.0, 1, true, 1100)
        pending.record(.02, 100.0, 1, true, 1500)
        assertEquals(2f, pending.consume(1, true, 1510), 0f)
    }

    @Test fun invalidFractionsScaleOrHeadingCannotCreateWindowMotion() {
        val invalid = listOf(
            Triple(Double.NaN, 100.0, 1), Triple(Double.POSITIVE_INFINITY, 100.0, 1),
            Triple(.01, Double.MAX_VALUE, 1), Triple(.01, 0.0, 1), Triple(2.0, 100.0, 1),
            Triple(-.01, 100.0, 1), Triple(.01, 100.0, 0),
        )
        for ((fraction, width, sign) in invalid) {
            val pending = AuthoredTravelAccumulator()
            pending.record(.01, 100.0, 1, true, 0)
            pending.record(fraction, width, sign, true, 31)
            assertEquals(0f, pending.consume(1, true, 50), 0f)
        }
    }
}
