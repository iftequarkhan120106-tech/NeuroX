package org.neurox.patient

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

// ──────────────────────────────────────────────
// Speech provider abstraction
// ──────────────────────────────────────────────

/**
 * A minimal, technology-agnostic speech-to-text interface.
 * Every provider must expose whether it can handle the current language
 * before starting a listening session, so the UI can show a clear
 * fallback message rather than silently failing.
 */
interface SpeechProvider {
    /** True if this provider can recognise speech for [languageCode]. */
    fun isSupported(languageCode: String): Boolean

    /**
     * Begin listening. Calls [onResult] with the top hypothesis once
     * recognition ends, or [onError] with a human-readable message.
     * Must be called on the main thread.
     */
    fun startListening(languageCode: String, onResult: (String) -> Unit, onError: (String) -> Unit)

    /** Cancel an in-progress listening session without reporting a result. */
    fun stopListening()
}

// ──────────────────────────────────────────────
// Mock provider (demo / tests — no hardware needed)
// ──────────────────────────────────────────────

/**
 * Returns a deterministic sequence of transcripts so the hackathon
 * demo always works offline and without real microphone input.
 *
 * Cycling through the phrases lets a demonstrator trigger each intent
 * by tapping the mic button in sequence.
 */
class MockSpeechProvider : SpeechProvider {
    private val phrases = listOf(
        "start memory match",
        "show my reminders",
        "I need help",
        "start object recall",
        "start pattern activity"
    )
    private var index = 0

    override fun isSupported(languageCode: String): Boolean = true

    override fun startListening(languageCode: String, onResult: (String) -> Unit, onError: (String) -> Unit) {
        // Simulate a short recognition delay, then return the next mock phrase.
        val result = phrases[index % phrases.size]
        index++
        // The caller supplies a coroutine scope; we call back synchronously here
        // because MockSpeechProvider is used only in controlled demo/test contexts.
        onResult(result)
    }

    override fun stopListening() { /* no-op */ }
}

// ──────────────────────────────────────────────
// Android on-device provider (SpeechRecognizer)
// ──────────────────────────────────────────────

/**
 * Uses Android's built-in [SpeechRecognizer] (Google on-device ASR).
 * Requires RECORD_AUDIO permission and Google Play Services.
 * Falls back gracefully when the device does not have a recogniser
 * available — [isSupported] will return false in that case.
 */
class AndroidSpeechProvider(private val context: Context) : SpeechProvider {
    private var recognizer: SpeechRecognizer? = null

    override fun isSupported(languageCode: String): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    override fun startListening(languageCode: String, onResult: (String) -> Unit, onError: (String) -> Unit) {
        stopListening() // ensure no stale session

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition is not available on this device.")
            return
        }

        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val top = matches?.firstOrNull()
                if (top != null) onResult(top)
                else onError("Could not understand. Please try again.")
            }

            override fun onError(error: Int) {
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Could not understand. Please try again."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected. Please tap the microphone and speak."
                    SpeechRecognizer.ERROR_AUDIO -> "Microphone error. Please check your device microphone."
                    SpeechRecognizer.ERROR_NETWORK -> "Network error during speech recognition."
                    else -> "Speech recognition failed. Please try again."
                }
                onError(message)
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageCode)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageCode)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        sr.startListening(intent)
    }

    override fun stopListening() {
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
    }
}

// ──────────────────────────────────────────────
// Whisper provider stub (future remote ASR)
// ──────────────────────────────────────────────

/**
 * Stub for a future Whisper-compatible remote endpoint.
 * Always reports itself as unsupported until [baseUrl] is configured,
 * so the UI will show the correct fallback message rather than crashing.
 */
class WhisperSpeechProvider(private val baseUrl: String? = null) : SpeechProvider {
    override fun isSupported(languageCode: String): Boolean = baseUrl != null

    override fun startListening(languageCode: String, onResult: (String) -> Unit, onError: (String) -> Unit) {
        onError("Whisper speech provider is not configured. Set a base URL to enable it.")
    }

    override fun stopListening() { /* no-op */ }
}

// ──────────────────────────────────────────────
// BHASHINI provider (Indian government ASR)
// ──────────────────────────────────────────────

/**
 * Speech-to-text provider backed by BHASHINI (Ulca / AI4Bharat), the
 * Indian government's multilingual ASR platform.
 *
 * BHASHINI natively supports Indian regional languages — including Assamese,
 * Bengali, Hindi, Tamil, and others — that Android on-device ASR may not
 * handle well. This makes it the preferred provider for non-English Indian
 * patients in NeuroX.
 *
 * Configuration:
 *   - [apiKey]    — BHASHINI Ulca API key from https://bhashini.gov.in/ulca
 *   - [userId]    — Ulca user ID linked to the API key
 *   - [pipelineId] — Ulca ASR pipeline ID for the target language
 *
 * When [apiKey] is null (not yet configured), [isSupported] returns false and
 * the UI displays a clear fallback message. No crash, no silent failure.
 *
 * NOTE: Audio recording (PCM bytes) is handled by the calling layer. This
 * stub simulates a network call; real integration requires platform audio
 * capture and a brief (≤ 30 s) WAV/FLAC clip per request.
 */
