package com.manggome.oneemu.emu

import android.content.pm.ActivityInfo
import com.manggome.oneemu.library.WipiCompat
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GameOrientationTest {
    @Test fun choicesMapToActivityOrientations() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR, GameOrientation.AUTO.activityOrientation)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, GameOrientation.FOLLOW_SYSTEM.activityOrientation)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, GameOrientation.PORTRAIT.activityOrientation)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, GameOrientation.LANDSCAPE.activityOrientation)
    }

    @Test fun storedKeysRoundTrip() {
        for (o in GameOrientation.entries) assertEquals(o, GameOrientation.fromKey(o.key))
        assertEquals("orientation.42", GameOrientation.key(42).name)
    }

    @Test fun androidSpellingsAndJunk() {
        assertEquals(GameOrientation.PORTRAIT, GameOrientation.fromKey(" Portrait "))
        assertEquals(GameOrientation.PORTRAIT, GameOrientation.fromKey("sensorPortrait"))
        assertEquals(GameOrientation.LANDSCAPE, GameOrientation.fromKey("sensorLandscape"))
        assertEquals(GameOrientation.FOLLOW_SYSTEM, GameOrientation.fromKey("fullUser"))
        assertEquals(GameOrientation.AUTO, GameOrientation.fromKey("sensor"))
        assertNull(GameOrientation.fromKey(null))
        assertNull(GameOrientation.fromKey(""))
        assertNull(GameOrientation.fromKey("sideways"))
    }

    @Test fun defaultIsAutoRotate() {
        val e = GameOrientation.resolve(stored = "", catalogDefault = null)
        assertEquals(GameOrientation.AUTO, e.orientation)
        assertFalse(e.fromCatalog)
    }

    @Test fun catalogDefaultAppliesUntilTheUserPicks() {
        val fromList = GameOrientation.resolve(stored = "", catalogDefault = "portrait")
        assertEquals(GameOrientation.PORTRAIT, fromList.orientation)
        assertTrue(fromList.fromCatalog)

        val picked = GameOrientation.resolve(stored = "auto", catalogDefault = "portrait")
        assertEquals(GameOrientation.AUTO, picked.orientation)
        assertFalse(picked.fromCatalog)
    }

    @Test fun unreadableValuesFallThrough() {
        assertEquals(GameOrientation.LANDSCAPE, GameOrientation.resolve("garbage", "landscape").orientation)
        assertEquals(GameOrientation.AUTO, GameOrientation.resolve(null, "garbage").orientation)
    }

    @Test fun compatEntryReadsOrientationAndToleratesItsAbsence() {
        val db = json.decodeFromString<WipiCompat.Database>(
            """{"schema":1,"games":[{"title":"A","orientation":"portrait","files":[]},{"title":"B","files":[]}]}""",
        )
        assertEquals("portrait", db.games[0].orientation)
        assertNull(db.games[1].orientation)
    }

    @Test fun bundledListFixesNomUpright() {
        val file = listOf(File("../cores/wipi/assets/compat.json"), File("cores/wipi/assets/compat.json")).first { it.isFile }
        val db = json.decodeFromString<WipiCompat.Database>(file.readText())
        val nom = db.games.single { g -> g.files.any { it.sha256 == NOM_J2ME_SHA256 } }
        assertEquals("NOM", nom.title)
        assertEquals(GameOrientation.PORTRAIT, GameOrientation.fromKey(nom.orientation))
        // Every orientation in the list must be one the app understands.
        for (g in db.games) if (g.orientation != null) assertTrue(g.title, GameOrientation.fromKey(g.orientation) != null)
    }

    private companion object {
        /** Same settings as [WipiCompat] reads the list with. */
        val json = Json { ignoreUnknownKeys = true }
        const val NOM_J2ME_SHA256 = "bd92119dfdb2e31655f0ba9fe6f82a8bbb88c3cf0b9792b426ab9ddee6d995b2"
    }
}
