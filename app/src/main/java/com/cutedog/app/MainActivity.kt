package com.cutedog.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cutedog.app.ui.CuteDogApp
import com.cutedog.app.ui.CuteDogTheme

class MainActivity : ComponentActivity() {
    private data class LaunchRequest(val reminderId: Long = 0L, val openDaily: Boolean = false, val sequence: Int = 0, val openChat: Boolean = false, val showMissed: Boolean = false)
    private var request by mutableStateOf(LaunchRequest())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) consumeIntent(intent)
        setContent {
            CuteDogTheme {
                CuteDogApp(request.reminderId, request.openDaily, request.sequence, request.openChat, request.showMissed)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent) {
        request = LaunchRequest(intent.getLongExtra("reminder_id", 0L), intent.getBooleanExtra("open_daily", false), request.sequence + 1, intent.getBooleanExtra("open_chat", false), intent.getBooleanExtra("show_missed_reminders", false))
    }
    override fun dump(prefix: String, fd: java.io.FileDescriptor?, writer: java.io.PrintWriter, args: Array<out String>?) {
        super.dump(prefix, fd, writer, args)
        writer.println("${prefix}CUTEDOG_VIEW_METRICS_JSON:${com.cutedog.app.pet.PetView.diagnosticSnapshots()}")
    }
}
