package com.manggome.oneemu.emu.skin

import com.manggome.oneemu.emu.EmulatorSession.Buttons
import kotlin.math.abs

/**
 * RetroArch overlay (.cfg) model and parser.
 *
 * Supported subset (what the `flat`, `neo-retropad`, `lite` and the classic libretro gamepad sets use):
 *  - `overlays`, `overlayN_name`, `overlayN_overlay` (background), `overlayN_full_screen`, `overlayN_normalized`,
 *    `overlayN_aspect_ratio`, `overlayN_block_x_separation`, `overlayN_block_y_separation`, `overlayN_range_mod`,
 *    `overlayN_alpha_mod`, `overlayN_next`
 *  - `overlayN_descs`, `overlayN_descM = "id,x,y,radial|rect,rx,ry"` and the per-desc keys `_overlay`, `_movable`,
 *    `_alpha_mod`, `_range_mod`, `_range_mod_exclusive`, `_exclusive`, `_saturate_pct`, `_next_target`,
 *    `_reach_x/_reach_y/_reach_left/_reach_right/_reach_up/_reach_down`
 *  - ids: RetroPad buttons (`a b x y l r l2 r2 l3 r3 start select up down left right`) and `|` combos, `dpad_area`,
 *    `abxy_area`, `analog_left`, `analog_right`, `menu_toggle`, `overlay_next`, `toggle_fast_forward`,
 *    `hold_fast_forward`, `nul` (image only). Anything else (`save_state`, `retrok_*`, ...) is kept but inert.
 */
enum class HitShape { RADIAL, RECT }

enum class OverlayOrientation { PORTRAIT, LANDSCAPE }

sealed interface DescAction {
    /** One or more RetroPad buttons (libretro mask). */
    data class Press(val mask: Int) : DescAction
    /** 8-way area that emits up/down/left/right by finger angle. */
    data object DpadArea : DescAction
    /** Diamond area that emits X (top) / A (right) / B (bottom) / Y (left). */
    data object AbxyArea : DescAction
    data class Analog(val right: Boolean) : DescAction
    data object MenuToggle : DescAction
    /** Switch to [target] (null = next overlay in file order). */
    data class OverlayNext(val target: String?) : DescAction
    data class FastForward(val hold: Boolean) : DescAction
    /** Picture only, never hit-tested. */
    data object Image : DescAction
    /** Known RetroArch hotkey we do not implement; hidden. */
    data object Unsupported : DescAction

    val isPad: Boolean get() = this is Press || this is DpadArea || this is AbxyArea || this is Analog
}

data class OverlayDesc(
    val index: Int,
    val id: String,
    val action: DescAction,
    val x: Float,
    val y: Float,
    val shape: HitShape,
    val rx: Float,
    val ry: Float,
    /** Image path relative to the skin root, or null for hit-only descs. */
    val image: String?,
    val movable: Boolean,
    val alphaMod: Float?,
    val rangeMod: Float?,
    val rangeModExclusive: Boolean,
    val exclusive: Boolean,
    val saturatePct: Float,
    val reachLeft: Float,
    val reachRight: Float,
    val reachUp: Float,
    val reachDown: Float,
) {
    /** Hit-tested at runtime. Invisible hotkeys (no image) are dropped so they cannot hijack game touches. */
    val interactive: Boolean
        get() = when (action) {
            DescAction.Image, DescAction.Unsupported -> false
            DescAction.MenuToggle, is DescAction.OverlayNext, is DescAction.FastForward -> image != null
            else -> rx > 0f && ry > 0f
        }

    /** Drawn on screen (has an image and is either a picture or something the app can act on). */
    val drawable: Boolean get() = image != null && action != DescAction.Unsupported
}

