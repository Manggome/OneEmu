package com.manggome.oneemu.emu.skin

import com.manggome.oneemu.emu.EmulatorSession.Buttons
import com.manggome.oneemu.model.SystemId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Delta skin info.json parsing, input mapping and conversion to an overlay. Fixtures are hand-written. */
class DeltaSkinTest {
    private val gba = """
        {
          "name": "Test GBA",
          "identifier": "com.example.gba.test",
          "gameTypeIdentifier": "com.rileytestut.delta.game.gba",
          "debug": false,
          "representations": {
            "iphone": {
              "edgeToEdge": {
                "portrait": {
                  "assets": { "resizable": "e2e_portrait.pdf" },
                  "mappingSize": { "width": 400, "height": 800 },
                  "extendedEdges": { "top": 10, "bottom": 10, "left": 10, "right": 10 },
                  "translucent": false,
                  "screens": [ { "outputFrame": { "x": 0, "y": 50, "width": 400, "height": 266 } } ],
                  "items": [
                    { "inputs": { "up": "up", "down": "down", "left": "left", "right": "right" },
                      "frame": { "x": 20, "y": 500, "width": 120, "height": 120 } },
                    { "inputs": ["a"], "frame": { "x": 300, "y": 500, "width": 60, "height": 60 },
                      "extendedEdges": { "left": 0, "right": 20 } },
                    { "inputs": ["a", "b"], "frame": { "x": 260, "y": 560, "width": 40, "height": 40 } },
                    { "inputs": ["menu"], "frame": { "x": 10, "y": 760, "width": 30, "height": 30 } },
                    { "inputs": ["toggleFastForward"], "frame": { "x": 50, "y": 760, "width": 30, "height": 30 } },
                    { "inputs": ["quickSave"], "frame": { "x": 90, "y": 760, "width": 30, "height": 30 } },
                    { "inputs": ["screenshot"], "frame": { "x": 130, "y": 760, "width": 30, "height": 30 } },
                    { "inputs": "start", "frame": { "x": 200, "y": 760, "width": 40, "height": 20 } }
                  ]
                },
                "landscape": {
                  "assets": { "resizable": "e2e_landscape.pdf" },
                  "mappingSize": { "width": 800, "height": 400 },
                  "translucent": true,
                  "gameScreenFrame": { "x": 160, "y": 0, "width": 480, "height": 320 },
                  "items": [ { "inputs": ["l"], "frame": { "x": 0, "y": 0, "width": 100, "height": 40 } } ]
                }
              },
              "standard": {
                "portrait": {
                  "assets": { "small": "std.png", "medium": "std@2x.png", "large": "std@3x.png" },
                  "mappingSize": { "width": 375, "height": 667 },
                  "items": [ { "inputs": ["b"], "frame": { "x": 0, "y": 0, "width": 50, "height": 50 } } ]
                }
              }
            },
            "ipad": {
              "standard": {
                "portrait": {
                  "assets": { "resizable": "ipad_portrait.pdf" },
                  "mappingSize": { "width": 600, "height": 800 },
                  "items": [ { "inputs": ["b"], "frame": { "x": 0, "y": 0, "width": 50, "height": 50 } } ]
                }
              }
            }
          }
        }
    """.trimIndent()

    @Test
    fun `parses the header and every representation`() {
        val skin = DeltaSkinParser.parse(gba)
        assertEquals("Test GBA", skin.name)
        assertEquals("com.example.gba.test", skin.identifier)
        assertEquals("com.rileytestut.delta.game.gba", skin.gameTypeIdentifier)
        assertEquals(4, skin.representations.size)
        val e2e = skin.representations.single { it.device == "iphone" && it.display == "edgeToEdge" && !it.landscape }
        assertEquals("e2e_portrait.pdf", e2e.background)
        assertEquals(400f, e2e.mappingWidth)
        assertEquals(800f, e2e.mappingHeight)
        assertFalse(e2e.translucent)
        assertEquals(listOf(DeltaRect(0f, 50f, 400f, 266f)), e2e.screens)
        assertEquals(8, e2e.items.size)
    }

