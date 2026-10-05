package com.munin.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Android's [SpeechRecognizer]. From Android 12 the on-device recognizer is used when the phone has one, which cannot
 * fall back to the network. Otherwise the system recognizer is asked to prefer offline, which is a request, not a
 * guarantee: if the language pack is missing it may use the internet (through the speech service's own permissions,
 * since Munin has none). All calls must be on the main thread.
 */
class AndroidSpeechBackend(private val context: Context) : SpeechBackend {
    private var recognizer: SpeechRecognizer? = null
    private val onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    val available: Boolean get() = onDevice || SpeechRecognizer.isRecognitionAvailable(context)
    val usesOnDeviceRecognizer: Boolean get() = onDevice

    private fun intent(lang: VoiceLang) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang.tag)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

    private fun newRecognizer(): SpeechRecognizer =
        if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else SpeechRecognizer.createSpeechRecognizer(context)

    override fun start(lang: VoiceLang, listener: SpeechBackend.Listener) {
        if (!available) { listener.onError(VoiceError.UNAVAILABLE); return }
        recognizer?.destroy()
        val r = newRecognizer().also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onReady()
            override fun onPartialResults(partialResults: Bundle?) { first(partialResults)?.let(listener::onPartial) }
            override fun onResults(results: Bundle?) = listener.onFinal(first(results).orEmpty())
            override fun onError(error: Int) = listener.onError(map(error))
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(intent(lang))
    }

    override fun stop() { recognizer?.stopListening() }
    override fun cancel() { recognizer?.cancel() }
    override fun destroy() { recognizer?.destroy(); recognizer = null }

    /** Asks the system which offline language packs are installed (Android 13+); older versions report UNKNOWN. */
    fun checkSupport(onResult: (VoiceSupport) -> Unit) {
        if (!available) { onResult(VoiceSupport(false, false, emptyMap())); return }
        if (Build.VERSION.SDK_INT < 33) {
            onResult(VoiceSupport(true, onDevice, VoiceLang.entries.associateWith { PackStatus.UNKNOWN })); return
        }
        val probe = newRecognizer()
        val results = HashMap<VoiceLang, PackStatus>()
        var pending = VoiceLang.entries.size
        fun done(lang: VoiceLang, status: PackStatus) {
            results[lang] = status
            if (--pending == 0) { probe.destroy(); onResult(VoiceSupport(true, onDevice, results)) }
        }
        for (lang in VoiceLang.entries) {
            probe.checkRecognitionSupport(intent(lang), context.mainExecutor, object : RecognitionSupportCallback {
                override fun onSupportResult(s: RecognitionSupport) = done(
                    lang, PackStatuses.of(lang, s.installedOnDeviceLanguages, s.supportedOnDeviceLanguages, s.pendingOnDeviceLanguages, s.onlineLanguages),
                )
                override fun onError(error: Int) = done(lang, PackStatus.UNKNOWN)
            })
        }
    }

    private fun first(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun map(code: Int) = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH -> VoiceError.NO_MATCH
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceError.SPEECH_TIMEOUT
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> VoiceError.NETWORK
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> VoiceError.LANGUAGE_UNSUPPORTED
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> VoiceError.LANGUAGE_UNAVAILABLE
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceError.NO_PERMISSION
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceError.BUSY
        SpeechRecognizer.ERROR_AUDIO -> VoiceError.AUDIO
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> VoiceError.SERVER
        else -> VoiceError.CLIENT
    }
}

/** Posts to the main thread's handler. */
class MainScheduler : Scheduler {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    override fun postDelayed(delayMs: Long, task: () -> Unit): Scheduler.Cancellable {
        val r = Runnable(task)
        handler.postDelayed(r, delayMs)
        return Scheduler.Cancellable { handler.removeCallbacks(r) }
    }
}
