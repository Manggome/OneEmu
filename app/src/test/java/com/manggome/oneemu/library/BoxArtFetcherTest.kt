package com.manggome.oneemu.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The name matching that decides whether a game finds its box art. The download itself is not
 * exercised here; these are the rules that turn our titles into the server's file names.
 */
class BoxArtFetcherTest {
    @Test
    fun `case and region tags do not change the normalized name`() {
        val ours = BoxArtFetcher.normalize("Sonic the Hedgehog 3")
        assertEquals(ours, BoxArtFetcher.normalize("Sonic The Hedgehog 3 (USA)"))
        assertEquals(ours, BoxArtFetcher.normalize("Sonic  The   Hedgehog 3 (Japan, Korea) (Ja)"))
    }

    @Test
    fun `punctuation between our title and the server's spelling is ignored`() {
        assertEquals(
            BoxArtFetcher.normalize("Street Fighter II: The World Warrior"),
            BoxArtFetcher.normalize("Street Fighter II - The World Warrior (World 910522)"),
        )
    }

    @Test
    fun `a title that is only a tag normalizes to nothing rather than matching everything`() {
        assertEquals("", BoxArtFetcher.normalize("(USA)"))
    }

    @Test
    fun `the region the game itself names wins over the western default`() {
        val korea = "Ace Combat Zero - The Belkan War (Korea)"
        val usa = "Ace Combat Zero - The Belkan War (USA)"
        // With nothing to go on, the American release is the safe pick.
        assertEquals(usa, BoxArtFetcher.best(listOf(korea, usa)))
        // A Korean dump should not be given the American cover.
        assertEquals(korea, BoxArtFetcher.best(listOf(korea, usa), setOf("korea")))
        assertEquals(usa, BoxArtFetcher.best(listOf(korea, usa), setOf("usa")))
    }

    @Test
    fun `region tags are read out of a release name, language and version tags are not`() {
        assertEquals(setOf("japan", "korea"), BoxArtFetcher.regionsOf("Bad Omen (Japan, Korea) (En)"))
        assertEquals(setOf("usa"), BoxArtFetcher.regionsOf("Ratchet _ Clank (USA) (v1.00)"))
        assertEquals(emptySet<String>(), BoxArtFetcher.regionsOf("Sonic The Hedgehog 3"))
        // "En,Fr,De" are languages, not places.
        assertEquals(setOf("europe"), BoxArtFetcher.regionsOf("Ratchet _ Clank (Europe) (En,Fr,De,Es,It)"))
    }

    @Test
    fun `a game with no region tag still gets the usual order`() {
        val names = listOf("Game (Japan)", "Game (USA)", "Game (Korea)")
        assertEquals("Game (USA)", BoxArtFetcher.best(names, BoxArtFetcher.regionsOf("Game.iso")))
    }

    @Test
    fun `the widest plainest release wins when several files share a name`() {
        val usa = "Sonic The Hedgehog 3 (USA)"
        assertTrue(BoxArtFetcher.score(usa) < BoxArtFetcher.score("Sonic The Hedgehog 3 (Japan, Korea)"))
        assertTrue(BoxArtFetcher.score(usa) < BoxArtFetcher.score("Sonic The Hedgehog 3 (USA) (Sonic Classic Collection)"))
        assertTrue(BoxArtFetcher.score("Game (World)") < BoxArtFetcher.score("Game (Europe)"))
    }

    @Test
    fun `candidates try the plain title first and then the usual regions`() {
        val names = BoxArtFetcher.candidates("Crash Bandicoot", "Crash Bandicoot (USA).bin")
        assertEquals("Crash Bandicoot", names.first())
        assertTrue(names.contains("Crash Bandicoot (USA)"))
        assertTrue(names.contains("Crash Bandicoot (Europe)"))
        // The file name is a source of spellings too, tags and extension removed.
        assertTrue(names.contains("Crash Bandicoot (USA)"))
    }

    @Test
    fun `candidates survive a title that is entirely bracketed`() {
        val names = BoxArtFetcher.candidates("(USA)", "(USA).bin")
        assertEquals(listOf("(USA)"), names)
    }

    @Test
    fun `the picker leads with the exact title, not a longer game that contains it`() {
        val hits = listOf(
            "Sonic 3D Blast (USA, Europe, Korea) (En)",
            "Sonic 3 (Europe)",
            "Sonic 3D Blast ~ Sonic 3D Flickies' Island (USA, Europe)",
        ).map { BoxArtCandidate("Sega - Mega Drive - Genesis", it) }
        val ranked = BoxArtFetcher.rank(hits, BoxArtFetcher.normalize("Sonic 3"), limit = 10)
        assertEquals("Sonic 3 (Europe)", ranked.first().name)
    }

    @Test
    fun `the picker returns at most what it was asked for`() {
        val hits = (1..50).map { BoxArtCandidate("MAME", "Game $it (World)") }
        assertEquals(12, BoxArtFetcher.rank(hits, "nothingmatches", limit = 12).size)
    }

    @Test
    fun `a title the server could never match gives way to the file name`() {
        // The server holds English and romanized names only, so a Korean title is no use as a query.
        assertEquals("Ratchet & Clank", BoxArtFetcher.searchSeed("라쳇 앤 클랭크", "Ratchet & Clank.iso"))
        assertEquals("", BoxArtFetcher.normalize("라쳇 앤 클랭크"))
    }

    @Test
    fun `a usable title is kept as it is`() {
        assertEquals("Sonic 3", BoxArtFetcher.searchSeed("Sonic 3", "g_soni3.zip"))
        // A title with any letters or digits at all is worth trying before the file name.
        assertEquals("Tekken 5", BoxArtFetcher.searchSeed("Tekken 5", "tk5.iso"))
    }

    @Test
    fun `characters the server cannot store become underscores`() {
        assertEquals("Ratchet _ Clank", BoxArtFetcher.sanitize("Ratchet & Clank"))
        assertEquals("Spy vs. Spy_ The Island Caper", BoxArtFetcher.sanitize("Spy vs. Spy: The Island Caper"))
    }

    @Test
    fun `a release name becomes the title the box shows`() {
        assertEquals("Sonic the Hedgehog 3", BoxArtFetcher.releaseTitle("Sonic the Hedgehog 3 (USA)"))
        assertEquals("Pokemon HeartGold Version", BoxArtFetcher.releaseTitle("Pokemon HeartGold Version (Korea) [Rev 1]"))
        // Dots belong to real titles; RomInfo.cleanTitle would cut this one short.
        assertEquals("Super Mario Bros. 3", BoxArtFetcher.releaseTitle("Super Mario Bros. 3 (USA) (Rev 1)"))
        // Nothing to strip, and a name that is only a tag is left alone rather than emptied.
        assertEquals("Tetris", BoxArtFetcher.releaseTitle("Tetris"))
        assertEquals("(USA)", BoxArtFetcher.releaseTitle("(USA)"))
    }
}