data class Overlay(
    val index: Int,
    val name: String,
    val fullScreen: Boolean,
    val normalized: Boolean,
    val backgroundImage: String?,
    val aspectRatio: Float?,
    val blockXSeparation: Boolean,
    val blockYSeparation: Boolean,
    val rangeMod: Float,
    val alphaMod: Float,
    val next: String?,
    val descs: List<OverlayDesc>,
) {
    private val lower = name.lowercase()

    val orientation: OverlayOrientation?
        get() = when {
            "portrait" in lower -> OverlayOrientation.PORTRAIT
            "landscape" in lower -> OverlayOrientation.LANDSCAPE
            aspectRatio != null && aspectRatio < 1f -> OverlayOrientation.PORTRAIT
            aspectRatio != null && aspectRatio > 1f -> OverlayOrientation.LANDSCAPE
            else -> null
        }

    val hasPadButtons: Boolean get() = descs.any { it.action.isPad }
    val hasAnalog: Boolean get() = descs.any { it.action is DescAction.Analog }
    val isHidden: Boolean get() = "hidden" in lower || lower == "hide" || lower.startsWith("hide")

    /** A "menu page" overlay: only RetroArch hotkeys (save/load state, rewind, ...) and no pad buttons. */
    val isMenuLike: Boolean
        get() = !hasPadButtons && descs.any { it.action == DescAction.Unsupported && it.id in MENU_IDS }

    /** Aspect the author laid the overlay out for; RetroArch stretches to the screen when absent. */
    fun designAspect(landscape: Boolean): Float = aspectRatio ?: if (landscape) 16f / 9f else 9f / 16f

    companion object {
        private val MENU_IDS = setOf(
            "save_state", "load_state", "state_slot_increase", "state_slot_decrease", "rewind", "reset",
            "shader_next", "shader_prev", "toggle_slowmotion", "slowmotion", "hold_slowmotion",
        )
    }
}

class OverlayCfg(val overlays: List<Overlay>) {
    fun byName(name: String?): Overlay? = name?.let { n -> overlays.firstOrNull { it.name.equals(n, ignoreCase = true) } }

    fun after(overlay: Overlay): Overlay? = overlays.getOrNull((overlay.index + 1) % overlays.size)

    /**
     * Initial overlay for an orientation: visible, not a menu page, matching orientation (or unspecified),
     * preferring an analog variant when [preferAnalog] and the aspect closest to [screenAspect].
     */
    fun pick(landscape: Boolean, screenAspect: Float, preferAnalog: Boolean): Overlay? {
        val wanted = if (landscape) OverlayOrientation.LANDSCAPE else OverlayOrientation.PORTRAIT
        val usable = overlays.filter { it.hasPadButtons && !it.isHidden && !it.isMenuLike }
        val oriented = usable.filter { it.orientation == wanted }.ifEmpty { usable.filter { it.orientation == null } }.ifEmpty { usable }
        if (oriented.isEmpty()) return overlays.firstOrNull()
        return oriented.sortedWith(
            compareBy<Overlay>({ if (preferAnalog) !it.hasAnalog else it.hasAnalog })
                .thenBy { "popout" in it.name.lowercase() }
                .thenBy { abs(it.designAspect(landscape) - screenAspect) }
                .thenBy { it.index },
        ).first()
    }

    val isEmpty: Boolean get() = overlays.isEmpty()
}

