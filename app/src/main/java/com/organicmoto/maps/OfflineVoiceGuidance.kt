package com.organicmoto.maps

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.organicmoto.maps.routing.navigation.NavigationSnapshot
import com.organicmoto.maps.routing.navigation.NavigationState
import com.organicmoto.maps.routing.navigation.VoiceDistanceUnit
import com.organicmoto.maps.routing.navigation.VoiceGuidancePlanner
import com.organicmoto.maps.routing.navigation.VoiceGuidanceSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

sealed interface OfflineSpeechStatus {
    data object NotStarted : OfflineSpeechStatus
    data object Checking : OfflineSpeechStatus
    data class Ready(val voiceName: String) : OfflineSpeechStatus
    data class Unavailable(val reason: String) : OfflineSpeechStatus
}

/**
 * Android speech adapter for the pure route-guidance planner. The planner's
 * phrases are English, so this selects an installed English voice that does
 * not require network access; online voices are never a fallback.
 */
class OfflineVoiceGuidance(
    context: Context,
    private val unit: VoiceDistanceUnit,
) : AutoCloseable {
    private val lock = Any()
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val planner = VoiceGuidancePlanner()
    private val locale = Locale.getDefault()
    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(speechAttributes)
        .setAcceptsDelayedFocusGain(false)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(::onAudioFocusChange)
        .build()
    private val _status = MutableStateFlow<OfflineSpeechStatus>(OfflineSpeechStatus.NotStarted)
    val status: StateFlow<OfflineSpeechStatus> = _status.asStateFlow()

    private var speech: TextToSpeech? = null
    private var speechGeneration = 0L
    private var initResultBeforeAssignment: Pair<Long, Int>? = null
    private var currentUtteranceId: String? = null
    private var queuedPrompt: PendingSpeech? = null
    private var utteranceSequence = 0L
    private var audioFocusHeld = false
    private var closed = false
    private var wasEnabled = true
    private var engineStartRequested = false

    /** Feeds every GPS-backed guidance state through the announcement planner. */
    fun onSnapshot(snapshot: NavigationSnapshot, settings: VoiceGuidanceSettings) {
        // The screen normally filters idle/planning fixes before they reach
        // this adapter. Keep the boundary defensive as well: idle snapshots
        // must not create the Android TTS service or advance the planner.
        if (settings.enabled && snapshot.state == NavigationState.Idle) return
        if (settings.enabled) ensureAvailability()
        val prompt = synchronized(lock) {
            if (!settings.enabled) {
                if (wasEnabled) stopPlaybackLocked()
                wasEnabled = false
                planner.onSnapshot(snapshot, settings, unit)
                return
            }

            wasEnabled = true
            if (_status.value is OfflineSpeechStatus.Unavailable) {
                // Do not consume a maneuver while speech is unavailable. The
                // next GPS fix after recovery should preview the live turn.
                planner.reset()
                return
            }
            planner.onSnapshot(snapshot, settings, unit)
        }
        prompt?.let(::speak)
    }

    /**
     * Recreates the TTS service when the settings screen is opened after an
     * unavailable result, allowing a newly installed English voice to appear.
     */
    fun refreshAvailability() {
        val oldEngine = synchronized(lock) {
            if (closed || _status.value !is OfflineSpeechStatus.Unavailable) return@synchronized null
            _status.value = OfflineSpeechStatus.Checking
            speechGeneration++ // Ignore callbacks from the engine being replaced.
            engineStartRequested = false
            val old = speech
            speech = null
            initResultBeforeAssignment = null
            currentUtteranceId = null
            queuedPrompt = null
            releaseAudioFocusLocked()
            old
        }
        oldEngine?.stop()
        oldEngine?.shutdown()
        ensureAvailability()
    }

    /** Starts TTS for active enabled guidance or an explicit settings check. */
    fun ensureAvailability() {
        val shouldStart = synchronized(lock) {
            if (closed || speech != null || engineStartRequested) {
                false
            } else {
                engineStartRequested = true
                _status.value = OfflineSpeechStatus.Checking
                true
            }
        }
        if (shouldStart) startSpeechEngine()
    }

    /** Stops queued or active speech and resets turn identity after navigation ends. */
    fun reset() {
        synchronized(lock) {
            planner.reset()
            wasEnabled = true
            stopPlaybackLocked()
        }
    }

    override fun close() {
        val oldEngine = synchronized(lock) {
            if (closed) return
            closed = true
            engineStartRequested = false
            currentUtteranceId = null
            queuedPrompt = null
            releaseAudioFocusLocked()
            speech.also { speech = null }
        }
        oldEngine?.stop()
        oldEngine?.shutdown()
    }

    /** The init listener is allowed to run before TextToSpeech returns. */
    private fun startSpeechEngine() {
        val generation = synchronized(lock) {
            if (closed) return
            engineStartRequested = true
            speechGeneration += 1
            speechGeneration
        }
        val createdEngine = TextToSpeech(appContext) { result -> onEngineInitialized(generation, result) }
        val (stale, synchronousResult) = synchronized(lock) {
            if (closed || generation != speechGeneration) {
                createdEngine to null
            } else {
                speech = createdEngine
                val result = initResultBeforeAssignment
                    ?.takeIf { it.first == generation }
                    ?.second
                if (result != null) initResultBeforeAssignment = null
                null to result
            }
        }
        stale?.shutdown()
        synchronousResult?.let { initializeVoice(generation, it) }
    }

    private fun onEngineInitialized(generation: Long, result: Int) {
        val canInitializeNow = synchronized(lock) {
            if (closed || generation != speechGeneration) return
            if (speech == null) {
                initResultBeforeAssignment = generation to result
                false
            } else {
                true
            }
        }
        if (canInitializeNow) initializeVoice(generation, result)
    }

    private fun initializeVoice(generation: Long, result: Int) {
        val engine = synchronized(lock) {
            if (closed || generation != speechGeneration) return
            speech
        } ?: return
        if (result != TextToSpeech.SUCCESS) {
            setUnavailable(generation, "The installed speech service could not start.")
            return
        }

        val voice = chooseOfflineEnglishVoice(engine.voices.orEmpty(), locale)
        if (voice == null) {
            setUnavailable(
                generation,
                "No installed offline English speech voice is available. Install one in Android text-to-speech settings; " +
                    "guidance will not use an online voice.",
            )
            return
        }

        if (engine.setVoice(voice) != TextToSpeech.SUCCESS) {
            setUnavailable(generation, "The installed offline English speech voice could not be selected.")
            return
        }
        if (engine.setSpeechRate(1.0f) != TextToSpeech.SUCCESS ||
            engine.setAudioAttributes(speechAttributes) != TextToSpeech.SUCCESS
        ) {
            setUnavailable(generation, "The installed offline speech voice could not be configured.")
            return
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                finishUtterance(generation, utteranceId, failed = false)
            }

            override fun onError(utteranceId: String?) {
                finishUtterance(generation, utteranceId, failed = true)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                finishUtterance(generation, utteranceId, failed = true)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                finishUtterance(generation, utteranceId, failed = false)
            }
        })

        val queued = synchronized(lock) {
            if (closed || generation != speechGeneration) return
            _status.value = OfflineSpeechStatus.Ready(voice.name)
            queuedPrompt.also { queuedPrompt = null }
        }
        if (queued != null) {
            if (System.currentTimeMillis() - queued.createdAtMs <= MAX_PENDING_AGE_MS) {
                speak(queued.text)
            } else {
                synchronized(lock) {
                    if (!closed && generation == speechGeneration) planner.reset()
                }
            }
        }
    }

    private fun speak(text: String) {
        synchronized(lock) {
            if (closed) return
            val engine = speech ?: return
            when (_status.value) {
                OfflineSpeechStatus.Checking -> {
                    queuedPrompt = PendingSpeech(text, System.currentTimeMillis())
                    return
                }
                OfflineSpeechStatus.NotStarted -> return
                is OfflineSpeechStatus.Unavailable -> return
                is OfflineSpeechStatus.Ready -> Unit
            }

            if (currentUtteranceId != null) {
                if (isUrgent(text)) {
                    // An imminent maneuver replaces a long preview promptly;
                    // routine prompts are retained until the current phrase ends.
                    queuedPrompt = null
                    startUtteranceLocked(engine, text, TextToSpeech.QUEUE_FLUSH)
                } else {
                    queuedPrompt = PendingSpeech(text, System.currentTimeMillis())
                }
                return
            }
            startUtteranceLocked(engine, text, TextToSpeech.QUEUE_ADD)
        }
    }

    private fun startUtteranceLocked(engine: TextToSpeech, text: String, queueMode: Int) {
        if (!audioFocusHeld) {
            val result = audioManager.requestAudioFocus(focusRequest)
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                // Focus can be denied temporarily by the system. Keep the
                // installed voice marked ready and retry from a fresh planner
                // state on the next location fix.
                planner.reset()
                return
            }
            audioFocusHeld = true
        }

        utteranceSequence += 1
        val utteranceId = "route-guidance-$utteranceSequence"
        currentUtteranceId = utteranceId
        val result = engine.speak(text, queueMode, Bundle(), utteranceId)
        if (result != TextToSpeech.SUCCESS && currentUtteranceId == utteranceId) {
            setUnavailableLocked("Offline speech playback failed.")
        }
    }

    private fun finishUtterance(generation: Long, utteranceId: String?, failed: Boolean) {
        if (utteranceId == null) return
        synchronized(lock) {
            if (closed || generation != speechGeneration || currentUtteranceId != utteranceId) return
            currentUtteranceId = null
            if (failed) {
                setUnavailableLocked("Offline speech playback failed.")
                return
            }

            val next = queuedPrompt.also { queuedPrompt = null }
            if (next != null) {
                if (System.currentTimeMillis() - next.createdAtMs <= MAX_PENDING_AGE_MS) {
                    val engine = speech
                    if (engine != null && _status.value is OfflineSpeechStatus.Ready) {
                        startUtteranceLocked(
                            engine,
                            next.text,
                            if (isUrgent(next.text)) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                        )
                        return
                    }
                } else {
                    planner.reset()
                }
            }
            releaseAudioFocusLocked()
        }
    }

    private fun setUnavailable(generation: Long, reason: String) {
        synchronized(lock) {
            if (closed || generation != speechGeneration) return
            setUnavailableLocked(reason)
        }
    }

    private fun setUnavailableLocked(reason: String) {
        currentUtteranceId = null
        queuedPrompt = null
        speech?.stop()
        releaseAudioFocusLocked()
        _status.value = OfflineSpeechStatus.Unavailable(reason)
    }

    private fun stopPlaybackLocked() {
        currentUtteranceId = null
        queuedPrompt = null
        speech?.stop()
        releaseAudioFocusLocked()
    }

    private fun releaseAudioFocusLocked() {
        if (!audioFocusHeld) return
        audioFocusHeld = false
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun onAudioFocusChange(change: Int) {
        if (change == AudioManager.AUDIOFOCUS_GAIN) return
        synchronized(lock) {
            if (!audioFocusHeld || closed) return
            // If another app takes focus, drop this prompt and let the next
            // location fix announce the current maneuver again.
            stopPlaybackLocked()
            planner.reset()
        }
    }

    private fun isUrgent(text: String): Boolean =
        text.startsWith("Now,", ignoreCase = true) ||
            text.startsWith("Recalculating the route", ignoreCase = true) ||
            text.startsWith("You have arrived", ignoreCase = true)

    private data class PendingSpeech(val text: String, val createdAtMs: Long)

    private companion object {
        const val MAX_PENDING_AGE_MS = 8_000L
    }
}

/** Select only installed, on-device English voices for the English guidance phrases. */
internal fun chooseOfflineEnglishVoice(voices: Set<Voice>, preferredLocale: Locale): Voice? = voices
    .asSequence()
    .filter { !it.isNetworkConnectionRequired }
    .filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
    .filter { it.locale.language.equals(Locale.ENGLISH.language, ignoreCase = true) }
    .sortedWith(
        compareByDescending<Voice> {
            preferredLocale.language.equals(Locale.ENGLISH.language, ignoreCase = true) &&
                it.locale.country.equals(preferredLocale.country, ignoreCase = true)
        }.thenByDescending { it.quality }
            .thenBy { it.latency },
    )
    .firstOrNull()
