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
}
