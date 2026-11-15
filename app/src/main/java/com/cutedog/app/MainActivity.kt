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
    private data class LaunchRequest(val reminderId: Long = 0L, val openDaily: Boolean = false, val sequence: Int = 0)
    private var request by mutableStateOf(LaunchRequest())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) consumeIntent(intent)
        setContent {
            CuteDogTheme {
                CuteDogApp(request.reminderId, request.openDaily, request.sequence)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent) {
        request = LaunchRequest(intent.getLongExtra("reminder_id", 0L), intent.getBooleanExtra("open_daily", false), request.sequence + 1)
    }
}
