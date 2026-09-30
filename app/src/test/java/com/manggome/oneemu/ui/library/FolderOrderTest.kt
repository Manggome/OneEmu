package com.manggome.oneemu.ui.library

import com.manggome.oneemu.data.db.GameEntity
import com.manggome.oneemu.model.SystemId
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Collator
import java.util.Locale

class FolderOrderTest {
    private val collator = Collator.getInstance(Locale.KOREAN)

    private fun game(system: SystemId, played: Long = 0) = GameEntity(path = "/x", title = "t", system = system.id, lastPlayedAt = played)
    private fun section(system: SystemId, played: Long = 0) = LibrarySection(system, listOf(game(system, played)), false)
    private val favorites = LibrarySection(null, listOf(game(SystemId.PSX)), false, favorites = true)

    private val nes = section(SystemId.NES)
    private val gba = section(SystemId.GBA, played = 300)
    private val psx = section(SystemId.PSX, played = 500)
    private val ps2 = section(SystemId.PS2)
    private val all = listOf(favorites, nes, gba, psx, ps2)

    private fun keys(list: List<LibrarySection>) = list.map { it.key }

    @Test fun nameOrderKeepsFavoritesFirst() {
        assertEquals(listOf("favorites", "gba", "nes", "psx", "ps2"), keys(FolderOrder.sort(all, FolderSort.NAME, emptyList(), collator)))
    }

    @Test fun recentOrderPutsUnplayedLastInDefaultOrder() {
        assertEquals(listOf("favorites", "psx", "gba", "nes", "ps2"), keys(FolderOrder.sort(all, FolderSort.RECENT, emptyList(), collator)))
    }

    @Test fun customOrderAppendsUnlistedFolders() {
        assertEquals(listOf("favorites", "ps2", "nes", "gba", "psx"), keys(FolderOrder.sort(all, FolderSort.CUSTOM, listOf("ps2", "nes"), collator)))
    }
}
