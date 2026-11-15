package com.cutedog.app.data

/** A durable reminder outbox entry, also used by the missed-reminders dashboard. */
data class DeliveryRecord(
    val reminderId: Long,
    val token: String,
    val title: String,
    val dueAt: Long,
    val status: String,
    val reason: String,
    val attempts: Int,
    val lastAttemptAt: Long,
    val createdAt: Long,
    val details: String = "",
    val priority: Int = 1,
    val isSnooze: Boolean = false,
)
