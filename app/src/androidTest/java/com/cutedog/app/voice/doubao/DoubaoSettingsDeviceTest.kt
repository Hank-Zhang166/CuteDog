package com.cutedog.app.voice.doubao

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class DoubaoSettingsDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val namespace = "instrumentation_fixture"
    private val preferenceName = "doubao_settings_$namespace"
    private val prefs get() = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    @Before
    fun prepare() {
        prefs.edit().clear().commit()
    }

    @After
    fun cleanFixtures() {
        prefs.edit().clear().commit()
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry("cutedog.$preferenceName.voice.v1")
            deleteEntry("cutedog.$preferenceName.search.v1")
        }
    }

    @Test
    fun encryptsBothKeysAndRevokesMicrophoneConsentWhenVoiceKeyChanges() {
        val settings = DoubaoSettings(context, namespace)
        val voice = "voice-fixture-012345"
        val search = "search-fixture-543210"
        assertFalse(settings.microphoneUploadAccepted)
        settings.save(voice, search)

        val reopened = DoubaoSettings(context, namespace)
        assertTrue(reopened.hasVoiceKey && reopened.hasSearchKey)
        assertEquals(voice, reopened.voiceApiKey)
        assertEquals(search, reopened.searchApiKey)
        assertFalse(prefs.all.values.any { voice in it.toString() || search in it.toString() })

        reopened.microphoneUploadAccepted = true
        reopened.save("", "updated-search-fixture")
        assertTrue(settings.microphoneUploadAccepted)
        assertEquals(voice, settings.voiceApiKey)
        assertEquals("updated-search-fixture", settings.searchApiKey)
        reopened.save("updated-voice-fixture", "")
        assertFalse(settings.microphoneUploadAccepted)
        assertEquals("updated-search-fixture", settings.searchApiKey)

        reopened.microphoneUploadAccepted = true
        reopened.clear()
        assertFalse(settings.microphoneUploadAccepted)
        assertEquals("", settings.voiceApiKey)
        assertEquals("", settings.searchApiKey)
    }

    @Test
    fun rejectsHeaderInjectionWithoutChangingEitherKey() {
        val settings = DoubaoSettings(context, namespace)
        settings.save("voice-fixture", "search-fixture")
        assertThrows(IllegalArgumentException::class.java) {
            settings.save("replacement-fixture", "a\r\nX-Fake: injected")
        }
        assertEquals("voice-fixture", settings.voiceApiKey)
        assertEquals("search-fixture", settings.searchApiKey)
    }
}