object OverlayCfgParser {
    /** [cfgDir] is the skin-relative directory of the .cfg ("" for root), used to resolve image paths. */
    fun parse(text: String, cfgDir: String = ""): OverlayCfg {
        val kv = HashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            var value = line.substring(eq + 1).trim()
            if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) value = value.substring(1, value.length - 1)
            kv[key] = value
        }
        val count = kv["overlays"]?.toIntOrNull() ?: 0
        val overlays = ArrayList<Overlay>(count)
        for (i in 0 until count) {
            val p = "overlay${i}_"
            val descCount = kv["${p}descs"]?.toIntOrNull() ?: 0
            val descs = ArrayList<OverlayDesc>(descCount)
            for (j in 0 until descCount) {
                val d = kv["${p}desc$j"] ?: continue
                parseDesc(j, d, "${p}desc${j}_", kv, cfgDir)?.let(descs::add)
            }
            overlays += Overlay(
                index = i,
                name = kv["${p}name"] ?: i.toString(),
                fullScreen = kv["${p}full_screen"].toBool(true),
                normalized = kv["${p}normalized"].toBool(true),
                backgroundImage = kv["${p}overlay"]?.let { join(cfgDir, it) },
                aspectRatio = kv["${p}aspect_ratio"]?.toFloatOrNull()?.takeIf { it > 0f },
                blockXSeparation = kv["${p}block_x_separation"].toBool(false),
                blockYSeparation = kv["${p}block_y_separation"].toBool(false),
                rangeMod = kv["${p}range_mod"]?.toFloatOrNull() ?: 1.5f,
                alphaMod = kv["${p}alpha_mod"]?.toFloatOrNull() ?: 2f,
                next = kv["${p}next"],
                descs = descs,
            )
        }
        return OverlayCfg(overlays)
    }

    private fun parseDesc(index: Int, spec: String, p: String, kv: Map<String, String>, cfgDir: String): OverlayDesc? {
        val parts = spec.split(',').map { it.trim() }
        if (parts.size < 6) return null
        val id = parts[0].lowercase()
        val x = parts[1].toFloatOrNull() ?: return null
        val y = parts[2].toFloatOrNull() ?: return null
        val shape = if (parts[3].equals("rect", ignoreCase = true)) HitShape.RECT else HitShape.RADIAL
        val rx = parts[4].toFloatOrNull() ?: return null
        val ry = parts[5].toFloatOrNull() ?: return null
        val reachX = kv["${p}reach_x"]?.toFloatOrNull()
        val reachY = kv["${p}reach_y"]?.toFloatOrNull()
        return OverlayDesc(
            index = index,
            id = id,
            action = actionFor(id, kv["${p}next_target"]),
            x = x, y = y, shape = shape, rx = rx, ry = ry,
            image = kv["${p}overlay"]?.takeIf { it.isNotBlank() }?.let { join(cfgDir, it) },
            movable = kv["${p}movable"].toBool(false),
            alphaMod = kv["${p}alpha_mod"]?.toFloatOrNull(),
            rangeMod = kv["${p}range_mod"]?.toFloatOrNull(),
            rangeModExclusive = kv["${p}range_mod_exclusive"].toBool(false),
            exclusive = kv["${p}exclusive"].toBool(false),
            saturatePct = kv["${p}saturate_pct"]?.toFloatOrNull()?.coerceIn(0.1f, 2f) ?: 1f,
            reachLeft = kv["${p}reach_left"]?.toFloatOrNull() ?: reachX ?: 1f,
            reachRight = kv["${p}reach_right"]?.toFloatOrNull() ?: reachX ?: 1f,
            reachUp = kv["${p}reach_up"]?.toFloatOrNull() ?: reachY ?: 1f,
            reachDown = kv["${p}reach_down"]?.toFloatOrNull() ?: reachY ?: 1f,
        )
    }

    private fun actionFor(id: String, nextTarget: String?): DescAction = when (id) {
        "nul", "null", "" -> DescAction.Image
        "dpad_area" -> DescAction.DpadArea
        "abxy_area" -> DescAction.AbxyArea
        "analog_left" -> DescAction.Analog(right = false)
        "analog_right" -> DescAction.Analog(right = true)
        "menu_toggle" -> DescAction.MenuToggle
        "overlay_next" -> DescAction.OverlayNext(nextTarget?.takeIf { it.isNotBlank() })
        "toggle_fast_forward" -> DescAction.FastForward(hold = false)
        "hold_fast_forward" -> DescAction.FastForward(hold = true)
        else -> {
            var mask = 0
            var ok = true
            for (part in id.split('|')) {
                val m = buttonMask(part.trim())
                if (m == null) { ok = false; break }
                mask = mask or m
            }
            if (ok && mask != 0) DescAction.Press(mask) else DescAction.Unsupported
        }
    }

    private fun buttonMask(name: String): Int? = when (name) {
        "a" -> Buttons.A
        "b" -> Buttons.B
        "x" -> Buttons.X
        "y" -> Buttons.Y
        "l", "l1" -> Buttons.L
        "r", "r1" -> Buttons.R
        "l2" -> Buttons.L2
        "r2" -> Buttons.R2
        "l3" -> Buttons.L3
        "r3" -> Buttons.R3
        "start" -> Buttons.START
        "select" -> Buttons.SELECT
        "up" -> Buttons.UP
        "down" -> Buttons.DOWN
        "left" -> Buttons.LEFT
        "right" -> Buttons.RIGHT
        else -> null
    }

    private fun String?.toBool(default: Boolean): Boolean = when (this?.lowercase()) {
        null -> default
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> default
    }

    /** Joins a skin-relative directory and a cfg-relative path, normalising `./` and `..`. */
    fun join(dir: String, rel: String): String {
        val parts = ArrayList<String>()
        for (seg in (dir.replace('\\', '/') + "/" + rel.replace('\\', '/')).split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += seg
            }
        }
        return parts.joinToString("/")
    }
}
