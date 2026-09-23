package com.manggome.oneemu.emu.input

import androidx.datastore.preferences.core.stringPreferencesKey
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.emu.EmulatorSession.Buttons
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One step of a [Macro]: hold [buttons] for [frames] emulated frames (60 a second), or - when [action] is
 * set - do that instead ([ACTION_QUICK_SAVE], [ACTION_QUICK_LOAD]). A step with no buttons is a pause.
 */
@Serializable
data class MacroStep(
    val buttons: Int = 0,
    val frames: Int = DEFAULT_FRAMES,
    val action: String? = null,
) {
    companion object {
        const val DEFAULT_FRAMES = 4
        const val ACTION_QUICK_SAVE = "quick_save"
        const val ACTION_QUICK_LOAD = "quick_load"
    }
}

/** A named sequence played by one pad key - a 파동권 motion, a combo, a mash of A. */
@Serializable
data class Macro(val id: Int, val name: String, val steps: List<MacroStep>) {
    /** "↓ ↘ →+A" style summary for lists. */
    fun summary(): String = steps.joinToString("  ") { step ->
        when (step.action) {
            MacroStep.ACTION_QUICK_SAVE -> "[빠른저장]"
            MacroStep.ACTION_QUICK_LOAD -> "[빠른로드]"
            else -> if (step.buttons == 0) "·" else Macros.label(step.buttons)
        }
    }
}

object Macros {
    val KEY = stringPreferencesKey("macros")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(Macro.serializer())

    fun parse(text: String): List<Macro> =
        if (text.isBlank()) emptyList() else runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList())

    fun encode(list: List<Macro>): String = json.encodeToString(serializer, list)

    fun observe(settings: Settings): Flow<List<Macro>> = settings.observe(KEY, "").map(::parse)
    suspend fun load(settings: Settings): List<Macro> = parse(settings.get(KEY, ""))
    suspend fun save(settings: Settings, list: List<Macro>) = settings.set(KEY, encode(list))

    fun nextId(list: List<Macro>): Int = (list.maxOfOrNull { it.id } ?: 0) + 1

    /** Directions as arrows, the rest by name: "↘+A". */
    fun label(mask: Int): String {
        val up = mask and Buttons.UP != 0
        val down = mask and Buttons.DOWN != 0
        val left = mask and Buttons.LEFT != 0
        val right = mask and Buttons.RIGHT != 0
        val arrow = when {
            up && left -> "↖"; up && right -> "↗"; down && left -> "↙"; down && right -> "↘"
            up -> "↑"; down -> "↓"; left -> "←"; right -> "→"; else -> ""
        }
        val dirs = Buttons.UP or Buttons.DOWN or Buttons.LEFT or Buttons.RIGHT
        val rest = GamepadMapping.COMBO_BUTTONS.filter { (_, bit) -> bit and dirs == 0 && mask and bit != 0 }.map { it.first }
        return (listOf(arrow).filter { it.isNotEmpty() } + rest).joinToString("+")
    }

    /**
     * Starting points; the player edits frames and buttons from there. Written for a character facing
     * right - the mirrored ones are separate presets, as a pad has no idea which way the fighter faces.
     */
    val PRESETS: List<Pair<String, List<MacroStep>>> = listOf(
        "파동권 (↓↘→+A)" to listOf(
            MacroStep(Buttons.DOWN, 3), MacroStep(Buttons.DOWN or Buttons.RIGHT, 3), MacroStep(Buttons.RIGHT or Buttons.A, 4),
        ),
        "파동권 왼쪽 (↓↙←+A)" to listOf(
            MacroStep(Buttons.DOWN, 3), MacroStep(Buttons.DOWN or Buttons.LEFT, 3), MacroStep(Buttons.LEFT or Buttons.A, 4),
        ),
        "승룡권 (→↓↘+A)" to listOf(
            MacroStep(Buttons.RIGHT, 3), MacroStep(Buttons.DOWN, 3), MacroStep(Buttons.DOWN or Buttons.RIGHT or Buttons.A, 4),
        ),
        "대시 (→ →)" to listOf(
            MacroStep(Buttons.RIGHT, 3), MacroStep(0, 3), MacroStep(Buttons.RIGHT, 6),
        ),
        "A 5번 연타" to (1..5).flatMap { listOf(MacroStep(Buttons.A, 3), MacroStep(0, 3)) },
        "빠른 저장 후 A" to listOf(MacroStep(action = MacroStep.ACTION_QUICK_SAVE), MacroStep(Buttons.A, 4)),
    )
}
