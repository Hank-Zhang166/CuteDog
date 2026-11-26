package com.cutedog.app

import android.os.Bundle
import android.content.Intent
import android.view.Gravity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.cutedog.app.pet.PetInteractionMenu
import com.cutedog.app.pet.PetSettings
import com.cutedog.app.ui.ChatScreen
import com.cutedog.app.ui.CuteDogTheme
import kotlin.math.roundToInt

/** A focusable, IME-capable conversation window above the user's current screen. */
class PetChatActivity : ComponentActivity() {
    private var draftVersion by mutableIntStateOf(0)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(VOICE_DRAFT) || intent.getBooleanExtra(VOICE_SETUP, false)) draftVersion++
        PetInteractionMenu.setChatVisible(intent.getBooleanExtra(FROM_PET_PANEL, false))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)
        setContent {
            CuteDogTheme {
                Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.background) {
                    key(draftVersion) {
                        ChatScreen(onClose = { finish() }, voiceDraft = intent.getStringExtra(VOICE_DRAFT),
                            voiceSetup = intent.getBooleanExtra(VOICE_SETUP, false))
                    }
                }
            }
        }
        val density = resources.displayMetrics.density
        val config = resources.configuration
        window.setLayout((minOf(460f, config.screenWidthDp * .93f) * density).roundToInt(),
            (minOf(540f, config.screenHeightDp * .73f) * density).roundToInt())
        val abovePet = PetSettings(this).yFraction >= .5f
        window.attributes = window.attributes.apply {
            gravity = Gravity.CENTER_HORIZONTAL or (if (abovePet) Gravity.TOP else Gravity.BOTTOM)
            y = (14 * density).roundToInt()
        }
    }

    override fun onStart() {
        super.onStart()
        PetInteractionMenu.setChatVisible(intent.getBooleanExtra(FROM_PET_PANEL, false))
    }

    override fun onStop() {
        if (intent.getBooleanExtra(FROM_PET_PANEL, false)) PetInteractionMenu.setChatVisible(false)
        super.onStop()
    }

    companion object {
        internal const val VOICE_DRAFT = "voice_draft"
        internal const val VOICE_SETUP = "voice_setup"
        internal const val FROM_PET_PANEL = "from_pet_panel"
    }
}
