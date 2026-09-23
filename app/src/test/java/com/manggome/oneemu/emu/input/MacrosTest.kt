package com.manggome.oneemu.emu.input

import android.view.KeyEvent
import com.manggome.oneemu.emu.EmulatorSession.Buttons
import org.junit.Assert.assertEquals
import org.junit.Test

class MacrosTest {
    @Test fun macrosSurviveTheirStorageFormat() {
        val list = listOf(Macro(3, "파동권", Macros.PRESETS.first().second), Macro(4, "저장", listOf(MacroStep(action = MacroStep.ACTION_QUICK_SAVE))))
        assertEquals(list, Macros.parse(Macros.encode(list)))
        assertEquals(emptyList<Macro>(), Macros.parse("not json"))
    }

    @Test fun labelsReadLikeAFightingGameManual() {
        assertEquals("↘+A", Macros.label(Buttons.DOWN or Buttons.RIGHT or Buttons.A))
        assertEquals("↓", Macros.label(Buttons.DOWN))
        assertEquals("A+B", Macros.label(Buttons.A or Buttons.B))
    }

    @Test fun aMacroKeyRoundTripsAndIsNotACombo() {
        val m = GamepadMapping.DEFAULT.addKey(GamepadMapping.macroValue(7), KeyEvent.KEYCODE_BUTTON_1)
        assertEquals(listOf(KeyEvent.KEYCODE_BUTTON_1 to 7), m.macroKeys)
        assert(m.combos.none { it.first == KeyEvent.KEYCODE_BUTTON_1 })
        val back = GamepadMapping.parse(m.overridesOf())
        assertEquals(GamepadMapping.macroValue(7), back.buttonFor(KeyEvent.KEYCODE_BUTTON_1))
        assert(!GamepadMapping.isMacro(GamepadMapping.REWIND))
        assert(!GamepadMapping.isMacro(Buttons.A or Buttons.B))
    }
}
