package com.cutedog.app.reminder

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DogReminder(val id: Long, val occurrence: Long, val token: String, val title: String)

/** Only reminders delivered while the floating dog is visible can enter its queue. */
internal class DogReminderQueue {
    private var visible = false
    private val pending = mutableListOf<DogReminder>()
    private val state = MutableStateFlow<DogReminder?>(null)
    val display = state.asStateFlow()

    @Synchronized fun setVisible(value: Boolean) {
        visible = value
        if (!visible) pending.clear()
        publish()
    }

    @Synchronized fun offer(reminder: DogReminder) {
        if (!visible || pending.any { it.id == reminder.id && it.token == reminder.token }) return
        pending.add(reminder)
        publish()
    }

    @Synchronized fun remove(id: Long, occurrence: Long? = null) {
        pending.removeAll { it.id == id && (occurrence == null || it.occurrence == occurrence) }
        publish()
    }

    @Synchronized fun removeToken(id: Long, token: String) {
        pending.removeAll { it.id == id && it.token == token }
        publish()
    }

    @Synchronized fun timeout(displayed: DogReminder) {
        if (state.value == displayed) {
            pending.remove(displayed)
            publish()
        }
    }

    private fun publish() {
        state.value = pending.firstOrNull()
    }
}

internal val dogReminders = DogReminderQueue()