    @Test
    fun `assets prefer the resizable pdf, then the largest bitmap`() {
        val skin = DeltaSkinParser.parse(gba)
        assertEquals("std@3x.png", skin.representations.single { it.display == "standard" && it.device == "iphone" }.background)
    }

    @Test
    fun `gameScreenFrame is read when there are no screens`() {
        val land = DeltaSkinParser.parse(gba).representations.single { it.landscape }
        assertEquals(listOf(DeltaRect(160f, 0f, 480f, 320f)), land.screens)
        assertTrue(land.translucent)
    }

    @Test
    fun `item edges fall back side by side to the representation's`() {
        val e2e = DeltaSkinParser.parse(gba).representations.single { it.display == "edgeToEdge" && !it.landscape }
        assertEquals(DeltaEdges(10f, 10f, 10f, 10f), e2e.items[0].extendedEdges)
        assertEquals(DeltaEdges(top = 10f, bottom = 10f, left = 0f, right = 20f), e2e.items[1].extendedEdges)
    }

    @Test
    fun `inputs may be a string, an array or a direction dictionary`() {
        val items = DeltaSkinParser.parse(gba).representations.single { it.display == "edgeToEdge" && !it.landscape }.items
        assertEquals(DeltaInputs.Directional("up", "down", "left", "right"), items[0].inputs)
        assertEquals(DeltaInputs.Press(listOf("a", "b")), items[2].inputs)
        assertEquals(DeltaInputs.Press(listOf("start")), items[7].inputs)
    }

    @Test
    fun `thumbstick and per-item pictures are read`() {
        val skin = DeltaSkinParser.parse(
            """
            { "gameTypeIdentifier": "public.aoshuang.game.ps1",
              "representations": { "iphone": { "edgeToEdge": { "portrait": {
                "assets": { "resizable": "bg.pdf" }, "mappingSize": { "width": 375, "height": 812 },
                "items": [
                  { "thumbstick": { "name": "knob.pdf", "width": 40, "height": 40 },
                    "inputs": { "up": "leftThumbstickUp", "down": "leftThumbstickDown", "left": "leftThumbstickLeft", "right": "leftThumbstickRight" },
                    "frame": { "x": 70, "y": 636, "width": 80, "height": 80 } },
                  { "asset": { "normal": "cross.pdf" }, "inputs": ["b"], "frame": { "x": 256, "y": 564, "width": 52, "height": 52 } }
                ] } } } } }
            """.trimIndent(),
        )
        val items = skin.representations.single().items
        assertEquals(DeltaThumbstick("knob.pdf", 40f, 40f), items[0].thumbstick)
        assertEquals("cross.pdf", items[1].asset)
        assertEquals("public.aoshuang.game.ps1", skin.gameTypeIdentifier)
        // No name: the identifier, then a generic one.
        assertEquals("Delta skin", skin.name)
    }

    @Test
    fun `broken files are rejected`() {
        for (text in listOf("", "[]", "{}", """{"gameTypeIdentifier":"x","representations":{}}""", "not json")) {
            try {
                DeltaSkinParser.parse(text)
                fail("accepted: $text")
            } catch (_: DeltaSkinException) {
            }
        }
    }

