package com.manggome.oneemu.emu

import com.manggome.oneemu.emu.PerGameOptions.DeinterlaceWord
import org.junit.Assert.assertEquals
import org.junit.Test

class PerGameOptionsTest {
    @Test fun deinterlaceIsPerGame() {
        assert(PerGameOptions.isPerGame("armsx2_deinterlacing"))
        assert(!PerGameOptions.isPerGame("armsx2_aspect_ratio"))
    }

    @Test fun armsx2ModesReadAsWordsAndField() {
        assertEquals(DeinterlaceWord.AUTO to "", PerGameOptions.deinterlaceWord("Automatic"))
        assertEquals(DeinterlaceWord.OFF to "", PerGameOptions.deinterlaceWord("Off"))
        assertEquals(DeinterlaceWord.BLEND to "TFF", PerGameOptions.deinterlaceWord("Blend TFF"))
        assertEquals(DeinterlaceWord.ADAPTIVE to "BFF", PerGameOptions.deinterlaceWord("Adaptive BFF"))
        assertEquals(DeinterlaceWord.ON to "", PerGameOptions.deinterlaceWord("enabled"))
    }
}
