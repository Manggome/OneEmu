package com.manggome.oneemu.emu

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.datastore.preferences.core.stringPreferencesKey
import com.manggome.oneemu.OneEmuApp
import com.manggome.oneemu.model.SystemId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where on the surface the game image is drawn: a normalized rectangle (0..1 of the surface, top-left
 * origin). The native renderer aspect-fits the frame inside it (`VideoGL::present`), so the rectangle is
 * the *bounding box* — the image may be letterboxed within. [FULL] is the legacy whole-surface behaviour.
 */
@Serializable
data class ViewportRect(val x: Float = 0f, val y: Float = 0f, val w: Float = 1f, val h: Float = 1f) {
    val isFull: Boolean get() = x <= 0f && y <= 0f && w >= 1f && h >= 1f

    /** Pixel rectangle on a surface/canvas of [size]. */
    fun toRect(size: Size): Rect = Rect(Offset(x * size.width, y * size.height), Size(w * size.width, h * size.height))

    /** Clamped to the unit square with a minimum size so the image never vanishes. */
    fun normalized(): ViewportRect {
        val cw = w.coerceIn(MIN_SIZE, 1f)
        val ch = h.coerceIn(MIN_SIZE, 1f)
        val cx = x.coerceIn(0f, 1f - cw)
        val cy = y.coerceIn(0f, 1f - ch)
        return ViewportRect(cx, cy, cw, ch)
    }

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val MIN_SIZE = 0.15f
        val FULL = ViewportRect()
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun fromJson(text: String): ViewportRect? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()?.normalized()

        fun fromRect(rect: Rect, size: Size): ViewportRect {
            if (size.width <= 0f || size.height <= 0f) return FULL
            return ViewportRect(rect.left / size.width, rect.top / size.height, rect.width / size.width, rect.height / size.height).normalized()
        }
    }
}

/**
 * Per-(system, screen configuration) viewport persistence. Key: `viewport.<systemId>.<config.key>` = JSON.
 * Unset falls back to [default]: portrait configs anchor the game to the top edge at full width (so the pad
 * area is below it), landscape configs use the whole surface.
 */
object ViewportStore {
    object Keys {
        fun viewport(systemId: String, config: ScreenConfig) = stringPreferencesKey("viewport.$systemId.${config.key}")
    }

    private val settings get() = OneEmuApp.get().settings

    /**
     * Nominal image aspect per system, used for the default viewport height and the editor preview
     * (NDS/3DS: both screens stacked, which is how the cores hand us the frame).
     */
    fun nominalAspect(system: SystemId): Float = when (system) {
        SystemId.JAZZ2 -> 16f / 9f
        SystemId.NES -> 4f / 3f
        SystemId.GB, SystemId.GBC -> 160f / 144f
        SystemId.GBA -> 3f / 2f
        SystemId.NDS -> 256f / 384f
        SystemId.N3DS -> 400f / 480f
        SystemId.PSX -> 4f / 3f
        SystemId.PSP -> 16f / 9f
        SystemId.PS2 -> 4f / 3f
        SystemId.GC -> 4f / 3f
        SystemId.ARCADE -> 4f / 3f
    }

    /**
     * Factory viewport for [system] on a screen of [screen] pixels (aspect only matters). Portrait: flush
     * with the top edge, full width, as tall as the image needs (clamped to the screen). Landscape: full.
     */
    fun default(system: SystemId, config: ScreenConfig, screen: Size): ViewportRect {
        if (config.landscape || screen.width <= 0f || screen.height <= 0f) return ViewportRect.FULL
        val h = (screen.width / nominalAspect(system)) / screen.height
        return ViewportRect(0f, 0f, 1f, h.coerceIn(ViewportRect.MIN_SIZE, 1f))
    }

    /** Saved viewport or null when the user never customised this configuration. */
    fun observeSaved(system: SystemId, config: ScreenConfig): Flow<ViewportRect?> =
        settings.observe(Keys.viewport(system.id, config), "").map { it.takeIf(String::isNotBlank)?.let(ViewportRect::fromJson) }

    suspend fun loadSaved(system: SystemId, config: ScreenConfig): ViewportRect? =
        settings.get(Keys.viewport(system.id, config), "").takeIf(String::isNotBlank)?.let(ViewportRect::fromJson)

    suspend fun save(system: SystemId, config: ScreenConfig, viewport: ViewportRect) =
        settings.set(Keys.viewport(system.id, config), viewport.normalized().toJson())

    suspend fun reset(system: SystemId, config: ScreenConfig) = settings.remove(Keys.viewport(system.id, config))
}