    @Test
    fun `game types map to OneEmu systems`() {
        assertEquals(setOf(SystemId.GBA), DeltaGameTypes.systemsFor("com.rileytestut.delta.game.gba"))
        assertEquals(setOf(SystemId.GB, SystemId.GBC), DeltaGameTypes.systemsFor("com.rileytestut.delta.game.gbc"))
        assertEquals(setOf(SystemId.NES), DeltaGameTypes.systemsFor("com.rileytestut.delta.game.nes"))
        assertEquals(setOf(SystemId.NDS), DeltaGameTypes.systemsFor("com.rileytestut.delta.game.ds"))
        assertEquals(setOf(SystemId.MD), DeltaGameTypes.systemsFor("com.rileytestut.delta.game.genesis"))
        assertEquals(setOf(SystemId.PSX), DeltaGameTypes.systemsFor("public.aoshuang.game.ps1"))
        assertEquals(setOf(SystemId.N3DS), DeltaGameTypes.systemsFor("public.aoshuang.game.3ds"))
        assertEquals(setOf(SystemId.GC), DeltaGameTypes.systemsFor("public.aoshuang.game.ngc"))
        assertEquals(setOf(SystemId.SMS), DeltaGameTypes.systemsFor("public.aoshuang.game.ms"))
        // Consoles OneEmu does not run.
        assertTrue(DeltaGameTypes.systemsFor("com.rileytestut.delta.game.n64").isEmpty())
        assertTrue(DeltaGameTypes.systemsFor("com.rileytestut.delta.game.snes").isEmpty())
        assertNull(DeltaGameTypes.primarySystem("public.aoshuang.game.dc"))
    }

    @Test
    fun `button names map to the libretro pad`() {
        val s = SystemId.GBA
        assertEquals(Buttons.A, DeltaInputMap.buttonMask(s, "a"))
        assertEquals(Buttons.B, DeltaInputMap.buttonMask(s, "B"))
        assertEquals(Buttons.L, DeltaInputMap.buttonMask(s, "l"))
        assertEquals(Buttons.L, DeltaInputMap.buttonMask(s, "l1"))
        assertEquals(Buttons.R2, DeltaInputMap.buttonMask(s, "zr"))
        assertEquals(Buttons.START, DeltaInputMap.buttonMask(s, "start"))
        assertEquals(Buttons.SELECT, DeltaInputMap.buttonMask(s, "select"))
        assertNull(DeltaInputMap.buttonMask(s, "cUp"))
        assertNull(DeltaInputMap.buttonMask(s, "menu"))
        // PlayStation glyph names.
        assertEquals(Buttons.B, DeltaInputMap.buttonMask(SystemId.PSX, "cross"))
        assertEquals(Buttons.A, DeltaInputMap.buttonMask(SystemId.PSX, "circle"))
        assertEquals(Buttons.Y, DeltaInputMap.buttonMask(SystemId.PSX, "square"))
        assertEquals(Buttons.X, DeltaInputMap.buttonMask(SystemId.PSX, "triangle"))
    }

    @Test
    fun `Mega Drive and GameCube buttons follow their cores`() {
        val md = SystemId.MD
        assertEquals(Buttons.Y, DeltaInputMap.buttonMask(md, "a"))
        assertEquals(Buttons.B, DeltaInputMap.buttonMask(md, "b"))
        assertEquals(Buttons.A, DeltaInputMap.buttonMask(md, "c"))
        assertEquals(Buttons.L, DeltaInputMap.buttonMask(md, "x"))
        assertEquals(Buttons.X, DeltaInputMap.buttonMask(md, "y"))
        assertEquals(Buttons.R, DeltaInputMap.buttonMask(md, "z"))
        assertEquals(Buttons.SELECT, DeltaInputMap.buttonMask(md, "mode"))
        assertEquals(Buttons.R, DeltaInputMap.buttonMask(SystemId.GC, "z"))
        assertEquals(Buttons.L2, DeltaInputMap.buttonMask(SystemId.GC, "l"))
        assertEquals(Buttons.R2, DeltaInputMap.buttonMask(SystemId.GC, "r2"))
    }

