package com.manggome.oneemu.library

import com.manggome.oneemu.library.RomInfo.DiscPlatform
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * GameCube disc or Wii disc. Getting this wrong hands the player the wrong controller, which for a Wii
 * game means a "connect the Nunchuk" screen it never gets past.
 */
class DiscPlatformTest {
    @get:Rule val tmp = TemporaryFolder()

    private val WII = byteArrayOf(0x5D, 0x1C.toByte(), 0x9E.toByte(), 0xA3.toByte())
    private val GC = byteArrayOf(0xC2.toByte(), 0x33, 0x9F.toByte(), 0x3D)

    /** A 0x40-byte disc header with [magic] at [at]; the rest is the game id and title, which we ignore. */
    private fun discHeader(magic: ByteArray, at: Int): ByteArray =
        ByteArray(0x40).also { magic.copyInto(it, at) }

    private fun file(name: String, bytes: ByteArray): File = tmp.newFile(name).also { it.writeBytes(bytes) }

    private fun ByteArray.at(offset: Int, part: ByteArray): ByteArray = also { part.copyInto(it, offset) }

    @Test fun `raw Wii iso`() =
        assertEquals(DiscPlatform.WII, RomInfo.discPlatform(file("a.iso", discHeader(WII, 0x18))))

    @Test fun `raw GameCube iso`() =
        assertEquals(DiscPlatform.GAMECUBE, RomInfo.discPlatform(file("b.iso", discHeader(GC, 0x1C))))

    @Test fun `wbfs carries the disc header one HD sector in`() {
        val bytes = ByteArray(0x400)
            .at(0, "WBFS".toByteArray(Charsets.US_ASCII))
        bytes[8] = 9 // hd_sector_shift: 512-byte sectors
        bytes.at(0x200, discHeader(WII, 0x18))
        assertEquals(DiscPlatform.WII, RomInfo.discPlatform(file("c.wbfs", bytes)))
    }

    @Test fun `a wbfs whose header is unreadable is still Wii, because nothing else is stored that way`() {
        val bytes = ByteArray(0x20).at(0, "WBFS".toByteArray(Charsets.US_ASCII))
        bytes[8] = 9
        assertEquals(DiscPlatform.WII, RomInfo.discPlatform(file("d.wbfs", bytes)))
    }

    @Test fun `rvz keeps the first 0x80 disc bytes uncompressed in header 2`() {
        // WIAHeader1 is 0x48 bytes; header 2 opens with disc_type and then the disc header itself at 0x58.
        val bytes = ByteArray(0x200)
            .at(0, byteArrayOf('R'.code.toByte(), 'V'.code.toByte(), 'Z'.code.toByte(), 1))
            .at(0x58, discHeader(WII, 0x18))
        assertEquals(DiscPlatform.WII, RomInfo.discPlatform(file("e.rvz", bytes)))
    }

    @Test fun `wia falls back to disc_type when the copied header is blank`() {
        val bytes = ByteArray(0x200)
            .at(0, byteArrayOf('W'.code.toByte(), 'I'.code.toByte(), 'A'.code.toByte(), 1))
            .at(0x48, byteArrayOf(0, 0, 0, 2)) // disc_type: 2 = Wii
        assertEquals(DiscPlatform.WII, RomInfo.discPlatform(file("f.wia", bytes)))
        val gc = ByteArray(0x200)
            .at(0, byteArrayOf('W'.code.toByte(), 'I'.code.toByte(), 'A'.code.toByte(), 1))
            .at(0x48, byteArrayOf(0, 0, 0, 1))
        assertEquals(DiscPlatform.GAMECUBE, RomInfo.discPlatform(file("g.wia", gc)))
    }

    @Test fun `gcz says which machine it was made from in its sub_type`() {
        fun gcz(subType: Byte) = ByteArray(0x40)
            .at(0, byteArrayOf(0x0B, 0xB1.toByte(), 0x0B, 0xB1.toByte())) // 0xB10BB10B little-endian
            .at(4, byteArrayOf(subType, 0, 0, 0))
        assertEquals(DiscPlatform.WII, RomInfo.discPlatform(file("h.gcz", gcz(1))))
        assertEquals(DiscPlatform.GAMECUBE, RomInfo.discPlatform(file("i.gcz", gcz(0))))
    }

    @Test fun `ciso puts block 0 after a fixed 0x8000 index`() {
        val bytes = ByteArray(0x8100)
            .at(0, "CISO".toByteArray(Charsets.US_ASCII))
            .at(0x8000, discHeader(WII, 0x18))
        assertEquals(DiscPlatform.WII, RomInfo.discPlatform(file("j.ciso", bytes)))
    }

    @Test fun `a wad is a Wii channel`() =
        assertEquals(DiscPlatform.WII, RomInfo.discPlatform(file("k.wad", ByteArray(0x100))))

    @Test fun `an image we cannot read says so rather than guessing`() {
        assertEquals(DiscPlatform.UNKNOWN, RomInfo.discPlatform(file("l.iso", ByteArray(0x1000))))
        assertEquals(DiscPlatform.UNKNOWN, RomInfo.discPlatform(File(tmp.root, "missing.iso")))
    }

    @Test fun `an m3u is judged by the disc it points at`() {
        val disc = file("disc1.iso", discHeader(WII, 0x18))
        val m3u = file("game.m3u", "${disc.name}\n".toByteArray(Charsets.UTF_8))
        assertEquals(DiscPlatform.WII, RomInfo.discPlatform(m3u))
    }
}
