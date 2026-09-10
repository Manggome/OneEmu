package com.manggome.oneemu.emu.pad

import androidx.datastore.preferences.core.booleanPreferencesKey
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.emu.EmulatorSession.Buttons
import com.manggome.oneemu.model.SystemId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Every control the virtual pad can show. Buttons carry the libretro mask they press; the arcade
 * buttons follow MAME 2003-Plus' RetroPad wiring (1→B, 2→A, 3→Y, 4→X, 5→L, 6→R, COIN→SELECT).
 */
enum class PadElementId(val mask: Int, val kind: Kind) {
    DPAD(0, Kind.DPAD),
    BUTTON_A(Buttons.A, Kind.ROUND),
    BUTTON_B(Buttons.B, Kind.ROUND),
    BUTTON_X(Buttons.X, Kind.ROUND),
    BUTTON_Y(Buttons.Y, Kind.ROUND),
    ABXY_CLUSTER(0, Kind.CLUSTER),
    L(Buttons.L, Kind.PILL),
    R(Buttons.R, Kind.PILL),
    L2(Buttons.L2, Kind.PILL),
    R2(Buttons.R2, Kind.PILL),
    START(Buttons.START, Kind.PILL),
    SELECT(Buttons.SELECT, Kind.PILL),
    LEFT_STICK(0, Kind.STICK),
    RIGHT_STICK(0, Kind.STICK),
    MENU(0, Kind.SMALL),
    FAST_FORWARD(0, Kind.SMALL),
    COIN(Buttons.SELECT, Kind.PILL),
    ARCADE_1(Buttons.B, Kind.ROUND),
    ARCADE_2(Buttons.A, Kind.ROUND),
    ARCADE_3(Buttons.Y, Kind.ROUND),
    ARCADE_4(Buttons.X, Kind.ROUND),
    ARCADE_5(Buttons.L, Kind.ROUND),
    ARCADE_6(Buttons.R, Kind.ROUND);

    enum class Kind { DPAD, ROUND, CLUSTER, PILL, STICK, SMALL }

    /** Buttons that may be slid onto from a neighbouring button with the same finger. */
    val slidable: Boolean get() = kind == Kind.ROUND || kind == Kind.PILL || kind == Kind.CLUSTER

    /** Base size in dp before [PadElement.scale] and the global pad scale. */
    val baseWidthDp: Float
        get() = when (kind) {
            Kind.DPAD -> 150f
            Kind.ROUND -> 62f
            Kind.CLUSTER -> 170f
            Kind.PILL -> if (this == L || this == R || this == L2 || this == R2) 84f else 72f
            Kind.STICK -> 120f
            Kind.SMALL -> 40f
        }
    val baseHeightDp: Float
        get() = when (kind) {
            Kind.PILL -> 34f
            Kind.SMALL -> 32f
            else -> baseWidthDp
        }

    /** Label as it appears on the button; PSP/PS2 use the PlayStation glyphs. */
    fun label(system: SystemId): String = when (this) {
        BUTTON_A -> if (system.isPlayStation) "○" else "A"
        BUTTON_B -> if (system.isPlayStation) "×" else "B"
        BUTTON_X -> if (system.isPlayStation) "△" else "X"
        BUTTON_Y -> if (system.isPlayStation) "□" else "Y"
        L -> if (system.isPlayStation) "L1" else "L"
        R -> if (system.isPlayStation) "R1" else "R"
        L2 -> "L2"
        R2 -> "R2"
        START -> "START"
        SELECT -> "SELECT"
        MENU -> "☰"
        FAST_FORWARD -> "▶▶"
        COIN -> "COIN"
        ARCADE_1 -> "1"
        ARCADE_2 -> "2"
        ARCADE_3 -> "3"
        ARCADE_4 -> "4"
        ARCADE_5 -> "5"
        ARCADE_6 -> "6"
        DPAD, ABXY_CLUSTER, LEFT_STICK, RIGHT_STICK -> ""
    }

    /** Korean name shown in the layout editor list. */
    val displayName: String
        get() = when (this) {
            DPAD -> "방향 패드"
            BUTTON_A -> "A 버튼"
            BUTTON_B -> "B 버튼"
            BUTTON_X -> "X 버튼"
            BUTTON_Y -> "Y 버튼"
            ABXY_CLUSTER -> "ABXY 묶음"
            L -> "L 버튼"
            R -> "R 버튼"
            L2 -> "L2 버튼"
            R2 -> "R2 버튼"
            START -> "START"
            SELECT -> "SELECT"
            LEFT_STICK -> "왼쪽 스틱"
            RIGHT_STICK -> "오른쪽 스틱"
            MENU -> "메뉴 버튼"
            FAST_FORWARD -> "빨리감기 버튼"
            COIN -> "코인"
            ARCADE_1 -> "버튼 1"
            ARCADE_2 -> "버튼 2"
            ARCADE_3 -> "버튼 3"
            ARCADE_4 -> "버튼 4"
            ARCADE_5 -> "버튼 5"
            ARCADE_6 -> "버튼 6"
        }
}

val SystemId.isPlayStation: Boolean get() = this == SystemId.PSP || this == SystemId.PS2

/** One control on screen. [x]/[y] are the normalized 0..1 center position relative to the screen. */
@Serializable
data class PadElement(
    val id: PadElementId,
    val x: Float,
    val y: Float,
    val scale: Float = 1f,
    val visible: Boolean = true,
)

@Serializable
data class PadLayout(val elements: List<PadElement>) {
    operator fun get(id: PadElementId): PadElement? = elements.firstOrNull { it.id == id }

    fun update(id: PadElementId, transform: (PadElement) -> PadElement): PadLayout =
        copy(elements = elements.map { if (it.id == id) transform(it) else it })

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun fromJson(text: String): PadLayout? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
            ?.takeIf { it.elements.isNotEmpty() }
    }
}

/** Loads/saves per-(system, orientation) layouts through [Settings.Keys.layout]. */
object PadLayoutStore {
    /** Preferences owned by the emulator package. */
    object Keys {
        val layoutSnapToGrid = booleanPreferencesKey("layout_snap_grid")
    }

    private val settings get() = OneEmuApp.get().settings

    fun observe(system: SystemId, landscape: Boolean): Flow<PadLayout> =
        settings.observe(Settings.Keys.layout(system.id, landscape), "").map { resolve(system, landscape, it) }

    suspend fun load(system: SystemId, landscape: Boolean): PadLayout =
        resolve(system, landscape, settings.get(Settings.Keys.layout(system.id, landscape), ""))

    suspend fun save(system: SystemId, landscape: Boolean, layout: PadLayout) =
        settings.set(Settings.Keys.layout(system.id, landscape), layout.toJson())

    suspend fun reset(system: SystemId, landscape: Boolean) = settings.remove(Settings.Keys.layout(system.id, landscape))

    /** Saved layout merged with the default so elements added in newer versions still show up. */
    private fun resolve(system: SystemId, landscape: Boolean, saved: String): PadLayout {
        val def = DefaultLayouts.forSystem(system, landscape)
        val stored = saved.takeIf { it.isNotBlank() }?.let { PadLayout.fromJson(it) } ?: return def
        val known = stored.elements.map { it.id }.toSet()
        return PadLayout(stored.elements + def.elements.filter { it.id !in known })
    }
}
