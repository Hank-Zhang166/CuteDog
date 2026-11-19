package com.cutedog.app.reminder

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DogReminder(val id: Long, val occurrence: Long, val token: String, val title: String)

/** The visible home dog takes precedence over the floating dog. Only a delivered task can enter the queue. */
internal class DogReminderQueue {
    enum class Host { HOME, OVERLAY }
    data class Display(val host: Host, val reminder: DogReminder)

    private val visible = mutableSetOf<Host>()
    private val pending = mutableListOf<DogReminder>()
    private val state = MutableStateFlow<Display?>(null)
    val display = state.asStateFlow()

    @Synchronized fun setVisible(host: Host, value: Boolean) {
        if (value) visible.add(host) else visible.remove(host)
        if (visible.isEmpty()) pending.clear()
        publish()
    }

    @Synchronized fun offer(reminder: DogReminder) {
        if (visible.isEmpty() || pending.any { it.id == reminder.id && it.token == reminder.token }) return
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

    @Synchronized fun timeout(displayed: Display) {
        if (state.value == displayed) {
            pending.remove(displayed.reminder)
            publish()
        }
    }

    private fun publish() {
        val host = if (Host.HOME in visible) Host.HOME else Host.OVERLAY
        state.value = pending.firstOrNull()?.let { Display(host, it) }
    }
}

internal val dogReminders = DogReminderQueue()
