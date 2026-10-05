package com.munin.app.assist

import android.app.role.RoleManager
import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.munin.app.launch.SearchLaunch

/**
 * Lets the user choose Munin as the phone's "digital assistant", so the system's assist gesture (long-press power or home, depending on the phone)
 * opens Munin's search. It is only a door: Munin does not listen, understand speech or answer by voice here. It does nothing until the user picks it
 * in the phone's own settings, and the system starts it only when the gesture is used.
 */
class MuninInteractionService : VoiceInteractionService()

class MuninSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = MuninSession(this)
}

class MuninSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = SearchLaunch.intent(context)
        try { startAssistantActivity(intent) } catch (_: Exception) { runCatching { context.startActivity(intent) } }
        finish()
    }
}

/**
 * Android refuses an assistant that does not declare a recognition service, so this exists only to satisfy that rule. It has no intent filter, so it never
 * appears in the phone's list of speech recognizers, and it recognises nothing: every request is answered with "not supported".
 */
class MuninNoRecognizer : android.speech.RecognitionService() {
    override fun onStartListening(recognizerIntent: android.content.Intent?, listener: Callback?) { runCatching { listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT) } }
    override fun onCancel(listener: Callback?) = Unit
    override fun onStopListening(listener: Callback?) = Unit
}

object AssistRole {
    /** Whether Munin is currently the phone's digital assistant. Readable by the app itself. */
    fun isHeld(context: Context): Boolean =
        context.getSystemService(RoleManager::class.java)?.let { it.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && it.isRoleHeld(RoleManager.ROLE_ASSISTANT) } == true
}
