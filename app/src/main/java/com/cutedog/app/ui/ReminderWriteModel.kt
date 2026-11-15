package com.cutedog.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.cutedog.app.data.Reminder
import com.cutedog.app.data.ReminderRepository
import com.cutedog.app.reminder.ReminderScheduler
import kotlinx.coroutines.*

/** Owns a write across Activity recreation, so rotating cannot repeat a completed insert. */
class ReminderWriteModel : ViewModel() {
    data class Result(val originalId: Long, val savedId: Long, val message: String)
    private val operationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var running by mutableStateOf(false)
        private set
    var result by mutableStateOf<Result?>(null)
        private set

    fun save(context: Context, item: Reminder) {
        if (running || result != null) return
        val application = context.applicationContext
        running = true
        operationScope.launch {
            var savedId = 0L
            try {
                val saved = withContext(Dispatchers.IO) {
                    val repository = ReminderRepository(application)
                    val id = repository.save(item)
                    savedId = id
                    repository.get(id) ?: item.copy(id = id)
                }
                savedId = saved.id
                withContext(Dispatchers.IO) { ReminderScheduler(application).schedule(saved) }
                result = Result(item.id, savedId, "事项已保存。可在小狗设置中检查提醒权限。")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                result = Result(item.id, savedId, if (savedId > 0) "事项已保存，但提醒调度失败。请检查权限后重新打开应用恢复提醒。" else "保存失败：${e.message ?: "请稍后重试"}")
            } finally { running = false }
        }
    }

    fun consumeResult() { result = null }
    override fun onCleared() { operationScope.cancel(); super.onCleared() }
}
