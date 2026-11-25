package com.cutedog.app.reminder

import org.junit.Assert.*
import org.junit.Test

class DogReminderQueueTest {
    private val queue = DogReminderQueue()
    private val first = DogReminder(7, 100, "due:100", "今天")
    private val next = DogReminder(7, 200, "due:200", "下次")

    @Test fun onlyVisibleOverlayShowsRemindersAndLockClearsTheQueue() {
        queue.offer(first)
        queue.setVisible(true)
        assertNull(queue.display.value)
        queue.offer(first)
        assertEquals(first, queue.display.value)
        queue.setVisible(true)
        assertEquals(first, queue.display.value)
        queue.setVisible(false)
        queue.setVisible(true)
        assertNull(queue.display.value)
    }

    @Test fun repeatedOffersAndOldTimersNeverConsumeNextOccurrence() {
        queue.setVisible(true)
        queue.offer(first)
        val old = queue.display.value!!
        queue.offer(first)
        queue.offer(next)
        queue.remove(first.id, first.occurrence)
        queue.timeout(old)
        queue.removeToken(first.id, first.token)
        assertEquals(next, queue.display.value)
        queue.timeout(queue.display.value!!)
        assertNull(queue.display.value)
    }

    @Test fun snoozeAcknowledgementDoesNotClearADifferentDeliveryWithSameDueDate() {
        val snooze = first.copy(token = "snooze:91")
        queue.setVisible(true)
        queue.offer(snooze)
        queue.offer(first)
        queue.removeToken(snooze.id, snooze.token)
        assertEquals(first, queue.display.value)
        queue.remove(first.id)
        assertNull(queue.display.value)
    }
}
