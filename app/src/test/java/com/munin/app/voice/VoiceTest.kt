package com.munin.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackStatusTest {
    private fun status(lang: VoiceLang, installed: List<String> = emptyList(), supported: List<String> = emptyList(), pending: List<String> = emptyList(), online: List<String> = emptyList()) =
        PackStatuses.of(lang, installed, supported, pending, online)

    @Test fun installedPackMeansOffline() = assertEquals(PackStatus.INSTALLED, status(VoiceLang.TELUGU, installed = listOf("te-IN")))

    @Test fun supportedButNotInstalled() = assertEquals(PackStatus.NOT_INSTALLED, status(VoiceLang.HINDI, installed = listOf("en-US"), supported = listOf("hi-IN", "en-US")))

    @Test fun pendingDownload() = assertEquals(PackStatus.DOWNLOADING, status(VoiceLang.HINDI, pending = listOf("hi-IN"), supported = listOf("hi-IN")))

    @Test fun onlineOnlyAndUnsupported() {
        assertEquals(PackStatus.ONLINE_ONLY, status(VoiceLang.TELUGU, online = listOf("te-IN", "en-IN")))
        assertEquals(PackStatus.UNSUPPORTED, status(VoiceLang.TELUGU, installed = listOf("en-US"), supported = listOf("en-US", "hi-IN")))
    }

    @Test fun aPackForTheSameLanguageInAnotherRegionCountsAndSeparatorsAreIgnored() {
        assertEquals(PackStatus.INSTALLED, status(VoiceLang.ENGLISH, installed = listOf("en_US")))
        assertEquals(PackStatus.INSTALLED, status(VoiceLang.HINDI, installed = listOf("hi")))
        assertEquals(PackStatus.UNSUPPORTED, status(VoiceLang.ENGLISH, installed = listOf("es-ES")))
    }

    @Test fun installedBeatsEverythingElse() = assertEquals(PackStatus.INSTALLED, status(VoiceLang.ENGLISH, installed = listOf("en-IN"), supported = listOf("en-IN"), online = listOf("en-IN")))

    @Test fun theExplanationNeverClaimsMorePrivacyThanTheStatusSupports() {
        fun text(p: PackStatus) = PackStatuses.explain(VoiceSupport(true, true, mapOf(VoiceLang.TELUGU to p)), VoiceLang.TELUGU)
        assertTrue(text(PackStatus.INSTALLED).contains("recognised on the phone"))
        for (p in listOf(PackStatus.NOT_INSTALLED, PackStatus.DOWNLOADING, PackStatus.ONLINE_ONLY)) assertTrue(p.name, text(p).contains("internet"))
        assertTrue(text(PackStatus.UNKNOWN).contains("only if it is"))
        assertFalse(text(PackStatus.NOT_INSTALLED).contains("stays on the phone"))
        assertTrue(PackStatuses.explain(VoiceSupport(false, false, emptyMap()), VoiceLang.ENGLISH).contains("Typing works fully offline"))
    }
}

class FailureHintTest {
    private fun support(p: PackStatus) = VoiceSupport(true, true, mapOf(VoiceLang.ENGLISH to p))

    @Test fun aGenericFailureNamesTheMissingPack() {
        val hint = PackStatuses.failureHint(VoiceError.CLIENT, support(PackStatus.NOT_INSTALLED), VoiceLang.ENGLISH)!!
        assertTrue(hint, hint.contains("offline pack for English is not installed"))
        assertTrue(PackStatuses.failureHint(VoiceError.NO_RESPONSE, support(PackStatus.NOT_INSTALLED), VoiceLang.ENGLISH) != null)
    }

    @Test fun noHintWhenThePackIsInstalledOrUnknownOrTheFailureIsBenign() {
        assertEquals(null, PackStatuses.failureHint(VoiceError.CLIENT, support(PackStatus.INSTALLED), VoiceLang.ENGLISH))
        assertEquals(null, PackStatuses.failureHint(VoiceError.CLIENT, support(PackStatus.UNKNOWN), VoiceLang.ENGLISH))
        assertEquals(null, PackStatuses.failureHint(VoiceError.CLIENT, null, VoiceLang.ENGLISH))
        assertEquals(null, PackStatuses.failureHint(VoiceError.NO_MATCH, support(PackStatus.NOT_INSTALLED), VoiceLang.ENGLISH)) // you just mumbled
        assertEquals(null, PackStatuses.failureHint(VoiceError.NO_PERMISSION, support(PackStatus.NOT_INSTALLED), VoiceLang.ENGLISH))
    }
}

