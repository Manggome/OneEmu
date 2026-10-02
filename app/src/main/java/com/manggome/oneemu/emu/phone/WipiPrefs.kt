package com.manggome.oneemu.emu.phone

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.manggome.oneemu.data.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Feature-phone (WIPI) screen settings: how the handset keypad looks, where it sits and how big it and the game
 * picture are. Portrait and landscape keep their own arrangement, like a phone held upright versus sideways.
 */
object WipiPrefs {
    enum class Design { CLASSIC, FLAT }

    /** PHONE: screen above the handset keys. TWO_HAND: screen in the middle, keys on both sides. OVERLAY: keys over a full picture. */
    enum class Arrangement { PHONE, TWO_HAND, OVERLAY }

    val DESIGN = stringPreferencesKey("wipi.keypad_design")
    val ARRANGEMENT_PORTRAIT = stringPreferencesKey("wipi.arrangement_portrait")
    val ARRANGEMENT_LANDSCAPE = stringPreferencesKey("wipi.arrangement_landscape")
    val KEYPAD_SCALE = floatPreferencesKey("wipi.keypad_scale")
    val SCREEN_SCALE = floatPreferencesKey("wipi.screen_scale")
    val OVERLAY_OPACITY = floatPreferencesKey("wipi.overlay_opacity")
    val SHOW_KEYPAD = booleanPreferencesKey("wipi.show_keypad")
    val FOLD_DPAD = booleanPreferencesKey("wipi.fold_dpad")
    val HIDE_WITH_PAD = booleanPreferencesKey("wipi.hide_keypad_with_pad")
    val STRETCH = booleanPreferencesKey("wipi.stretch_without_keypad")

    const val KEYPAD_SCALE_MIN = 0.6f
    const val KEYPAD_SCALE_MAX = 1.0f
    const val SCREEN_SCALE_MIN = 0.5f

    data class State(
        val design: Design = Design.CLASSIC,
        val portrait: Arrangement = Arrangement.PHONE,
        val landscape: Arrangement = Arrangement.TWO_HAND,
        val keypadScale: Float = 1f,
        val screenScale: Float = 1f,
        val overlayOpacity: Float = 0.45f,
        val showKeypad: Boolean = true,
        val foldDpad: Boolean = false,
        /** No handset keys while a gamepad is connected: the picture gets the whole screen. */
        val hideWithPad: Boolean = true,
        /** Without the keypad, stretch the picture over the screen instead of keeping the game's shape. */
        val stretch: Boolean = false,
    ) {
        fun arrangement(landscapeNow: Boolean): Arrangement = if (landscapeNow) landscape else portrait
    }

    fun observe(settings: Settings): Flow<State> = combine(
        observeKeypad(settings),
        settings.observe(HIDE_WITH_PAD, true),
        settings.observe(STRETCH, false),
    ) { s, hide, stretch -> s.copy(hideWithPad = hide, stretch = stretch) }

    private fun observeKeypad(settings: Settings): Flow<State> = combine(
        combine(
            settings.observe(DESIGN, Design.CLASSIC.name),
            settings.observe(ARRANGEMENT_PORTRAIT, Arrangement.PHONE.name),
            settings.observe(ARRANGEMENT_LANDSCAPE, Arrangement.TWO_HAND.name),
            settings.observe(FOLD_DPAD, false),
        ) { d, p, l, fold -> listOf(d, p, l, fold) },
        combine(
            settings.observe(KEYPAD_SCALE, 1f),
            settings.observe(SCREEN_SCALE, 1f),
            settings.observe(OVERLAY_OPACITY, 0.45f),
            settings.observe(SHOW_KEYPAD, true),
        ) { k, s, o, show -> listOf(k, s, o, show) },
    ) { a, b ->
        State(
            design = enumOr(a[0] as String, Design.CLASSIC),
            portrait = enumOr(a[1] as String, Arrangement.PHONE),
            landscape = enumOr(a[2] as String, Arrangement.TWO_HAND),
            foldDpad = a[3] as Boolean,
            keypadScale = (b[0] as Float).coerceIn(KEYPAD_SCALE_MIN, KEYPAD_SCALE_MAX),
            screenScale = (b[1] as Float).coerceIn(SCREEN_SCALE_MIN, 1f),
            overlayOpacity = (b[2] as Float).coerceIn(0f, 1f),
            showKeypad = b[3] as Boolean,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
        runCatching { enumValueOf<E>(name) }.getOrDefault(default)
}