class BHASHINISpeechProvider(
    private val apiKey: String? = null,
    private val userId: String? = null,
    private val pipelineId: String? = null
) : SpeechProvider {

    /** BCP-47 codes for languages BHASHINI Ulca can reliably transcribe. */
    private val supportedLanguageCodes = setOf(
        "as-IN",  // Assamese — primary target for NeuroX Assamese patients
        "bn-IN",  // Bengali
        "hi-IN",  // Hindi
        "gu-IN",  // Gujarati
        "kn-IN",  // Kannada
        "ml-IN",  // Malayalam
        "mr-IN",  // Marathi
        "or-IN",  // Odia
        "pa-IN",  // Punjabi
        "ta-IN",  // Tamil
        "te-IN",  // Telugu
        "ur-IN"   // Urdu
    )

    private val client = OkHttpClient()
    private var cancelled = false

    /**
     * Returns true only when:
     * 1. The API key and user ID are configured (provider is usable), AND
     * 2. The requested [languageCode] is in BHASHINI's supported set.
     *
     * This guarantees the UI never shows the mic button for a language
     * that BHASHINI cannot transcribe.
     */
    override fun isSupported(languageCode: String): Boolean =
        apiKey != null && userId != null && languageCode in supportedLanguageCodes

    /**
     * Submits a recognition request to the BHASHINI Ulca ASR pipeline.
     *
     * In this prototype the audio bytes are a fixed silent WAV placeholder;
     * real integration wires platform audio capture here. The network call
     * is dispatched on [Dispatchers.IO] and the result is returned on the
     * main thread via [onResult] / [onError] callbacks.
     *
     * If the API key is not configured, [onError] is called immediately
     * with a clear explanation.
     */
    override fun startListening(
        languageCode: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isSupported(languageCode)) {
            onError(
                if (apiKey == null)
                    "BHASHINI API key is not configured. Contact the NeuroX team to enable regional-language voice support."
                else
                    "BHASHINI does not support speech recognition for language code $languageCode."
            )
            return
        }

        cancelled = false

        // Build a minimal Ulca ASR inference request.
        // Payload structure follows the BHASHINI Ulca inference API v1 schema.
        val payload = JSONObject().apply {
            put("pipelineTasks", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("taskType", "asr")
                    put("config", JSONObject().apply {
                        put("language", JSONObject().apply {
                            put("sourceLanguage", languageCode.substringBefore("-"))
                        })
                        put("serviceId", pipelineId ?: "")
                        put("audioFormat", "wav")
                        put("samplingRate", 16000)
                    })
                })
            })
            // Audio bytes would be base64-encoded and placed here.
            // Placeholder: empty audio for prototype stub.
            put("inputData", JSONObject().apply {
                put("audio", org.json.JSONArray().apply {
                    put(JSONObject().apply { put("audioContent", "") })
                })
            })
        }

        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://dhruva-api.bhashini.gov.in/services/inference/pipeline")
            .addHeader("Authorization", apiKey ?: "")
            .addHeader("userID", userId ?: "")
            .post(body)
            .build()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = client.newCall(request).execute()
                if (cancelled) return@launch
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        onError("BHASHINI recognition failed (HTTP ${response.code}). Please try again.")
                    }
                    return@launch
                }
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val transcript = json
                    .optJSONArray("pipelineResponse")
                    ?.optJSONObject(0)
                    ?.optJSONArray("output")
                    ?.optJSONObject(0)
                    ?.optString("source", "")
                    ?: ""
                withContext(Dispatchers.Main) {
                    if (transcript.isNotBlank()) onResult(transcript)
                    else onError("BHASHINI returned an empty transcript. Please speak clearly and try again.")
                }
            } catch (e: Exception) {
                if (!cancelled) {
                    withContext(Dispatchers.Main) {
                        onError("Could not reach BHASHINI. Please check your connection and try again.")
                    }
                }
            }
        }
    }

    override fun stopListening() { cancelled = true }
}

// ──────────────────────────────────────────────
// Provider factory
// ──────────────────────────────────────────────

/**
 * Returns the best available [SpeechProvider] for the current context.
 *
 * Priority:
 * 1. [MockSpeechProvider] when [demoMode] is true — deterministic, no hardware.
 * 2. [BHASHINISpeechProvider] when [bhashinApiKey] is set and the language
 *    is in BHASHINI's supported set — preferred for Indian regional languages.
 * 3. [AndroidSpeechProvider] when the device has a recognition service — good
 *    for English and Hindi with Google services.
 * 4. [WhisperSpeechProvider] stub — always unavailable until a base URL is set.
 *
 * BHASHINI is placed above Android on-device recognition because it provides
 * far better accuracy for Assamese and other low-resource Indian languages.
 *
 * For the hackathon demo, [demoMode] should remain true so the app always
 * works without a microphone or an API key.
 */
fun buildSpeechProvider(
    context: Context,
    demoMode: Boolean = true,
    bhashinApiKey: String? = null,
    bhashinUserId: String? = null,
    bhashinPipelineId: String? = null
): SpeechProvider = when {
    demoMode -> MockSpeechProvider()
    bhashinApiKey != null -> BHASHINISpeechProvider(
        apiKey = bhashinApiKey,
        userId = bhashinUserId,
        pipelineId = bhashinPipelineId
    )
    SpeechRecognizer.isRecognitionAvailable(context) -> AndroidSpeechProvider(context)
    else -> WhisperSpeechProvider()
}
