package com.manggome.oneemu.emu.skin

import com.manggome.oneemu.emu.EmulatorSession.Buttons
import com.manggome.oneemu.model.SystemId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.abs
import kotlin.math.ln

/*
 * Delta controller skins (`.deltaskin`; ManicEMU ships the same format as `.manicskin`): a zip with an
 * `info.json` and the pictures it names (PDF or PNG).
 *
 * Supported subset of info.json:
 *  - `name`, `identifier`, `gameTypeIdentifier`
 *  - `representations` → device (`iphone`, `ipad`, …) → display (`standard`, `edgeToEdge`, …) →
 *    `portrait` / `landscape` →
 *      `assets` (`resizable` PDF, else `large` / `medium` / `small`), `mappingSize`, `translucent`,
 *      `extendedEdges` (default for every item), `screens[].outputFrame` or the older `gameScreenFrame`,
 *      `items[]` with `inputs` (string, array or direction dictionary), `frame`, `extendedEdges`,
 *      `thumbstick` (`name`, `width`, `height`) and ManicEMU's per-item `asset.normal` picture.
 * Not supported: `screens[].inputFrame` cropping, screen filters, `menuInsets`, ManicEMU's flex/animation keys.
 */

data class DeltaRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val bottom: Float get() = y + height
    fun union(o: DeltaRect): DeltaRect {
        val l = minOf(x, o.x); val t = minOf(y, o.y)
        return DeltaRect(l, t, maxOf(right, o.right) - l, maxOf(bottom, o.bottom) - t)
    }
}

/** Extra touch margin per side in mapping units; null = not given (falls back to the representation's). */
data class DeltaEdges(val top: Float? = null, val bottom: Float? = null, val left: Float? = null, val right: Float? = null) {
    fun orElse(base: DeltaEdges) = DeltaEdges(top ?: base.top, bottom ?: base.bottom, left ?: base.left, right ?: base.right)
    companion object { val NONE = DeltaEdges() }
}

sealed interface DeltaInputs {
    /** All of these are pressed together while the item is touched. */
    data class Press(val names: List<String>) : DeltaInputs
    /** A d-pad or stick: which input each direction of the finger produces. */
    data class Directional(val up: String?, val down: String?, val left: String?, val right: String?) : DeltaInputs {
        val values: List<String> get() = listOfNotNull(up, down, left, right)
    }
}

data class DeltaThumbstick(val name: String, val width: Float, val height: Float)

data class DeltaItem(
    val inputs: DeltaInputs,
    val frame: DeltaRect,
    val extendedEdges: DeltaEdges,
    /** Picture of this button alone (ManicEMU `asset.normal`), drawn in [frame]. */
    val asset: String?,
    val thumbstick: DeltaThumbstick?,
)

data class DeltaRepresentation(
    val device: String,
    val display: String,
    val landscape: Boolean,
    /** Background picture (skin-relative path), null when the representation names none. */
    val background: String?,
    val mappingWidth: Float,
    val mappingHeight: Float,
    val items: List<DeltaItem>,
    /** Where the game picture goes, in mapping units (one per screen; DS/3DS have two). */
    val screens: List<DeltaRect>,
    val translucent: Boolean,
) {
    val aspect: Float get() = mappingWidth / mappingHeight
    val label: String get() = "$device $display ${if (landscape) "landscape" else "portrait"}"
}

data class DeltaSkinInfo(
    val name: String,
    val identifier: String,
    val gameTypeIdentifier: String,
    val representations: List<DeltaRepresentation>,
)

class DeltaSkinException(message: String) : Exception(message)

object DeltaSkinParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): DeltaSkinInfo {
        val root = runCatching { json.parseToJsonElement(text.trimStart('﻿')) }.getOrNull() as? JsonObject
            ?: throw DeltaSkinException("info.json is not a JSON object")
        val gameType = root.str("gameTypeIdentifier") ?: throw DeltaSkinException("no gameTypeIdentifier")
        val reps = ArrayList<DeltaRepresentation>()
        val representations = root["representations"] as? JsonObject ?: throw DeltaSkinException("no representations")
        for ((device, displays) in representations) {
            if (displays !is JsonObject) continue
            for ((display, orientations) in displays) {
                if (orientations !is JsonObject) continue
                for ((orientation, body) in orientations) {
                    val landscape = when (orientation.lowercase()) {
                        "portrait" -> false
                        "landscape" -> true
                        else -> continue
                    }
                    if (body !is JsonObject) continue
                    parseRepresentation(device, display, landscape, body)?.let(reps::add)
                }
            }
        }
        if (reps.isEmpty()) throw DeltaSkinException("no usable representation")
        return DeltaSkinInfo(
            name = root.str("name")?.ifBlank { null } ?: root.str("identifier") ?: "Delta skin",
            identifier = root.str("identifier").orEmpty(),
            gameTypeIdentifier = gameType,
            representations = reps,
        )
    }

    private fun parseRepresentation(device: String, display: String, landscape: Boolean, o: JsonObject): DeltaRepresentation? {
        val mapping = o["mappingSize"] as? JsonObject ?: return null
        val mw = mapping.num("width") ?: return null
        val mh = mapping.num("height") ?: return null
        if (mw <= 0f || mh <= 0f) return null
        val defaultEdges = edges(o["extendedEdges"]) ?: DeltaEdges.NONE
        val items = (o["items"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { item -> parseItem(item, defaultEdges) } }
        val screens = ArrayList<DeltaRect>()
        (o["screens"] as? JsonArray)?.forEach { s -> ((s as? JsonObject)?.get("outputFrame"))?.let(::rect)?.let(screens::add) }
        if (screens.isEmpty()) rect(o["gameScreenFrame"])?.let(screens::add)
        return DeltaRepresentation(
            device = device, display = display, landscape = landscape,
            background = assetPath(o["assets"]),
            mappingWidth = mw, mappingHeight = mh,
            items = items, screens = screens,
            translucent = (o["translucent"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    private fun parseItem(o: JsonObject, defaultEdges: DeltaEdges): DeltaItem? {
        val frame = rect(o["frame"]) ?: return null
        val inputs = when (val i = o["inputs"]) {
            is JsonPrimitive -> i.contentOrNull?.let { DeltaInputs.Press(listOf(it)) }
            is JsonArray -> DeltaInputs.Press(i.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
            is JsonObject -> DeltaInputs.Directional(i.str("up"), i.str("down"), i.str("left"), i.str("right"))
            else -> null
        } ?: return null
        val thumb = (o["thumbstick"] as? JsonObject)?.let { t ->
            val name = t.str("name") ?: return@let null
            DeltaThumbstick(name, t.num("width") ?: frame.width, t.num("height") ?: frame.height)
        }
        val asset = when (val a = o["asset"]) {
            is JsonObject -> a.str("normal")
            is JsonPrimitive -> a.contentOrNull
            else -> null
        }?.ifBlank { null }
        return DeltaItem(inputs, frame, (edges(o["extendedEdges"]) ?: DeltaEdges.NONE).orElse(defaultEdges), asset, thumb)
    }

    /** `resizable` (a PDF) wins, then the largest bitmap. */
    private fun assetPath(e: JsonElement?): String? {
        val o = e as? JsonObject ?: return (e as? JsonPrimitive)?.contentOrNull?.ifBlank { null }
        for (key in listOf("resizable", "large", "medium", "small")) o.str(key)?.ifBlank { null }?.let { return it }
        return o.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.ifBlank { null } }
    }

    private fun rect(e: JsonElement?): DeltaRect? {
        val o = e as? JsonObject ?: return null
        val w = o.num("width") ?: return null
        val h = o.num("height") ?: return null
        return DeltaRect(o.num("x") ?: 0f, o.num("y") ?: 0f, w, h)
    }

    private fun edges(e: JsonElement?): DeltaEdges? {
        val o = e as? JsonObject ?: return null
        return DeltaEdges(o.num("top"), o.num("bottom"), o.num("left"), o.num("right"))
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.num(key: String): Float? = (this[key] as? JsonPrimitive)?.let { it.floatOrNull ?: it.contentOrNull?.toFloatOrNull() }
}

/** Delta / ManicEMU `gameTypeIdentifier` → OneEmu systems the skin can be worn on. */
object DeltaGameTypes {
    fun systemsFor(gameTypeIdentifier: String): Set<SystemId> = when (gameTypeIdentifier.trim().lowercase().substringAfterLast('.')) {
        "nes", "fds", "famicom" -> setOf(SystemId.NES)
        // A Game Boy skin and a Game Boy Color skin fit either machine.
        "gb", "gbc" -> setOf(SystemId.GB, SystemId.GBC)
        "gba" -> setOf(SystemId.GBA)
        "ds", "nds" -> setOf(SystemId.NDS)
        "3ds" -> setOf(SystemId.N3DS)
        "genesis", "md", "megadrive" -> setOf(SystemId.MD)
        "ms", "sms", "mastersystem" -> setOf(SystemId.SMS)
        "gg", "gamegear" -> setOf(SystemId.GG)
        "ps1", "psx", "playstation" -> setOf(SystemId.PSX)
        "psp" -> setOf(SystemId.PSP)
        "ps2" -> setOf(SystemId.PS2)
        "ngc", "gc", "gamecube" -> setOf(SystemId.GC)
        "arcade", "mame" -> setOf(SystemId.ARCADE)
        else -> emptySet()
    }

    /** The system whose button names the skin uses (GB/GBC share one). */
    fun primarySystem(gameTypeIdentifier: String): SystemId? = systemsFor(gameTypeIdentifier).firstOrNull()
}

/** Delta input names → OneEmu pad actions. */
object DeltaInputMap {
    private val DIRECTIONS = setOf("up", "down", "left", "right")

    /** libretro button for one Delta input name on [system], or null when OneEmu has nothing for it. */
    fun buttonMask(system: SystemId, name: String): Int? {
        val n = name.trim().lowercase()
        when (system) {
            // Genesis Plus GX: RetroPad Y/B/A = A/B/C, L/X/R = X/Y/Z, Select = Mode.
            SystemId.MD -> when (n) {
                "a" -> return Buttons.Y
                "b" -> return Buttons.B
                "c" -> return Buttons.A
                "x" -> return Buttons.L
                "y" -> return Buttons.X
                "z" -> return Buttons.R
                "mode" -> return Buttons.SELECT
            }
            // Dolphin: L2/R2 are the analog L/R triggers, R is Z.
            SystemId.GC -> when (n) {
                "l" -> return Buttons.L2
                "r" -> return Buttons.R2
                "z" -> return Buttons.R
            }
            else -> {}
        }
        return when (n) {
            "a", "circle" -> Buttons.A
            "b", "cross" -> Buttons.B
            "x", "triangle" -> Buttons.X
            "y", "square" -> Buttons.Y
            "l", "l1" -> Buttons.L
            "r", "r1" -> Buttons.R
            "l2", "zl" -> Buttons.L2
            "r2", "zr" -> Buttons.R2
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
    }

    /** App actions (not pad buttons) a Delta skin can carry. */
    fun hotkey(name: String): DescAction? = when (name.trim().lowercase()) {
        "menu" -> DescAction.MenuToggle
        "fastforward" -> DescAction.FastForward(hold = true)
        "togglefastforward" -> DescAction.FastForward(hold = false)
        "quicksave" -> DescAction.QuickSave
        "quickload" -> DescAction.QuickLoad
        else -> null
    }

    /** What touching [inputs] does on [system]; null = nothing OneEmu supports (the item is dropped). */
    fun action(system: SystemId, inputs: DeltaInputs): DescAction? = when (inputs) {
        is DeltaInputs.Press -> pressAction(system, inputs.names)
        is DeltaInputs.Directional -> directionalAction(system, inputs)
    }

    private fun pressAction(system: SystemId, names: List<String>): DescAction? {
        if (names.isEmpty()) return null
        val lower = names.map { it.trim().lowercase() }
        // Old skins list a whole d-pad as one item: ["up", "down", "left", "right"].
        if (lower.size >= 3 && lower.all { it in DIRECTIONS }) return DescAction.DpadArea
        var mask = 0
        for (n in names) mask = mask or (buttonMask(system, n) ?: 0)
        if (mask != 0) return DescAction.Press(mask)
        return names.firstNotNullOfOrNull { hotkey(it) }
    }

    private fun directionalAction(system: SystemId, d: DeltaInputs.Directional): DescAction? {
        val values = d.values.map { it.trim().lowercase() }
        if (values.isEmpty()) return null
        return when {
            values.all { it in DIRECTIONS } -> DescAction.DpadArea
            values.all { it.startsWith("leftthumbstick") || it.startsWith("analogstick") } ->
                if (system.hasAnalog) DescAction.Analog(right = false) else DescAction.DpadArea
            values.all { it.startsWith("rightthumbstick") } ->
                if (system.hasAnalog) DescAction.Analog(right = true) else null
            // touchScreenX/Y: the game picture itself takes touches; cUp/rightDpad…: nothing to send them to.
            else -> null
        }
    }
}

/** Picking a representation for the phone and turning it into the [Overlay] the image pad already plays. */
object DeltaSkinLayout {
    /**
     * Representations for one orientation, best first: the closest aspect to the screen (an iPhone
     * edge-to-edge layout on a modern phone, an iPad layout on a tablet or an unfolded Fold), edge-to-edge
     * before standard on a tie. Only those with a background picture count.
     */
    fun candidates(skin: DeltaSkinInfo, landscape: Boolean, screenAspect: Float): List<DeltaRepresentation> {
        val target = screenAspect.takeIf { it > 0f } ?: (if (landscape) 2f else 0.5f)
        return skin.representations
            .filter { it.landscape == landscape && it.background != null }
            .sortedWith(compareBy<DeltaRepresentation>({ abs(ln(it.aspect / target)) }).thenBy { if (it.display.equals("edgeToEdge", true)) 0 else 1 })
    }

    /** The first candidate that converts, or null (the app then uses its built-in pad for this orientation). */
    fun pick(skin: DeltaSkinInfo, landscape: Boolean, screenAspect: Float, system: SystemId): Pair<DeltaRepresentation, Overlay>? {
        for (rep in candidates(skin, landscape, screenAspect)) {
            toOverlay(rep, system)?.let { return rep to it }
        }
        return null
    }

    /**
     * Converts one representation. Null when nothing on it is a pad control OneEmu can press, or when its
     * screens sit side by side (the DS/3DS cores hand over the two screens stacked, one picture).
     */
    fun toOverlay(rep: DeltaRepresentation, system: SystemId, index: Int = 0): Overlay? {
        val mw = rep.mappingWidth
        val mh = rep.mappingHeight
        if (rep.screens.size > 1 && sideBySide(rep.screens)) return null
        val descs = ArrayList<OverlayDesc>(rep.items.size)
        for (item in rep.items) {
            val action = DeltaInputMap.action(system, item.inputs) ?: continue
            val f = item.frame
            if (f.width <= 0f || f.height <= 0f) continue
            val hw = f.width / 2f
            val hh = f.height / 2f
            val e = item.extendedEdges
            val thumb = item.thumbstick?.takeIf { action is DescAction.Analog || action == DescAction.DpadArea }
            descs += OverlayDesc(
                index = descs.size,
                id = when (val i = item.inputs) {
                    is DeltaInputs.Press -> i.names.joinToString("|")
                    is DeltaInputs.Directional -> i.values.joinToString("|")
                },
                action = action,
                x = (f.x + hw) / mw, y = (f.y + hh) / mh,
                shape = HitShape.RECT,
                rx = hw / mw, ry = hh / mh,
                image = thumb?.name ?: item.asset,
                movable = false, alphaMod = null, rangeMod = null, rangeModExclusive = false, exclusive = false,
                saturatePct = 1f,
                reachLeft = 1f + (e.left ?: 0f).coerceAtLeast(0f) / hw,
                reachRight = 1f + (e.right ?: 0f).coerceAtLeast(0f) / hw,
                reachUp = 1f + (e.top ?: 0f).coerceAtLeast(0f) / hh,
                reachDown = 1f + (e.bottom ?: 0f).coerceAtLeast(0f) / hh,
                artInBackground = true,
                imageScaleX = thumb?.let { it.width / f.width } ?: 1f,
                imageScaleY = thumb?.let { it.height / f.height } ?: 1f,
            )
        }
        if (descs.none { it.action.isPad }) return null
        val screen = rep.screens.reduceOrNull { a, b -> a.union(b) }
        return Overlay(
            index = index,
            name = rep.label,
            fullScreen = false,
            normalized = true,
            backgroundImage = rep.background,
            aspectRatio = mw / mh,
            blockXSeparation = true,
            blockYSeparation = true,
            rangeMod = 1f,
            alphaMod = 1f,
            next = null,
            descs = descs,
            delta = true,
            translucent = rep.translucent,
            gameScreen = screen?.let { NormRect(it.x / mw, it.y / mh, it.width / mw, it.height / mh) },
        )
    }

    /** Two screens whose horizontal spans do not overlap are side by side rather than stacked. */
    fun sideBySide(screens: List<DeltaRect>): Boolean {
        for (i in screens.indices) for (j in i + 1 until screens.size) {
            val a = screens[i]; val b = screens[j]
            val overlapX = minOf(a.right, b.right) - maxOf(a.x, b.x)
            if (overlapX <= 0.25f * minOf(a.width, b.width)) return true
        }
        return false
    }
}
