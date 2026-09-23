package com.manggome.oneemu.emu.pad

import androidx.datastore.preferences.core.booleanPreferencesKey
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.emu.EmulatorSession.Buttons
import com.manggome.oneemu.emu.ScreenConfig
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
    // Stick clicks. Cores put their own functions here: melonDS DS uses L3 for the microphone and
    // R3 for the next screen layout, which is what its on-screen mic and layout icons really are.
    L3(Buttons.L3, Kind.PILL),
    R3(Buttons.R3, Kind.PILL),
    START(Buttons.START, Kind.PILL),
    SELECT(Buttons.SELECT, Kind.PILL),
    LEFT_STICK(0, Kind.STICK),
    RIGHT_STICK(0, Kind.STICK),
    MENU(0, Kind.SMALL),
    FAST_FORWARD(0, Kind.SMALL),
    SPEED(0, Kind.SMALL),
    TURBO(0, Kind.SMALL),
    SAVE_STATE(0, Kind.SMALL),
    LOAD_STATE(0, Kind.SMALL),
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
            Kind.PILL -> if (this == L || this == R || this == L2 || this == R2 || this == L3 || this == R3) 84f else 72f
            Kind.STICK -> 120f
            // The state buttons carry a word rather than a glyph, so they need the room for it.
            Kind.SMALL -> if (this == SAVE_STATE || this == LOAD_STATE) 64f else 40f
        }
    val baseHeightDp: Float
        get() = when (kind) {
            Kind.PILL -> 34f
            Kind.SMALL -> 32f
            else -> baseWidthDp
        }

    /**
     * Label as it appears on the button. PSP/PS2 use the PlayStation glyphs, and a Wii Remote its own:
     * Dolphin wires the same retro pad to completely different Wii Remote and nunchuk buttons.
     */
    fun label(profile: PadProfile): String {
        val system = profile.system
        val wii = profile.isWiimote
        return when (this) {
            BUTTON_A -> if (system.isPlayStation) "○" else "A"
            BUTTON_B -> if (system.isPlayStation) "×" else "B"
            BUTTON_X -> if (wii) "C" else if (system.isPlayStation) "△" else "X"
            BUTTON_Y -> if (wii) "Z" else if (system.isPlayStation) "□" else "Y"
            L -> if (wii) "−" else if (system.isPlayStation) "L1" else "L"
            R -> if (wii) "+" else if (system.isPlayStation) "R1" else "R"
            L2 -> if (wii) "눈차크" else "L2"
            R2 -> if (wii) "흔들기" else if (system == SystemId.NDS) "터치" else "R2"
            // melonDS DS reads these as its microphone and screen-layout controls.
            // The core recentres the gyro pointer on L3 when the Wii Remote aims with the phone's motion.
            L3 -> if (wii) "재조준" else if (system == SystemId.NDS) "마이크" else "L3"
            R3 -> if (wii) "HOME" else if (system == SystemId.NDS) "화면" else "R3"
            START -> if (wii) "1" else "START"
            SELECT -> if (wii) "2" else "SELECT"
            MENU -> "☰"
            FAST_FORWARD -> "▶▶"
            SPEED -> "1×" // replaced with the live speed while a game runs
            TURBO -> "연사"
            SAVE_STATE -> "저장"
            LOAD_STATE -> "불러오기"
            COIN -> "COIN"
            ARCADE_1 -> "1"
            ARCADE_2 -> "2"
            ARCADE_3 -> "3"
            ARCADE_4 -> "4"
            ARCADE_5 -> "5"
            ARCADE_6 -> "6"
            DPAD, ABXY_CLUSTER, LEFT_STICK, RIGHT_STICK -> ""
        }
    }

    /** Convenience for the systems whose pad has a single legend. */
    fun label(system: SystemId): String = label(PadProfile(system))

    /** Where the editor drops a control the current layout does not have yet (normalized, top-left origin). */
    val defaultSpot: Pair<Float, Float>
        get() = when (this) {
            SPEED -> 0.96f to 0.18f
            TURBO -> 0.96f to 0.28f
            FAST_FORWARD -> 0.96f to 0.08f
            MENU -> 0.04f to 0.08f
            SAVE_STATE -> 0.13f to 0.08f
            LOAD_STATE -> 0.33f to 0.08f
            else -> 0.5f to 0.5f
        }

    /** Korean name for the layout editor list, spelling out what the control does on [profile]. */
    fun displayName(profile: PadProfile): String = if (!profile.isWiimote) displayName else when (this) {
        BUTTON_X -> "C 버튼 (눈차크)"
        BUTTON_Y -> "Z 버튼 (눈차크)"
        L -> "− 버튼"
        R -> "+ 버튼"
        L2 -> "눈차크 흔들기"
        R2 -> "위모컨 흔들기"
        R3 -> "HOME 버튼"
        START -> "1 버튼"
        SELECT -> "2 버튼"
        LEFT_STICK -> "눈차크 스틱"
        RIGHT_STICK -> "조준 스틱 (포인터)"
        L3 -> "재조준 (자이로 조준일 때 가운데로)"
        else -> displayName
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
            R2 -> "R2 버튼 (DS: 터치 조이스틱)"
            L3 -> "L3 버튼 (DS: 마이크)"
            R3 -> "R3 버튼 (DS: 화면 배치 전환)"
            START -> "START"
            SELECT -> "SELECT"
            LEFT_STICK -> "왼쪽 스틱"
            RIGHT_STICK -> "오른쪽 스틱"
            MENU -> "메뉴 버튼"
            FAST_FORWARD -> "빨리감기 버튼"
            SPEED -> "배속 버튼"
            TURBO -> "연사 버튼"
            SAVE_STATE -> "저장 버튼"
            LOAD_STATE -> "불러오기 버튼"
            COIN -> "코인"
            ARCADE_1 -> "버튼 1"
            ARCADE_2 -> "버튼 2"
            ARCADE_3 -> "버튼 3"
            ARCADE_4 -> "버튼 4"
            ARCADE_5 -> "버튼 5"
            ARCADE_6 -> "버튼 6"
        }
}

