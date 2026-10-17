package com.manggome.oneemu.library

import com.manggome.oneemu.data.db.GameEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.Normalizer

/** How a picture in OneEmu/boxart/ finds its game, and when it may replace the game's thumbnail. */
class LocalBoxArtTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var boxArt: File
    private lateinit var thumbs: File

    @Before
    fun setUp() {
        boxArt = tmp.newFolder("boxart")
        thumbs = tmp.newFolder("thumbnails")
    }

    private fun picture(name: String, dir: File = boxArt): File = File(dir, name).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
    private fun nfd(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
    private fun game(path: String, thumbnail: String? = null, id: Long = 7) =
        GameEntity(id = id, path = path, title = "t", system = "gba", thumbnail = thumbnail)

    @Test
    fun `picture named like the file without its extension matches`() {
        val png = picture("포켓몬스터 에메랄드(K).png")
        val index = LocalBoxArt.index(boxArt)
        assertEquals(png, LocalBoxArt.match(index, "/sdcard/roms/gba/포켓몬스터 에메랄드(K).gba"))
    }

    @Test
    fun `picture named like the whole file name matches`() {
        val png = picture("이노티아 연대기 2 (KTF).zip.png")
        val index = LocalBoxArt.index(boxArt)
        assertEquals(png, LocalBoxArt.match(index, "/sdcard/roms/wipi/이노티아 연대기 2 (KTF).zip"))
    }

    @Test
    fun `decomposed and composed Korean names match each other`() {
        // A picture pushed from a Mac arrives NFD; the game's path is NFC.
        val png = picture(nfd("이노티아 연대기 2 (KTF).png"))
        val index = LocalBoxArt.index(boxArt)
        assertEquals(png, LocalBoxArt.match(index, "/sdcard/roms/이노티아 연대기 2 (KTF).zip"))
        // And the other way round.
        val png2 = picture("포켓몬스터 에메랄드(K).png")
        assertEquals(png2, LocalBoxArt.match(LocalBoxArt.index(boxArt), nfd("/sdcard/roms/포켓몬스터 에메랄드(K).gba")))
    }

    @Test
    fun `case is ignored on both the name and the extension`() {
        val jpg = picture("Sonic The Hedgehog 3.JPG")
        val index = LocalBoxArt.index(boxArt)
        assertEquals(jpg, LocalBoxArt.match(index, "/roms/sonic the hedgehog 3.md"))
    }

    @Test
    fun `only picture extensions count`() {
        for (ext in listOf("png", "jpg", "jpeg", "webp")) picture("game_$ext.$ext")
        picture("notes.txt")
        picture("anim.gif")
        picture("._game_png.png") // a Mac's resource-fork twin
        val index = LocalBoxArt.index(boxArt)
        for (ext in listOf("png", "jpg", "jpeg", "webp")) assertEquals("game_$ext.$ext", LocalBoxArt.match(index, "/r/game_$ext.gba")?.name)
        assertNull(LocalBoxArt.match(index, "/r/notes.gba"))
        assertNull(LocalBoxArt.match(index, "/r/anim.gba"))
        assertEquals(4, index.size)
    }

    @Test
    fun `no partial or title matches`() {
        picture("Mario.png")
        val index = LocalBoxArt.index(boxArt)
        assertNull(LocalBoxArt.match(index, "/r/Super Mario.gba"))
        assertNull(LocalBoxArt.match(index, "/r/Mario Kart.gba"))
    }

    @Test
    fun `subfolders one level down are searched, the top folder wins`() {
        val nested = picture("Metroid.png", File(boxArt, "GBA"))
        assertEquals(nested, LocalBoxArt.match(LocalBoxArt.index(boxArt), "/r/Metroid.gba"))
        val top = picture("Metroid.png")
        assertEquals(top, LocalBoxArt.match(LocalBoxArt.index(boxArt), "/r/Metroid.gba"))
        picture("Deep.png", File(boxArt, "a/b"))
        assertNull(LocalBoxArt.match(LocalBoxArt.index(boxArt), "/r/Deep.gba"))
    }

    @Test
    fun `missing folder gives an empty index`() {
        assertTrue(LocalBoxArt.index(File(tmp.root, "nope")).isEmpty())
    }

    @Test
    fun `a game with no thumbnail takes the folder's picture`() {
        val png = picture("Metroid.png")
        val index = LocalBoxArt.index(boxArt)
        assertEquals(png.absolutePath, LocalBoxArt.thumbnailFor(game("/r/Metroid.gba"), index, boxArt, thumbs))
        assertNull(LocalBoxArt.thumbnailFor(game("/r/Other.gba"), index, boxArt, thumbs))
    }

    @Test
    fun `a thumbnail the user chose is kept`() {
        picture("Metroid.png")
        val index = LocalBoxArt.index(boxArt)
        // Picked from the gallery or the box art picker.
        val chosen = File(thumbs, "7_1700000000000.jpg").absolutePath
        assertEquals(chosen, LocalBoxArt.thumbnailFor(game("/r/Metroid.gba", chosen), index, boxArt, thumbs))
        val uri = "content://media/external/images/media/12"
        assertEquals(uri, LocalBoxArt.thumbnailFor(game("/r/Metroid.gba", uri), index, boxArt, thumbs))
    }

    @Test
    fun `box art the server pass found is replaced, but kept without a folder picture`() {
        val png = picture("Metroid.png")
        val index = LocalBoxArt.index(boxArt)
        val auto = File(thumbs, BoxArtFetcher.thumbnailName(7, auto = true, now = 1)).absolutePath
        assertEquals(png.absolutePath, LocalBoxArt.thumbnailFor(game("/r/Metroid.gba", auto), index, boxArt, thumbs))
        assertEquals(auto, LocalBoxArt.thumbnailFor(game("/r/Other.gba", auto), index, boxArt, thumbs))
    }

    @Test
    fun `a folder picture that went away is dropped, one that was renamed is followed`() {
        val old = File(boxArt, "Metroid.png").absolutePath
        assertNull(LocalBoxArt.thumbnailFor(game("/r/Metroid.gba", old), emptyMap(), boxArt, thumbs))
        val nested = picture("Metroid.webp", File(boxArt, "GBA"))
        assertEquals(nested.absolutePath, LocalBoxArt.thumbnailFor(game("/r/Metroid.gba", old), LocalBoxArt.index(boxArt), boxArt, thumbs))
    }

    @Test
    fun `auto-fetched marker belongs to its own game and folder`() {
        val name = BoxArtFetcher.thumbnailName(7, auto = true, now = 5)
        assertTrue(BoxArtFetcher.isAutoFetched(File(thumbs, name).absolutePath, 7, thumbs))
        assertFalse(BoxArtFetcher.isAutoFetched(File(thumbs, name).absolutePath, 77, thumbs))
        assertFalse(BoxArtFetcher.isAutoFetched(File(boxArt, name).absolutePath, 7, thumbs))
        assertFalse(BoxArtFetcher.isAutoFetched(File(thumbs, BoxArtFetcher.thumbnailName(7, auto = false, now = 5)).absolutePath, 7, thumbs))
        // Every downloaded thumbnail still starts with "<id>_", which the library's cleanup relies on.
        assertTrue(name.startsWith("7_"))
    }

    @Test
    fun `inside means the folder or one subfolder`() {
        assertTrue(LocalBoxArt.isInside(File(boxArt, "a.png").absolutePath, boxArt))
        assertTrue(LocalBoxArt.isInside(File(boxArt, "GBA/a.png").absolutePath, boxArt))
        assertFalse(LocalBoxArt.isInside(File(thumbs, "a.png").absolutePath, boxArt))
        assertFalse(LocalBoxArt.isInside("content://x/boxart/a.png", boxArt))
    }
}