    @Test
    fun `items become pad actions or app hotkeys`() {
        val s = SystemId.GBA
        assertEquals(DescAction.Press(Buttons.A or Buttons.B), DeltaInputMap.action(s, DeltaInputs.Press(listOf("a", "b"))))
        assertEquals(DescAction.DpadArea, DeltaInputMap.action(s, DeltaInputs.Directional("up", "down", "left", "right")))
        assertEquals(DescAction.DpadArea, DeltaInputMap.action(s, DeltaInputs.Press(listOf("up", "down", "left", "right"))))
        assertEquals(DescAction.MenuToggle, DeltaInputMap.action(s, DeltaInputs.Press(listOf("menu"))))
        assertEquals(DescAction.FastForward(hold = false), DeltaInputMap.action(s, DeltaInputs.Press(listOf("toggleFastForward"))))
        assertEquals(DescAction.FastForward(hold = true), DeltaInputMap.action(s, DeltaInputs.Press(listOf("fastForward"))))
        assertEquals(DescAction.QuickSave, DeltaInputMap.action(s, DeltaInputs.Press(listOf("quickSave"))))
        assertEquals(DescAction.QuickLoad, DeltaInputMap.action(s, DeltaInputs.Press(listOf("quickLoad"))))
        assertNull(DeltaInputMap.action(s, DeltaInputs.Press(listOf("screenshot"))))
        assertNull(DeltaInputMap.action(s, DeltaInputs.Press(emptyList())))
        // The DS touch screen is the game picture itself.
        assertNull(DeltaInputMap.action(SystemId.NDS, DeltaInputs.Directional(null, null, null, null)))
        assertNull(DeltaInputMap.action(SystemId.NDS, DeltaInputs.Directional("touchScreenY", null, "touchScreenX", null)))
    }

    @Test
    fun `sticks are analog where the console has them, a d-pad elsewhere`() {
        val left = DeltaInputs.Directional("leftThumbstickUp", "leftThumbstickDown", "leftThumbstickLeft", "leftThumbstickRight")
        val right = DeltaInputs.Directional("rightThumbstickUp", "rightThumbstickDown", "rightThumbstickLeft", "rightThumbstickRight")
        val n64 = DeltaInputs.Directional("analogStickUp", "analogStickDown", "analogStickLeft", "analogStickRight")
        assertEquals(DescAction.Analog(right = false), DeltaInputMap.action(SystemId.PSX, left))
        assertEquals(DescAction.Analog(right = true), DeltaInputMap.action(SystemId.PSX, right))
        assertEquals(DescAction.Analog(right = false), DeltaInputMap.action(SystemId.N3DS, n64))
        assertEquals(DescAction.DpadArea, DeltaInputMap.action(SystemId.ARCADE, left))
        assertNull(DeltaInputMap.action(SystemId.ARCADE, right))
    }

    @Test
    fun `a representation converts to a whole-picture overlay`() {
        val rep = DeltaSkinParser.parse(gba).representations.single { it.display == "edgeToEdge" && !it.landscape }
        val o = DeltaSkinLayout.toOverlay(rep, SystemId.GBA)
        assertNotNull(o)
        o!!
        assertTrue(o.delta)
        assertFalse(o.translucent)
        assertEquals(OverlayOrientation.PORTRAIT, o.orientation)
        assertEquals("e2e_portrait.pdf", o.backgroundImage)
        assertEquals(0.5f, o.aspectRatio!!, 1e-6f)
        assertTrue(o.blockXSeparation && o.blockYSeparation)
        assertEquals(NormRect(0f, 50f / 800f, 1f, 266f / 800f), o.gameScreen)
        // screenshot is dropped; the other seven remain.
        assertEquals(7, o.descs.size)
        val a = o.descs.single { it.action == DescAction.Press(Buttons.A) }
        assertEquals(330f / 400f, a.x, 1e-6f)
        assertEquals(530f / 800f, a.y, 1e-6f)
        assertEquals(30f / 400f, a.rx, 1e-6f)
        assertEquals(HitShape.RECT, a.shape)
        // 0 left, 20 right, 10 (the representation's) top and bottom, over a 30-unit half extent.
        assertEquals(1f, a.reachLeft, 1e-6f)
        assertEquals(1f + 20f / 30f, a.reachRight, 1e-6f)
        assertEquals(1f + 10f / 30f, a.reachUp, 1e-6f)
        // Hotkeys printed on the picture are live even without a picture of their own.
        val menu = o.descs.single { it.action == DescAction.MenuToggle }
        assertTrue(menu.interactive)
        assertFalse(menu.drawable)
        assertTrue(o.descs.single { it.action == DescAction.QuickSave }.interactive)
    }

