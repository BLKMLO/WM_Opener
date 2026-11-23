package com.wmopener

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Génère des captures de l'interface (./gradlew recordRoborazziDebug).
 * Les données d'historique sont fictives et servent uniquement au rendu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, Configuration.Builder().build())
        History.clear(ctx)
        val now = System.currentTimeMillis()
        History.add(ctx, Entry(now - 26 * 3600_000L, "auto", 1, listOf(Card("C", "Origami"), Card("PC", "Hibou"), Card("R", "Colisée"))))
        History.add(ctx, Entry(now - 3 * 3600_000L, "auto", 2, listOf(
            Card("C", "Abeille"), Card("C", "Chat"), Card("PC", "Pieuvre"), Card("R", "Stonehenge"),
            Card("SR", "Marie Curie"), Card("UR", "Titanic"))))
        History.add(ctx, Entry(now - 20 * 60_000L, "manuel", 1, emptyList()))
        History.add(ctx, Entry(now - 5 * 60_000L, "auto", 1, listOf(Card("L", "Soleil"), Card("C", "Origami"), Card("R", "Pompéi"))))
        Prefs(ctx).apply { autoEnabled = true; lastRunAt = now - 5 * 60_000L; lastRunSummary = "1 paquet ouvert." }
    }

    private fun shot(tab: String, name: String) {
        val a = Robolectric.buildActivity(MainActivity::class.java,
            android.content.Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, tab)).setup().get()
        a.window.decorView.captureRoboImage("../docs/screenshots/$name.png")
    }

    @Test fun auto() = shot(MainActivity.TAB_AUTO, "auto")
    @Test fun history() = shot(MainActivity.TAB_HISTORY, "historique")
}