val SystemId.isPlayStation: Boolean get() = this == SystemId.PSX || this == SystemId.PSP || this == SystemId.PS2

/** One control on screen. [x]/[y] are the normalized 0..1 center position relative to the screen. */
@Serializable
data class PadElement(
    val id: PadElementId,
    val x: Float,
    val y: Float,
    val scale: Float = 1f,
    val visible: Boolean = true,
    /**
     * Sticks only: press the d-pad as well as moving the stick. Plenty of games never read the analog
     * sticks at all - Tekken on PlayStation is a digital game - and on those the stick does nothing
     * whatsoever until it also sends a direction. Off by default, because a game that reads both would
     * then see the same input twice.
     */
    val dpadToo: Boolean = false,
)

@Serializable
data class PadLayout(val elements: List<PadElement>) {
    operator fun get(id: PadElementId): PadElement? = elements.firstOrNull { it.id == id }

    fun update(id: PadElementId, transform: (PadElement) -> PadElement): PadLayout =
        copy(elements = elements.map { if (it.id == id) transform(it) else it })

    /** Adds [id] at ([x], [y]) when the layout does not contain it yet, otherwise just shows it again. */
    fun withElement(id: PadElementId, x: Float, y: Float): PadLayout =
        if (this[id] != null) update(id) { it.copy(visible = true) }
        else copy(elements = elements + PadElement(id, x, y))

    /**
     * Left and right swapped. Buttons keep their size and their vertical place, so a right-handed
     * layout becomes the left-handed one in a single step.
     */
    fun mirrored(): PadLayout = copy(elements = elements.map { it.copy(x = 1f - it.x) })

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun fromJson(text: String): PadLayout? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
            ?.takeIf { it.elements.isNotEmpty() }
    }
}

/** Loads/saves per-(pad profile, screen configuration) layouts through [Settings.Keys.layout]. */
object PadLayoutStore {
    /** Preferences owned by the emulator package. */
    object Keys {
        val layoutSnapToGrid = booleanPreferencesKey("layout_snap_grid")
    }

    private val settings get() = OneEmuApp.get().settings

    fun observe(profile: PadProfile, config: ScreenConfig): Flow<PadLayout> =
        settings.observe(Settings.Keys.layout(profile.key, config), "").map { resolve(profile, config, it) }

    suspend fun load(profile: PadProfile, config: ScreenConfig): PadLayout =
        resolve(profile, config, settings.get(Settings.Keys.layout(profile.key, config), ""))

    suspend fun save(profile: PadProfile, config: ScreenConfig, layout: PadLayout) =
        settings.set(Settings.Keys.layout(profile.key, config), layout.toJson())

    suspend fun reset(profile: PadProfile, config: ScreenConfig) = settings.remove(Settings.Keys.layout(profile.key, config))

    fun observe(system: SystemId, config: ScreenConfig): Flow<PadLayout> = observe(PadProfile(system), config)
    suspend fun load(system: SystemId, config: ScreenConfig): PadLayout = load(PadProfile(system), config)
    suspend fun save(system: SystemId, config: ScreenConfig, layout: PadLayout) = save(PadProfile(system), config, layout)

    /** Saved layout merged with the default so elements added in newer versions still show up. */
    private fun resolve(profile: PadProfile, config: ScreenConfig, saved: String): PadLayout {
        val def = DefaultLayouts.forProfile(profile, config)
        val stored = saved.takeIf { it.isNotBlank() }?.let { PadLayout.fromJson(it) } ?: return def
        val known = stored.elements.map { it.id }.toSet()
        return PadLayout(stored.elements + def.elements.filter { it.id !in known })
    }
}
