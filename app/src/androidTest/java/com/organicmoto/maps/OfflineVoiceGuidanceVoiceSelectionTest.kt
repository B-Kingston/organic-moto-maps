package com.organicmoto.maps

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.organicmoto.maps.routing.navigation.NavigationSnapshot
import com.organicmoto.maps.routing.navigation.NavigationState
import com.organicmoto.maps.routing.navigation.VoiceDistanceUnit
import com.organicmoto.maps.routing.navigation.VoiceGuidanceSettings
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineVoiceGuidanceVoiceSelectionTest {

    @Test
    fun prefersInstalledOfflineVoiceForEnglishPhrases() {
        val australianOnline = voice(
            name = "online-en-au",
            languageTag = "en-AU",
            quality = Voice.QUALITY_VERY_HIGH,
            requiresNetwork = true,
        )
        val australianNotInstalled = voice(
            name = "missing-en-au",
            languageTag = "en-AU",
            quality = Voice.QUALITY_VERY_HIGH,
            features = setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
        )
        val usEnglish = voice(
            name = "offline-en-us",
            languageTag = "en-US",
            quality = Voice.QUALITY_VERY_HIGH,
        )
        val australianEnglish = voice(
            name = "offline-en-au",
            languageTag = "en-AU",
            quality = Voice.QUALITY_NORMAL,
        )
        val french = voice(
            name = "offline-fr-fr",
            languageTag = "fr-FR",
            quality = Voice.QUALITY_VERY_HIGH,
        )

        val selected = chooseOfflineEnglishVoice(
            setOf(australianOnline, australianNotInstalled, usEnglish, australianEnglish, french),
            Locale.forLanguageTag("en-AU"),
        )

        assertEquals("offline-en-au", selected?.name)
    }

    @Test
    fun nonEnglishDeviceLocaleStillRequiresAnEnglishOfflineVoice() {
        val french = voice("offline-fr", "fr-FR", Voice.QUALITY_VERY_HIGH)
        val onlineEnglish = voice("online-en", "en-GB", Voice.QUALITY_VERY_HIGH, requiresNetwork = true)
        val missingEnglish = voice(
            "missing-en",
            "en-GB",
            Voice.QUALITY_VERY_HIGH,
            features = setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
        )

        assertNull(
            chooseOfflineEnglishVoice(
                setOf(french, onlineEnglish, missingEnglish),
                Locale.forLanguageTag("fr-FR"),
            ),
        )
    }

    @Test
    fun nonEnglishDeviceLocaleCanUseAnyInstalledEnglishVoice() {
        val english = voice("offline-en-gb", "en-GB", Voice.QUALITY_NORMAL)

        val selected = chooseOfflineEnglishVoice(setOf(english), Locale.forLanguageTag("fr-FR"))

        assertEquals("offline-en-gb", selected?.name)
    }

    @Test
    fun idleSnapshotLeavesSpeechServiceUninitialized() {
        val output = OfflineVoiceGuidance(
            ApplicationProvider.getApplicationContext(),
            VoiceDistanceUnit.METRIC,
        )
        try {
            output.onSnapshot(
                NavigationSnapshot(
                    state = NavigationState.Idle,
                    lat = Double.NaN,
                    lon = Double.NaN,
                    bearingDeg = Double.NaN,
                    speedMps = Double.NaN,
                    speedLimitMps = Double.NaN,
                    remainingDistanceM = 0.0,
                    remainingTimeS = 0.0,
                    completionPercent = 0,
                    paceDeltaS = Double.NaN,
                    turn = null,
                ),
                VoiceGuidanceSettings(enabled = true),
            )

            assertSame(OfflineSpeechStatus.NotStarted, output.status.value)
        } finally {
            output.close()
        }
    }

    private fun voice(
        name: String,
        languageTag: String,
        quality: Int,
        requiresNetwork: Boolean = false,
        features: Set<String> = emptySet(),
    ): Voice = Voice(
        name,
        Locale.forLanguageTag(languageTag),
        quality,
        Voice.LATENCY_NORMAL,
        requiresNetwork,
        features,
    )
}
