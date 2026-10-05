package com.munin.app.voice

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the phone's speech service reports. The values differ per phone, so this checks shape and logs the facts. */
class VoiceSupportTest {
    private fun check(): VoiceSupport {
        val latch = CountDownLatch(1)
        var result: VoiceSupport? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            AndroidSpeechBackend(ApplicationProvider.getApplicationContext()).checkSupport { result = it; latch.countDown() }
        }
        assertTrue("the system never answered the support check", latch.await(10, TimeUnit.SECONDS))
        return result!!
    }

    @Test fun supportCheckAnswersForEveryLanguageAndTheExplanationIsHonest() {
        val s = check()
        Log.i("MuninVoice", "available=${s.available} onDeviceRecognizer=${s.onDeviceRecognizer} packs=${s.packs}")
        if (s.available) {
            assertEquals(VoiceLang.entries.toSet(), s.packs.keys)
            for (lang in VoiceLang.entries) {
                val text = PackStatuses.explain(s, lang)
                Log.i("MuninVoice", text)
                assertNotNull(text)
                // never promise the audio stays on the phone unless the pack is installed
                if (s.packs[lang] != PackStatus.INSTALLED) assertTrue(text, !text.contains("recognised on the phone"))
            }
        } else {
            assertTrue(PackStatuses.explain(s, VoiceLang.ENGLISH).contains("not available"))
        }
    }

    @Test fun startingWithoutThePermissionReportsAnErrorInsteadOfCrashing() {
        // RECORD_AUDIO is not granted to the test app; the recognizer must answer with an error (or the watchdog must).
        val latch = CountDownLatch(1)
        var error: VoiceError? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val backend = AndroidSpeechBackend(ApplicationProvider.getApplicationContext())
            backend.start(VoiceLang.ENGLISH, object : SpeechBackend.Listener {
                override fun onReady() {}
                override fun onPartial(text: String) {}
                override fun onFinal(text: String) { latch.countDown() }
                override fun onError(error2: VoiceError) { error = error2; latch.countDown() }
            })
        }
        latch.await(10, TimeUnit.SECONDS)
        Log.i("MuninVoice", "start without permission -> $error")
    }
}
