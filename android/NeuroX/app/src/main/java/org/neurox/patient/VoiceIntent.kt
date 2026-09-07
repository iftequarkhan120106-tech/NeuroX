package org.neurox.patient

// ──────────────────────────────────────────────
// Voice intent hierarchy
// ──────────────────────────────────────────────

/**
 * Represents a recognised user intent parsed from a voice transcript.
 *
 * All intents are designed to have an equivalent large-touch action so
 * that users who cannot or prefer not to use voice can always reach the
 * same destination. See [VoiceListeningScreen] for the touch equivalents.
 */
sealed class VoiceIntent {
    /** The patient wants to start a cognitive activity. */
    data class StartActivity(val activityId: String?) : VoiceIntent()

    /** The patient wants to hear or see today's reminders. */
    object ListReminders : VoiceIntent()

    /** The patient needs help — routes to the Safety screen. */
    object RequestHelp : VoiceIntent()

    /**
     * The transcript was understood but didn't match a known intent.
     * [transcript] is shown verbatim so the user can see what was heard.
     */
    data class Unknown(val transcript: String) : VoiceIntent()
}

// ──────────────────────────────────────────────
// Intent parser
// ──────────────────────────────────────────────

/**
 * Parses a raw voice transcript into a [VoiceIntent] using deterministic
 * keyword matching. No external service is required; the hackathon demo
 * works entirely offline.
 *
 * Keywords are normalised (trimmed, lowercased) before matching. Adding a
 * new intent is a matter of adding a new keyword set and returning the
 * appropriate sealed subtype.
 */
object IntentParser {

    // Keywords that trigger each intent, grouped by intent type.
    // Multiple synonyms are listed to improve robustness across accents
    // and slight mis-recognitions.

    private val startActivityKeywords = listOf(
        "start", "begin", "play", "open", "launch", "do"
    )
    private val memoryMatchKeywords = listOf(
        "memory", "match", "matching", "pictures", "cards"
    )
    private val objectRecallKeywords = listOf(
        "object", "objects", "recall", "remember objects", "remember the objects"
    )
    private val patternKeywords = listOf(
        "pattern", "sequence", "completion", "next"
    )
    private val reminderKeywords = listOf(
        "reminder", "reminders", "remind", "schedule", "what do i have", "what's next",
        "show reminders", "my reminders", "today's reminders"
    )
    private val helpKeywords = listOf(
        "help", "sos", "emergency", "i need help", "assistance", "unsafe", "danger",
        "contact caregiver", "contact anita"
    )

    /**
     * Parse [transcript] into a [VoiceIntent].
     *
     * @param transcript  Raw text returned by the speech provider.
     * @param languageCode  BCP-47 code of the language used during recognition
     *                      (reserved for future locale-specific parsing).
     */
    fun parse(transcript: String, languageCode: String = "en-IN"): VoiceIntent {
        val text = transcript.trim().lowercase()

        // Activity intents — detect the activity type from the transcript first,
        // so that activity names containing help-adjacent words (e.g. "recall")
        // are not mistakenly classified as RequestHelp.
        val hasStartVerb = startActivityKeywords.any { text.contains(it) }
        val activityMention = when {
            memoryMatchKeywords.any { text.contains(it) }  -> "memory-match"
            objectRecallKeywords.any { text.contains(it) } -> "object-recall"
            patternKeywords.any { text.contains(it) }      -> "pattern"
            else                                           -> null
        }

        if (activityMention != null || (hasStartVerb && !text.contains("help"))) {
            return VoiceIntent.StartActivity(activityId = activityMention)
        }

        // Safety / help intent
        if (helpKeywords.any { text.contains(it) }) {
            return VoiceIntent.RequestHelp
        }

        // Reminder intent
        if (reminderKeywords.any { text.contains(it) }) {
            return VoiceIntent.ListReminders
        }

        // Generic start verb with no specific activity
        if (hasStartVerb) {
            return VoiceIntent.StartActivity(activityId = null)
        }

        // Unrecognised — return the transcript for display
        return VoiceIntent.Unknown(transcript)
    }

    /**
     * Returns a user-friendly confirmation string for a resolved intent,
     * shown in the listening screen before navigation takes place.
     */
    fun describe(intent: VoiceIntent): String = when (intent) {
        is VoiceIntent.StartActivity -> when (intent.activityId) {
            "memory-match"  -> "Starting Memory Match…"
            "object-recall" -> "Starting Remember the Objects…"
            "pattern"       -> "Starting Pattern Completion…"
            else            -> "Starting today's activity…"
        }
        is VoiceIntent.ListReminders  -> "Showing today's reminders…"
        is VoiceIntent.RequestHelp    -> "Opening Safety — I Need Help…"
        is VoiceIntent.Unknown        -> "I heard: '${intent.transcript}' — tap an option below."
    }
}