class VoiceSessionTest {
    private class FakeBackend : SpeechBackend {
        var listener: SpeechBackend.Listener? = null
        var started = 0; var stopped = 0; var cancelled = 0; var destroyed = 0
        var lastLang: VoiceLang? = null
        override fun start(lang: VoiceLang, listener: SpeechBackend.Listener) { started++; lastLang = lang; this.listener = listener }
        override fun stop() { stopped++ }
        override fun cancel() { cancelled++ }
        override fun destroy() { destroyed++ }
    }

    /** A clock the test moves by hand. */
    private class FakeScheduler : Scheduler {
        var now = 0L
        private class Job(val at: Long, val task: () -> Unit) { var cancelled = false }
        private val jobs = ArrayList<Job>()
        override fun postDelayed(delayMs: Long, task: () -> Unit): Scheduler.Cancellable { val j = Job(now + delayMs, task); jobs += j; return Scheduler.Cancellable { j.cancelled = true } }
        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val next = jobs.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
                now = next.at; jobs.remove(next); next.task()
            }
            now = target
        }
    }

    private val heard = ArrayList<Pair<String, Boolean>>()
    private val backend = FakeBackend()
    private val clock = FakeScheduler()
    private val session = VoiceSession(backend, clock) { t, f -> heard += t to f }

    @Test fun aNormalAttemptGoesStartingListeningFinalIdle() {
        session.start(VoiceLang.TELUGU)
        assertEquals(VoiceState.Starting, session.state.value); assertEquals(VoiceLang.TELUGU, backend.lastLang)
        backend.listener!!.onReady(); assertEquals(VoiceState.Listening(""), session.state.value)
        backend.listener!!.onPartial("hostel"); assertEquals(VoiceState.Listening("hostel"), session.state.value)
        backend.listener!!.onFinal("hostel fee"); assertEquals(VoiceState.Idle, session.state.value)
        assertEquals(listOf("hostel" to false, "hostel fee" to true), heard)
    }

    @Test fun onlyOneAttemptAtATime() {
        session.start(VoiceLang.ENGLISH); session.start(VoiceLang.HINDI)
        assertEquals(1, backend.started)
    }

    @Test fun stopWaitsForTheFinalText() {
        session.start(VoiceLang.ENGLISH); backend.listener!!.onReady(); backend.listener!!.onPartial("how much")
        session.stop()
        assertEquals(VoiceState.Processing("how much"), session.state.value); assertEquals(1, backend.stopped)
        backend.listener!!.onFinal("how much was the fee")
        assertEquals(VoiceState.Idle, session.state.value); assertEquals("how much was the fee" to true, heard.last())
    }

    @Test fun errorsAreReportedAndDismissable() {
        session.start(VoiceLang.ENGLISH); backend.listener!!.onError(VoiceError.NETWORK)
        assertEquals(VoiceState.Failed(VoiceError.NETWORK), session.state.value)
        assertTrue(heard.isEmpty())
        session.dismissError(); assertEquals(VoiceState.Idle, session.state.value)
        session.start(VoiceLang.ENGLISH); assertEquals(2, backend.started) // can try again
    }

    @Test fun aBlankFinalResultIsTreatedAsNothingHeard() {
        session.start(VoiceLang.ENGLISH); backend.listener!!.onFinal("   ")
        assertEquals(VoiceState.Failed(VoiceError.NO_MATCH), session.state.value); assertTrue(heard.isEmpty())
    }

    @Test fun cancelAbandonsTheAttemptAndLateResultsAreIgnored() {
        session.start(VoiceLang.ENGLISH); val old = backend.listener!!
        session.cancel()
        assertEquals(VoiceState.Idle, session.state.value); assertEquals(1, backend.cancelled)
        old.onPartial("late"); old.onFinal("late text"); old.onError(VoiceError.CLIENT)
        assertEquals(VoiceState.Idle, session.state.value); assertTrue(heard.isEmpty())
    }

    @Test fun aNewAttemptIgnoresTheOldOnesCallbacks() {
        session.start(VoiceLang.ENGLISH); val old = backend.listener!!
        session.cancel(); session.start(VoiceLang.HINDI)
        old.onFinal("from the first attempt")
        assertEquals(VoiceState.Starting, session.state.value); assertTrue(heard.isEmpty())
    }

    @Test fun stopOnlyAppliesWhileListening() {
        session.stop(); assertEquals(0, backend.stopped)
        session.start(VoiceLang.ENGLISH); session.stop(); assertEquals(0, backend.stopped) // still Starting
    }

    @Test fun everyErrorHasAUserFacingMessage() {
        for (e in VoiceError.entries) assertTrue(e.name, e.message.isNotBlank())
        assertTrue(VoiceError.NETWORK.message.contains("offline pack"))
    }

    // ---- watchdogs: the recognizer may never call back ----

    @Test fun aServiceThatNeverStartsFailsInsteadOfHanging() {
        session.start(VoiceLang.ENGLISH)
        clock.advance(VoiceSession.START_TIMEOUT_MS - 1); assertEquals(VoiceState.Starting, session.state.value)
        clock.advance(1)
        assertEquals(VoiceState.Failed(VoiceError.NO_RESPONSE), session.state.value); assertEquals(1, backend.cancelled)
    }

    @Test fun aServiceThatStaysSilentIsStoppedThenGivenUp() { // what the real recognizer did with a missing language pack
        session.start(VoiceLang.ENGLISH); backend.listener!!.onReady()
        clock.advance(VoiceSession.LISTEN_LIMIT_MS)
        assertEquals(VoiceState.Processing(""), session.state.value); assertEquals(1, backend.stopped)
        clock.advance(VoiceSession.FINAL_TIMEOUT_MS)
        assertEquals(VoiceState.Failed(VoiceError.NO_RESPONSE), session.state.value)
    }

    @Test fun pressingStopOnADeadServiceFailsAfterTheFinalTimeout() {
        session.start(VoiceLang.ENGLISH); backend.listener!!.onReady(); session.stop()
        clock.advance(VoiceSession.FINAL_TIMEOUT_MS)
        assertEquals(VoiceState.Failed(VoiceError.NO_RESPONSE), session.state.value)
    }

    @Test fun talkingKeepsTheListenLimitFromFiring() {
        session.start(VoiceLang.ENGLISH); backend.listener!!.onReady()
        repeat(5) { clock.advance(VoiceSession.LISTEN_LIMIT_MS - 1_000); backend.listener!!.onPartial("word $it") }
        assertTrue(session.state.value is VoiceState.Listening); assertEquals(0, backend.stopped)
    }

    @Test fun aRealResultCancelsTheWatchdog() {
        session.start(VoiceLang.ENGLISH); backend.listener!!.onReady(); backend.listener!!.onFinal("hostel fee")
        clock.advance(10 * VoiceSession.LISTEN_LIMIT_MS)
        assertEquals(VoiceState.Idle, session.state.value); assertEquals(0, backend.cancelled)
    }

    @Test fun anErrorCancelsTheWatchdogAndLateCallbacksAfterGivingUpAreIgnored() {
        session.start(VoiceLang.ENGLISH); val first = backend.listener!!
        clock.advance(VoiceSession.START_TIMEOUT_MS)
        first.onReady(); first.onFinal("too late")
        assertEquals(VoiceState.Failed(VoiceError.NO_RESPONSE), session.state.value); assertTrue(heard.isEmpty())
        session.dismissError(); session.start(VoiceLang.HINDI); backend.listener!!.onError(VoiceError.LANGUAGE_UNAVAILABLE)
        clock.advance(60_000)
        assertEquals(VoiceState.Failed(VoiceError.LANGUAGE_UNAVAILABLE), session.state.value)
    }

    @Test fun cancellingStopsTheWatchdog() {
        session.start(VoiceLang.ENGLISH); session.cancel(); clock.advance(60_000)
        assertEquals(VoiceState.Idle, session.state.value)
    }
}
