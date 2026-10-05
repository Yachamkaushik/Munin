package com.munin.app.launch

import android.content.ComponentName
import android.content.pm.PackageManager
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.munin.app.incoming.IncomingParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tile and widget on a real system: declared, inflatable, and pointing at the search screen. */
class SearchLaunchTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun theLaunchIntentOpensSearchInMunin() {
        val i = SearchLaunch.intent(ctx)
        assertEquals(IncomingParser.ACTION_OPEN_SEARCH, i.action)
        assertEquals("com.munin.app", i.component?.packageName)
    }

    @Test fun theWidgetLayoutInflatesAndHasAClickTarget() {
        val v = InstrumentationRegistry.getInstrumentation().targetContext.let { c ->
            SearchWidgetProvider.views(c).apply(c, FrameLayout(c))
        }
        assertNotNull(v.findViewById(com.munin.app.R.id.widget_root))
    }

    @Test fun theTileServiceAndWidgetReceiverAreDeclared() {
        val pm = ctx.packageManager
        val tile = pm.getServiceInfo(ComponentName(ctx, SearchTileService::class.java), 0)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", tile.permission)
        assertTrue(pm.getReceiverInfo(ComponentName(ctx, SearchWidgetProvider::class.java), PackageManager.GET_META_DATA).metaData.containsKey("android.appwidget.provider"))
    }
}
