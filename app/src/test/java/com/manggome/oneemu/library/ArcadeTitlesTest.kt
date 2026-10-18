package com.manggome.oneemu.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArcadeTitlesTest {
    private fun titles(korean: String, mame2003plus: String = "", mame: String = "") = ArcadeTitles { path ->
        when (path) {
            ArcadeTitles.KOREAN_TABLE -> korean
            "coreassets/${ArcadeCoreRouter.MAME2003PLUS}/titles.tsv" -> mame2003plus
            "coreassets/${ArcadeCoreRouter.MAME}/titles.tsv" -> mame
            else -> null
        }?.byteInputStream()
    }

    private val english = """
        bublbobl	Bubble Bobble	1986	Taito
        techromn	Tech Romancer (USA 980914)	1998	Capcom
        sf2	Street Fighter II - The World Warrior (World 910522)	1991	Capcom
        sf2ua	Street Fighter II - The World Warrior (USA 910206)	1991	Capcom
        galaxian	Galaxian (Namco set 1)	1979	Namco
    """.trimIndent()

    @Test
    fun `parses names, titles and clone columns`() {
        val map = ArcadeTitles.parseKorean(
            sequenceOf(
                "# comment\tignored",
                "",
                "bublbobl\t보글보글\tbublbobl1 bublboblr",
                "SF2\t스트리트 파이터 2 \t sf2ua  sf2j ",
                "notitle\t",
                "kof97\t더 킹 오브 파이터즈 '97",
            ),
        )
        assertEquals("보글보글", map["bublbobl"])
        assertEquals("보글보글", map["bublboblr"])
        assertEquals("스트리트 파이터 2", map["sf2"])
        assertEquals("스트리트 파이터 2", map["sf2j"])
        assertEquals("더 킹 오브 파이터즈 '97", map["kof97"])
        assertNull(map["notitle"])
        assertNull(map["# comment"])
    }

    @Test
    fun `a set's own line beats a clone column, wherever it appears`() {
        val before = ArcadeTitles.parseKorean(sequenceOf("techromnu\t테크 로맨서 (미국)", "techromn\t테크 로맨서 (유럽)\ttechromnu techromna"))
        val after = ArcadeTitles.parseKorean(sequenceOf("techromn\t테크 로맨서 (유럽)\ttechromnu techromna", "techromnu\t테크 로맨서 (미국)"))
        for (map in listOf(before, after)) {
            assertEquals("테크 로맨서 (미국)", map["techromnu"])
            assertEquals("테크 로맨서 (유럽)", map["techromna"])
        }
    }

    @Test
    fun `Korean title wins over English, English fills in, clones share the parent's name`() {
        val t = titles("bublbobl\t보글보글\tbublbobl1\nsf2\t스트리트 파이터 2\tsf2ua", mame2003plus = english)
        assertEquals("보글보글", t.cleanTitle("bublbobl"))
        assertEquals("보글보글", t.cleanTitle("BublBobl1"))
        assertEquals("스트리트 파이터 2", t.cleanTitle("sf2ua"))
        assertEquals("Galaxian", t.cleanTitle("galaxian"))
        assertEquals("Street Fighter II - The World Warrior", t.englishTitle("sf2ua"))
        assertNull(t.cleanTitle("unknown"))
    }

    @Test
    fun `English tables are consulted in core order`() {
        val t = titles("", mame2003plus = "kof98\tKOF 98 (old DAT)\t1998\tSNK", mame = "kof98\tKOF 98 (new DAT)\t1998\tSNK\nkof98h\tKOF 98 (AES)\t1998\tSNK")
        assertEquals("KOF 98", t.englishTitle("kof98"))
        assertEquals("SNK", t.lookup("kof98h")?.manufacturer)
    }

    @Test
    fun `missing Korean table falls back to English`() {
        val t = ArcadeTitles { path -> if (path.endsWith("titles.tsv") && "mame2003plus" in path) english.byteInputStream() else null }
        assertEquals("Bubble Bobble", t.cleanTitle("bublbobl"))
        assertNull(t.koreanTitle("bublbobl"))
    }

    @Test
    fun `only titles the scanner generated are replaced`() {
        val t = titles("bublbobl\t보글보글\tbublbobl1\ntechromn\t테크 로맨서 (유럽)", mame2003plus = english)
        // The English DAT title, also for a clone that inherits the parent's Korean name.
        assertEquals("보글보글", t.localizedTitle("bublbobl.zip", "Bubble Bobble"))
        assertEquals("테크 로맨서 (유럽)", t.localizedTitle("techromn.zip", "Tech Romancer"))
        // The cleaned file name, from before the DAT titles were used.
        assertEquals("보글보글", t.localizedTitle("bublbobl1.zip", "bublbobl1"))
        // A name the user typed stays.
        assertNull(t.localizedTitle("bublbobl.zip", "내 보글보글"))
        assertNull(t.localizedTitle("bublbobl.zip", "Bubble Bobble 최고"))
        // Already Korean, or no Korean name to move to.
        assertNull(t.localizedTitle("bublbobl.zip", "보글보글"))
        assertNull(t.localizedTitle("galaxian.zip", "Galaxian"))
    }

    @Test
    fun `table stamp follows the table contents`() {
        val a = titles("bublbobl\t보글보글").koreanTableStamp()
        assertEquals(a, titles("# other comment\nbublbobl\t보글보글").koreanTableStamp())
        assertTrue(a != titles("bublbobl\t버블 보블").koreanTableStamp())
    }

    // --- The bundled table ---

    private val bundled: Map<String, String> by lazy {
        File("src/main/assets/${ArcadeTitles.KOREAN_TABLE}").useLines { ArcadeTitles.parseKorean(it) }
    }

    @Test
    fun `bundled table covers the user's sets with the names Korean players use`() {
        val expected = mapOf(
            "bublbobl" to "보글보글", "pbobble" to "퍼즐 보블", "kof97" to "더 킹 오브 파이터즈 '97",
            "mslug" to "메탈슬러그", "mslugx" to "메탈슬러그 X", "s1945ii" to "스트라이커즈 1945 II",
            "tmnt" to "닌자 거북이", "tektagt" to "철권 태그 토너먼트",
        )
        for ((set, name) in expected) assertEquals(set, name, bundled[set])
        val sets = "beastrzr bldyror2 bublbobl captaven circusc dmnfrnt inthunt kof2001 kof2002 kof94 kof95 kof96 kof97 " +
            "kof98n kof99 megamana mp_soni2 mp_sonic mslug mslug3 mslug4 mslug5 mslugx nbbatman pbobble penbros " +
            "s1945ii techromn techromnu tektagt tmnt tmnt2 twcup98"
        for (set in sets.split(' ')) assertTrue("$set has no Korean title", !bundled[set].isNullOrBlank())
        // Sets that would otherwise look the same in one library.
        assertTrue(bundled["techromn"] != bundled["techromnu"])
        assertTrue(bundled["kof98"] != bundled["kof98n"])
        assertTrue(bundled["mp_sonic"]!!.contains("메가 플레이"))
        // Clones share the parent's name.
        assertEquals(bundled["megaman"], bundled["megamana"])
        assertEquals(bundled["sf2"], bundled["sf2ua"])
    }

    @Test
    fun `every bundled short name exists in a bundled MAME DAT`() {
        val known = ArcadeCoreRouter.CORE_IDS.flatMap { core ->
            val f = File("../cores/$core/assets/titles.tsv")
            if (f.isFile) f.readLines().map { it.substringBefore('\t') } else emptyList()
        }.toSet()
        if (known.isEmpty()) return // DATs not checked out next to the app module
        val unknown = bundled.keys.filter { it !in known }
        assertTrue("not in any titles.tsv: $unknown", unknown.isEmpty())
    }
}
