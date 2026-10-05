package com.munin.app.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The part of Android's recognizer the session needs, so the state machine can be tested with a fake. */
interface SpeechBackend {
    interface Listener {
        fun onReady()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(error: VoiceError)
    }

    fun start(lang: VoiceLang, listener: Listener)
    /** Stop listening and deliver what was heard so far. */
    fun stop()
    /** Abandon the attempt; no result is delivered. */
    fun cancel()
    fun destroy()
}

/** Runs a task later on the main thread; faked in tests. */
interface Scheduler {
    fun interface Cancellable { fun cancel() }
    fun postDelayed(delayMs: Long, task: () -> Unit): Cancellable
}

sealed interface VoiceState {
    data object Idle : VoiceState
    /** The microphone is being opened. */
    data object Starting : VoiceState
    data class Listening(val partial: String) : VoiceState
    /** Stop was pressed; waiting for the recognizer's final text. */
    data class Processing(val partial: String) : VoiceState
    data class Failed(val error: VoiceError) : VoiceState
}

/**
 * One voice attempt at a time. Partial text is shown while talking, and the final text is handed to [onText] once. Nothing
 * is ever searched or acted on from here: the caller decides what to do with the text.
 *
 * Watchdogs: a recognizer that is missing its language pack can accept a request and then never call back (observed on a
 * real recognizer, which logged a language-pack error internally and told the app nothing). So every wait has a limit, and
 * running out of time fails with [VoiceError.NO_RESPONSE] instead of leaving the user on a screen that says "Listening".
 */
class VoiceSession(
    private val backend: SpeechBackend,
    private val scheduler: Scheduler,
    private val onText: (text: String, final: Boolean) -> Unit,
) {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state
    private var attempt = 0
    private var timer: Scheduler.Cancellable? = null

    val busy get() = _state.value is VoiceState.Starting || _state.value is VoiceState.Listening || _state.value is VoiceState.Processing

    fun start(lang: VoiceLang) {
        if (busy) return
        val mine = ++attempt
        _state.value = VoiceState.Starting
        arm(START_TIMEOUT_MS) { giveUp(mine) }
        backend.start(lang, object : SpeechBackend.Listener {
            private fun current() = mine == attempt // results from an abandoned attempt are ignored
            override fun onReady() {
                if (!current()) return
                _state.value = VoiceState.Listening("")
                arm(LISTEN_LIMIT_MS) { autoStop(mine) }
            }
            override fun onPartial(text: String) {
                if (!current()) return
                val processing = _state.value is VoiceState.Processing
                _state.value = if (processing) VoiceState.Processing(text) else VoiceState.Listening(text)
                if (!processing) arm(LISTEN_LIMIT_MS) { autoStop(mine) } // still talking: restart the limit
                onText(text, false)
            }
            override fun onFinal(text: String) {
                if (!current()) return
                disarm()
                _state.value = VoiceState.Idle
                if (text.isBlank()) _state.value = VoiceState.Failed(VoiceError.NO_MATCH) else onText(text, true)
            }
            override fun onError(error: VoiceError) {
                if (!current()) return
                disarm()
                _state.value = VoiceState.Failed(error)
            }
        })
    }

    /** Stop talking and wait for the text. */
    fun stop() {
        val s = _state.value
        if (s !is VoiceState.Listening) return
        val mine = attempt
        _state.value = VoiceState.Processing(s.partial)
        arm(FINAL_TIMEOUT_MS) { giveUp(mine) }
        backend.stop()
    }

    fun cancel() {
        attempt++ // anything still in flight is ignored
        disarm()
        backend.cancel()
        _state.value = VoiceState.Idle
    }

    fun fail(error: VoiceError) { _state.value = VoiceState.Failed(error) }
    fun dismissError() { if (_state.value is VoiceState.Failed) _state.value = VoiceState.Idle }
    fun destroy() { attempt++; disarm(); backend.destroy() }

    private fun arm(delayMs: Long, task: () -> Unit) { timer?.cancel(); timer = scheduler.postDelayed(delayMs, task) }
    private fun disarm() { timer?.cancel(); timer = null }

    /** Nothing was heard for [LISTEN_LIMIT_MS]: stop as if the user pressed Stop. */
    private fun autoStop(mine: Int) { if (mine == attempt && _state.value is VoiceState.Listening) stop() }

    /** The recognizer went quiet: abandon the attempt and say so. */
    private fun giveUp(mine: Int) {
        if (mine != attempt || !busy) return
        attempt++
        timer = null
        backend.cancel()
        _state.value = VoiceState.Failed(VoiceError.NO_RESPONSE)
    }

    companion object {
        /** From pressing Speak to the microphone being ready. */
        const val START_TIMEOUT_MS = 8_000L
        /** Listening with no new words: long enough for a pause, short enough that a dead service is noticed. */
        const val LISTEN_LIMIT_MS = 20_000L
        /** From Stop to the final text. */
        const val FINAL_TIMEOUT_MS = 6_000L
    }
}