    @Test
    fun `a thumbstick knob is drawn at its own size inside the touch area`() {
        val rep = DeltaRepresentation(
            "iphone", "edgeToEdge", false, "bg.pdf", 375f, 812f,
            listOf(
                DeltaItem(
                    DeltaInputs.Directional("leftThumbstickUp", "leftThumbstickDown", "leftThumbstickLeft", "leftThumbstickRight"),
                    DeltaRect(70f, 636f, 80f, 80f), DeltaEdges.NONE, null, DeltaThumbstick("knob.pdf", 40f, 60f),
                ),
            ),
            emptyList(), false,
        )
        val d = DeltaSkinLayout.toOverlay(rep, SystemId.PSX)!!.descs.single()
        assertEquals(DescAction.Analog(right = false), d.action)
        assertEquals("knob.pdf", d.image)
        assertEquals(0.5f, d.imageScaleX, 1e-6f)
        assertEquals(0.75f, d.imageScaleY, 1e-6f)
    }

    @Test
    fun `a layout without pad controls or with side-by-side screens is not used`() {
        val onlyMenu = DeltaRepresentation(
            "iphone", "edgeToEdge", false, "bg.pdf", 375f, 812f,
            listOf(DeltaItem(DeltaInputs.Press(listOf("menu")), DeltaRect(0f, 0f, 20f, 20f), DeltaEdges.NONE, null, null)),
            emptyList(), false,
        )
        assertNull(DeltaSkinLayout.toOverlay(onlyMenu, SystemId.GBA))
        val dsLandscape = onlyMenu.copy(
            landscape = true,
            items = listOf(DeltaItem(DeltaInputs.Press(listOf("a")), DeltaRect(0f, 0f, 20f, 20f), DeltaEdges.NONE, null, null)),
            screens = listOf(DeltaRect(95f, 6f, 310f, 233f), DeltaRect(408f, 6f, 310f, 233f)),
        )
        assertNull(DeltaSkinLayout.toOverlay(dsLandscape, SystemId.NDS))
        // Stacked DS screens are one picture: their union.
        val dsPortrait = dsLandscape.copy(landscape = false, screens = listOf(DeltaRect(8f, 55f, 359f, 269f), DeltaRect(40f, 327f, 295f, 221f)))
        val o = DeltaSkinLayout.toOverlay(dsPortrait, SystemId.NDS)!!
        assertEquals(NormRect(8f / 375f, 55f / 812f, 359f / 375f, 493f / 812f), o.gameScreen)
    }

    @Test
    fun `the closest aspect wins, an iPad layout on a wide screen`() {
        val skin = DeltaSkinParser.parse(gba)
        // A 20:9 phone: edge-to-edge (0.5) over standard (0.56) and iPad (0.75).
        val phone = DeltaSkinLayout.pick(skin, landscape = false, screenAspect = 9f / 20f, system = SystemId.GBA)!!
        assertEquals("edgeToEdge", phone.first.display)
        // An unfolded Fold, nearly square.
        val fold = DeltaSkinLayout.pick(skin, landscape = false, screenAspect = 0.9f, system = SystemId.GBA)!!
        assertEquals("ipad", fold.first.device)
        // Landscape exists only as iPhone edge-to-edge.
        assertEquals("edgeToEdge", DeltaSkinLayout.pick(skin, landscape = true, screenAspect = 20f / 9f, system = SystemId.GBA)!!.first.display)
    }

    @Test
    fun `no layout for an orientation means the built-in pad`() {
        val portraitOnly = DeltaSkinParser.parse(gba).let { s -> s.copy(representations = s.representations.filter { !it.landscape }) }
        assertNull(DeltaSkinLayout.pick(portraitOnly, landscape = true, screenAspect = 2f, system = SystemId.GBA))
    }
}
