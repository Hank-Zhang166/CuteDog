package com.cutedog.app.pet.animation

import org.junit.Assert.*
import org.junit.Test

class FrameScheduleGateTest {
    @Test fun synchronousPauseInvalidatesTheCurrentCallbacksTail() {
        val gate = FrameScheduleGate()
        assertTrue(gate.resume()); val callback = gate.token()
        gate.pause() // Called from the frame's synchronous window consumer.
        assertFalse(gate.isCurrent(callback)); assertFalse(gate.running)
    }
    @Test fun synchronousPauseAndResumeCannotScheduleAnOldAndANewCallback() {
        val gate = FrameScheduleGate()
        gate.resume(); val oldCallback = gate.token()
        gate.pause(); assertTrue(gate.resume()); val newCallback = gate.token()
        assertFalse(gate.isCurrent(oldCallback)); assertTrue(gate.isCurrent(newCallback))
    }
    @Test fun repeatedResumeLeavesExactlyOneCurrentDeadline() {
        val gate = FrameScheduleGate()
        assertTrue(gate.resume()); val callback = gate.token()
        repeat(10) { assertFalse(gate.resume()) }
        assertTrue(gate.isCurrent(callback))
    }
}
