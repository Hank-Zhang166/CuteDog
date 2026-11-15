package com.cutedog.app.data

import java.time.ZoneId

data class Reminder(
    val id: Long = 0,
    val title: String,
    val details: String = "",
    val dueAt: Long,
    val leadMinutes: Int = 0,
    val repeat: String = "none",
    val priority: Int = 1,
    val category: String = "生活",
    val completed: Boolean = false,
    val snoozedUntil: Long = 0,
    val timeZoneId: String = ZoneId.systemDefault().id,
    val anchorDay: Int = 0,
) {
    fun validated(): Reminder {
        require(title.isNotBlank() && title.length <= 200) { "标题须为 1–200 个字符" }
        require(details.length <= 10000) { "详情最多 10000 个字符" }
        require(dueAt > 0) { "请选择有效的提醒时间" }
        require(leadMinutes in 0..10080) { "提前量须在 0–10080 分钟之间" }
        require(repeat in setOf("none", "daily", "weekly", "monthly")) { "不支持的重复规则" }
        require(priority in 1..3) { "优先级须在 1–3 之间" }
        require(category.isNotBlank() && category.length <= 60) { "分类须为 1–60 个字符" }
        require(snoozedUntil >= 0) { "稍后提醒时间无效" }
        require(anchorDay in 0..31) { "月度日期无效" }
        ZoneId.of(timeZoneId)
        return copy(title = title.trim(), category = category.trim())
    }
}
