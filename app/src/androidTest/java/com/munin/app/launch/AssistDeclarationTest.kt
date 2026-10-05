package com.munin.app.launch

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.munin.app.assist.MuninInteractionService
import com.munin.app.assist.MuninNoRecognizer
import com.munin.app.assist.MuninSessionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The assistant door as the system sees it: only the system can bind it, and the required recogniser stub is not offered as a speech recogniser. */
class AssistDeclarationTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val pm = ctx.packageManager

    @Test fun theAssistantServicesAreBindableOnlyBySystem() {
        for (c in listOf(MuninInteractionService::class.java, MuninSessionService::class.java, MuninNoRecognizer::class.java))
            assertEquals(c.name, "android.permission.BIND_VOICE_INTERACTION", pm.getServiceInfo(ComponentName(ctx, c), 0).permission)
    }

    @Test fun theInteractionServiceIsFoundByTheSystemsIntent() {
        val found = pm.queryIntentServices(Intent("android.service.voice.VoiceInteractionService").setPackage(ctx.packageName), PackageManager.GET_META_DATA)
        assertTrue(found.any { it.serviceInfo.name == MuninInteractionService::class.java.name })
    }

    @Test fun theStubIsNotOfferedAsASpeechRecognizer() {
        val found = pm.queryIntentServices(Intent("android.speech.RecognitionService").setPackage(ctx.packageName), 0)
        assertFalse(found.any { it.serviceInfo.name == MuninNoRecognizer::class.java.name })
    }
}
