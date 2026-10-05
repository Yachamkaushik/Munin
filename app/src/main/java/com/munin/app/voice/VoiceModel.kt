package com.munin.app.voice

/** The languages offered for voice queries (typed queries accept any mix). */
enum class VoiceLang(val tag: String, val label: String) {
    ENGLISH("en-IN", "English"),
    HINDI("hi-IN", "हिन्दी"),
    TELUGU("te-IN", "తెలుగు"),
}

/** Whether a language can be recognised without the internet on this phone. */
enum class PackStatus {
    /** The offline pack is installed: speech stays on the phone. */
    INSTALLED,
    /** Supported on-device but the pack is not installed; the system may fall back to the internet. */
    NOT_INSTALLED,
    /** The pack is being downloaded. */
    DOWNLOADING,
    /** This phone only offers the language online. */
    ONLINE_ONLY,
    /** This Android version cannot tell us (checking needs Android 13). */
    UNKNOWN,
    /** The recognizer does not list the language at all. */
    UNSUPPORTED,
}

/** What this phone can do for voice, as reported by the system. */
data class VoiceSupport(
    /** Whether any speech recognition service exists. When false, only typing works. */
    val available: Boolean,
    /** Whether the recognizer used is the on-device one (Android 12+) rather than the system default. */
    val onDeviceRecognizer: Boolean,
    val packs: Map<VoiceLang, PackStatus>,
)

object PackStatuses {
    /** Language subtag, lower-case: "te_IN" and "te-IN" and "te" all become "te". */
    private fun language(tag: String) = tag.replace('_', '-').substringBefore('-').lowercase()

    /**
     * Works out the status of [lang] from the four lists `RecognitionSupport` reports (installed, pending, supported on-device, online). A pack for the same language in a
     * different region (en-US for en-IN) counts, because the recognizer treats them as one on-device model family.
     */
    fun of(lang: VoiceLang, installed: Collection<String>, supported: Collection<String>, pending: Collection<String>, online: Collection<String>): PackStatus {
        val l = language(lang.tag)
        fun has(c: Collection<String>) = c.any { language(it) == l }
        return when {
            has(installed) -> PackStatus.INSTALLED
            has(pending) -> PackStatus.DOWNLOADING
            has(supported) -> PackStatus.NOT_INSTALLED
            has(online) -> PackStatus.ONLINE_ONLY // only reached when there is no on-device option
            else -> PackStatus.UNSUPPORTED
        }
    }

    private val VAGUE_FAILURES = setOf(VoiceError.CLIENT, VoiceError.NO_RESPONSE, VoiceError.SERVER, VoiceError.NETWORK, VoiceError.LANGUAGE_UNAVAILABLE, VoiceError.NO_MATCH)

    /**
     * A generic failure often has one known cause: the language pack. When the system already told us the pack is not
     * installed (or not offered), say so next to the error, so the user is not left guessing.
     */
    fun failureHint(error: VoiceError, s: VoiceSupport?, lang: VoiceLang): String? {
        if (error !in VAGUE_FAILURES || error == VoiceError.NO_MATCH) return null
        return when (s?.packs?.get(lang)) {
            PackStatus.NOT_INSTALLED, PackStatus.DOWNLOADING -> "The offline pack for ${lang.label} is not installed on this phone, which is the usual cause."
            PackStatus.UNSUPPORTED -> "This phone's speech service does not offer ${lang.label}, which is the usual cause."
            PackStatus.ONLINE_ONLY -> "${lang.label} is only offered online on this phone, so it needs the internet."
            else -> null
        }
    }

    /** The sentence shown under the language choice. It never claims more privacy than the status supports. */
    fun explain(s: VoiceSupport, lang: VoiceLang): String {
        if (!s.available) return "Voice input is not available on this phone. Typing works fully offline."
        return when (s.packs[lang] ?: PackStatus.UNKNOWN) {
            PackStatus.INSTALLED -> "${lang.label}: the offline language pack is installed, so speech is recognised on the phone."
            PackStatus.NOT_INSTALLED -> "${lang.label}: the offline pack is not installed. Without it your phone's speech service may send audio over the internet (Munin itself has no internet access). Install the pack in the phone's speech settings, or type instead."
            PackStatus.DOWNLOADING -> "${lang.label}: the offline pack is still downloading. Until it finishes, voice may use the internet."
            PackStatus.ONLINE_ONLY -> "${lang.label}: this phone only offers it online, so audio may be sent over the internet by your phone's speech service. Typing stays fully offline."
            PackStatus.UNSUPPORTED -> "${lang.label}: this phone's speech service does not list this language. Voice may not work. Typing always does."
            PackStatus.UNKNOWN -> "${lang.label}: this Android version cannot say whether the offline pack is installed. Voice is offline only if it is."
        }
    }
}

/** Why a voice attempt did not produce text, in terms the user can act on. */
enum class VoiceError(val message: String) {
    NO_MATCH("Didn't catch that. Try again, or type."),
    SPEECH_TIMEOUT("No speech heard. Tap Speak and talk right away."),
    NETWORK("The speech service needed the internet and could not reach it. Install the offline pack for this language, or type."),
    LANGUAGE_UNSUPPORTED("This language is not supported by the phone's speech service. Type instead."),
    LANGUAGE_UNAVAILABLE("The offline pack for this language is not installed. Install it in the phone's speech settings, or type."),
    NO_PERMISSION("Microphone permission is needed for voice. You can still type."),
    BUSY("The speech service is busy. Try again in a moment."),
    AUDIO("There was a problem with the microphone."),
    SERVER("The speech service had a problem. Try again, or type."),
    CLIENT("Voice input stopped unexpectedly. Try again."),
    UNAVAILABLE("Voice input is not available on this phone. Typing works fully offline."),
    /** The speech service accepted the request but never reported back (seen when an offline language pack is missing). */
    NO_RESPONSE("The speech service did not respond. This often means the offline language pack is not installed. Install it in the phone's speech settings, or type instead."),
}
