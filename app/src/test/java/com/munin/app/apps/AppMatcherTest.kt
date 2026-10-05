package com.munin.app.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneticKeyTest {
    @Test fun sameNameInThreeScriptsHasTheSameKey() {
        assertEquals("vtsp", PhoneticKey.of("whatsapp")); assertEquals("vtsp", PhoneticKey.of("వాట్సాప్")); assertEquals("vtsp", PhoneticKey.of("वॉट्सऐप"))
    }

    @Test fun moreNames() {
        assertEquals("krm", PhoneticKey.of("Chrome")); assertEquals("krm", PhoneticKey.of("క్రోమ్")); assertEquals("krm", PhoneticKey.of("क्रोम"))
        assertEquals("mjn", PhoneticKey.of("Amazon")); assertEquals("mjn", PhoneticKey.of("అమెజాన్")); assertEquals("stngs", PhoneticKey.of("Settings")); assertEquals("stngs", PhoneticKey.of("సెట్టింగ్స్"))
        assertEquals("kmr", PhoneticKey.of("Camera")); assertEquals("kmr", PhoneticKey.of("కెమెరా")); assertEquals("kmr", PhoneticKey.of("कैमरा"))
    }

    @Test fun noLettersMeansNoKey() { assertEquals("", PhoneticKey.of("123 !?")); assertEquals("", PhoneticKey.of("")) }
}

class AppMatcherTest {
    private fun app(label: String, pkg: String) = AppEntry(label, pkg, "$pkg/.Main")
    private val apps = listOf(
        app("WhatsApp", "com.whatsapp"), app("Chrome", "com.android.chrome"), app("Settings", "com.android.settings"), app("YouTube", "com.google.android.youtube"),
        app("Play Store", "com.android.vending"), app("Amazon", "in.amazon.mShop.android.shopping"), app("Phone", "com.google.android.dialer"), app("Camera", "com.android.camera"),
        app("Calculator", "com.google.android.calculator"), app("Contacts", "com.google.android.contacts"), app("Gmail", "com.google.android.gm"), app("Google Pay", "com.google.android.apps.nbu.paisa.user"),
        app("PhonePe", "com.phonepe.app"), app("Paytm", "net.one97.paytm"), app("Instagram", "com.instagram.android"), app("Hotstar", "in.startv.hotstar"), app("Swiggy", "in.swiggy.android"),
        app("Zomato", "com.application.zomato"), app("Maps", "com.google.android.apps.maps"), app("Messages", "com.google.android.apps.messaging"), app("Photos", "com.google.android.apps.photos"),
        app("Files", "com.google.android.apps.nbu.files"), app("Clock", "com.google.android.deskclock"), app("Calendar", "com.google.android.calendar"),
    )
    private fun top(q: String) = AppMatcher.search(q, apps).firstOrNull()?.app?.label

    @Test fun exactAndPartialNames() { assertEquals("WhatsApp", top("whatsapp")); assertEquals("WhatsApp", top("WhatsApp")); assertEquals("WhatsApp", top("whats")); assertEquals("Play Store", top("play")); assertEquals("Google Pay", top("pay")) }

    @Test fun typos() {
        assertEquals("WhatsApp", top("whatsap")); assertEquals("WhatsApp", top("whatsaap")); assertEquals("WhatsApp", top("whastapp")); assertEquals("Instagram", top("instagarm")); assertEquals("Calculator", top("calculater"))
        assertEquals("Chrome", top("crome")); assertEquals("Settings", top("setings"))
    }

    @Test fun teluguSpellings() {
        assertEquals("WhatsApp", top("వాట్సాప్")); assertEquals("Chrome", top("క్రోమ్")); assertEquals("Amazon", top("అమెజాన్")); assertEquals("Settings", top("సెట్టింగ్స్")); assertEquals("Camera", top("కెమెరా"))
        assertEquals("Calculator", top("కాలిక్యులేటర్")); assertEquals("YouTube", top("యూట్యూబ్")); assertEquals("Play Store", top("ప్లే స్టోర్")); assertEquals("Instagram", top("ఇన్స్టాగ్రామ్"))
    }

    @Test fun hindiSpellings() { assertEquals("WhatsApp", top("वॉट्सऐप")); assertEquals("Chrome", top("क्रोम")); assertEquals("Camera", top("कैमरा")); assertEquals("Settings", top("सेटिंग्स")); assertEquals("Gmail", top("जीमेल")) }

    @Test fun wordsAroundTheNameAreIgnored() { assertEquals("WhatsApp", top("open whatsapp")); assertEquals("WhatsApp", top("whatsapp app")); assertEquals("WhatsApp", top("వాట్సాప్ యాప్")); assertEquals("Chrome", top("launch chrome")) }

    @Test fun shortNamesPeopleType() { assertEquals("Google Pay", top("gpay")); assertEquals("YouTube", top("yt")); assertEquals("Instagram", top("insta")) }

    @Test fun orderingPutsTheBestMatchFirst() {
        val r = AppMatcher.search("pho", apps)
        assertEquals(listOf("Phone", "PhonePe", "Photos"), r.map { it.app.label }.sorted().filter { it in setOf("Phone", "PhonePe", "Photos") })
        assertTrue(r.first().score >= r.last().score)
    }

    @Test fun ordinaryFileSearchesMatchNoApps() {
        for (q in listOf("hostel fee receipt", "how much was the hostel fee", "electricity bill due", "flight to Visakhapatnam", "హాస్టల్ ఫీజు రసీదు", "छात्रावास शुल्क रसीद", "45000", "98765 43210",
            "payment to Lakshmi Tiffins", "organic chemistry notes", "x", "", "   ")) assertEquals(q, emptyList<AppMatch>(), AppMatcher.search(q, apps))
    }

    @Test fun anAppWithALocalisedLabelIsStillFoundByItsPackageName() {
        val list = listOf(app("వాట్సాప్ బిజినెస్", "com.whatsapp.w4b"), app("Contacts", "com.google.android.contacts"))
        assertEquals("వాట్సాప్ బిజినెస్", AppMatcher.search("whatsapp", list).first().app.label)
    }

    @Test fun editDistanceCountsASwapAsOneSlip() { assertEquals(1, AppMatcher.distance("whatsapp", "whatsapp".replace("ts", "st"))); assertEquals(0, AppMatcher.distance("a", "a")); assertEquals(3, AppMatcher.distance("abc", "xyz")) }

    @Test fun voicingSwapsCostHalfASlip() {
        assertEquals(0.5, AppMatcher.soundDistance("jml", "gml"), 1e-9); assertEquals(1.0, AppMatcher.soundDistance("xml", "gml"), 1e-9); assertEquals(0.0, AppMatcher.soundDistance("krm", "krm"), 1e-9)
    }

    @Test fun limitsTheNumberOfResults() { assertTrue(AppMatcher.search("a", apps).isEmpty()); assertTrue(AppMatcher.search("google", apps, limit = 2).size <= 2) }
}
