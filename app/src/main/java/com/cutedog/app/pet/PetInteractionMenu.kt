package com.cutedog.app.pet

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object PetInteractionMenu {
    private val chat = MutableStateFlow(false)
    val chatVisible = chat.asStateFlow()

    fun setChatVisible(visible: Boolean) { chat.value = visible }

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
