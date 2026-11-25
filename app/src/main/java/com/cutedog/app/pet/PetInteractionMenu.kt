package com.cutedog.app.pet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** All calls are on the main thread. A detached or replaced floating dog invalidates its open menu. */
internal object PetInteractionMenu {
    private var generation = 0L
    private val current = MutableStateFlow<Long?>(null)
    val session = current.asStateFlow()
    private var onSelection: ((String) -> Unit)? = null

    fun open(select: (String) -> Unit): Long {
        onSelection = select
        return (++generation).also { current.value = it }
    }

    fun close(id: Long? = current.value) {
        if (id != current.value) return
        onSelection = null
        current.value = null
    }

    fun select(id: Long, intent: String) {
        if (id != current.value) return
        val select = onSelection
        close(id)
        select?.invoke(intent)
    }

    const val CHAT = "open_chat"
    data class Action(val intent: String, val label: String)

    val actions = listOf(
        Action("ball", "投球"), Action("treat", "喂食"), Action("pet", "摸摸"),
        Action(CHAT, "聊聊天"), Action("call", "呼唤"), Action("sleep", "陪我休息"), Action("cuddle", "抱抱"),
        Action("wave", "打招呼"), Action("jump", "蹦一下"), Action("follow", "跟随"),
        Action("nose", "碰鼻子"), Action("body", "揉肚子"), Action("seek", "找一找"),
        Action("trot", "慢跑"), Action("request_pet", "撒个娇"), Action("happy", "逗它开心"),
        Action("calm", "安抚"), Action("curious", "引起好奇"), Action("excited", "一起兴奋"),
        Action("sleepy", "打个盹"), Action("shy", "害羞"), Action("startled", "吃一惊"),
    )
}
