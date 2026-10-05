package com.munin.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Thin, round-capped line icons in the spirit of SF Symbols, drawn here (no icon library, no Apple assets). Tinted by the caller. */
object IosIcons {
    private fun line(name: String, vararg paths: String, fillPaths: List<String> = emptyList(), width: Float = 1.8f): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (p in paths) b.addPath(PathParser().parsePathString(p).toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        for (p in fillPaths) b.addPath(PathParser().parsePathString(p).toNodes(), fill = SolidColor(Color.Black))
        return b.build()
    }

    val Search = line("search", "M10.5 4a6.5 6.5 0 1 0 0 13a6.5 6.5 0 0 0 0-13z", "M15.4 15.4L20.5 20.5")
    val Photos = line("photos", "M5.5 4.5h13a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2v-11a2 2 0 0 1 2-2z", "M3.5 16l4.6-4.6a1.5 1.5 0 0 1 2.1 0L15 16.2", "M13.5 14.7l1.7-1.7a1.5 1.5 0 0 1 2.1 0l3.2 3.2", "M8.2 8.6a1.1 1.1 0 1 0 0.01 0z")
    val Ledger = line("ledger", "M6 20V11", "M12 20V4", "M18 20v-6", "M3.5 20.5h17")
    val Chevron = line("chevron", "M9 5.5l6.5 6.5L9 18.5", width = 2.2f)
    val ChevronBack = line("back", "M15 5.5L8.5 12l6.5 6.5", width = 2.2f)
    val Mic = line("mic", "M12 3.5a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0v-5a3 3 0 0 1 3-3z", "M5.5 11.5a6.5 6.5 0 0 0 13 0", "M12 18v3")
    val Stop = line("stop", "M7 7h10v10H7z", width = 2.2f)
    val Globe = line("globe", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18z", "M3.5 12h17", "M12 3c2.6 2.5 3.8 5.5 3.8 9s-1.2 6.5-3.8 9c-2.6-2.5-3.8-5.5-3.8-9S9.4 5.5 12 3z")
    val Clear = line("clear", "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18z", "M9 9l6 6", "M15 9l-6 6")
    val Calculator = line("calc", "M6.5 3.5h11a1.5 1.5 0 0 1 1.5 1.5v14a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19V5a1.5 1.5 0 0 1 1.5-1.5z", "M8 7.5h8", "M8.5 12h1", "M12 12h1", "M15.5 12h1", "M8.5 16h1", "M12 16h1", "M15.5 16h1")
    val Gear = line("gear", "M12 9a3 3 0 1 0 0 6a3 3 0 0 0 0-6z", "M12 3.5v2.2", "M12 18.3v2.2", "M3.5 12h2.2", "M18.3 12h2.2", "M6 6l1.6 1.6", "M16.4 16.4L18 18", "M6 18l1.6-1.6", "M16.4 7.6L18 6")
    val Person = line("person", "M12 4a4 4 0 1 0 0 8a4 4 0 0 0 0-8z", "M4.5 20.5c.6-3.6 3.6-5.5 7.5-5.5s6.9 1.9 7.5 5.5")
    val Bell = line("bell", "M6.5 16.5v-5a5.5 5.5 0 0 1 11 0v5l1.5 2h-14z", "M10 21h4")
    val Clock = line("clock", "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 0 0 0-17z", "M12 7.5V12l3 2")
    val Receipt = line("receipt", "M6 3.5h12v17l-2-1.4-2 1.4-2-1.4-2 1.4-2-1.4-2 1.4z", "M9 8.5h6", "M9 12h6", "M9 15.5h3")
    val Calendar = line("calendar", "M5.5 5.5h13a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2v-11a2 2 0 0 1 2-2z", "M3.5 10h17", "M8 3.5v3.5", "M16 3.5v3.5", "M8 14h2", "M14 14h2")
    val Lock = line("lock", "M6.5 10.5h11a1.5 1.5 0 0 1 1.5 1.5v6.5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 18.5V12a1.5 1.5 0 0 1 1.5-1.5z", "M8 10.5V8a4 4 0 0 1 8 0v2.5")
    val Apps = line("apps", "M5 4.5h5v5H5z", "M14 4.5h5v5h-5z", "M5 13.5h5v5H5z", "M14 13.5h5v5h-5z")
}
