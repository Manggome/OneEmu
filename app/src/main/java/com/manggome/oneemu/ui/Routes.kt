package com.manggome.oneemu.ui

/**
 * Navigation graph of the main activity (the emulator runs in its own activity).
 * Feature modules add composables for these routes in MainActivity's NavHost.
 */
object Routes {
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val SETTINGS_VIDEO = "settings/video"
    const val SETTINGS_AUDIO = "settings/audio"
    const val SETTINGS_INPUT = "settings/input"
    const val SETTINGS_GAMEPAD = "settings/gamepad"
    const val SETTINGS_LAYOUTS = "settings/layouts"
    const val SETTINGS_CORES = "settings/cores"
    const val SETTINGS_MISC = "settings/misc"
    const val SETTINGS_SAVEDATA = "settings/savedata"
    const val SETTINGS_ABOUT = "settings/about"
    const val FOLDERS = "folders"

    /** Core option editor: settings/core/{coreId} */
    const val CORE_OPTIONS = "settings/core/{coreId}"
    fun coreOptions(coreId: String) = "settings/core/$coreId"

    /** The same editor, but storing what it changes against one game: settings/core/{coreId}/game/{gameId} */
    const val GAME_CORE_OPTIONS = "settings/core/{coreId}/game/{gameId}"
    fun gameCoreOptions(coreId: String, gameId: Long) = "settings/core/$coreId/game/$gameId"

    /** Pad skin picker for one pad: settings/skins/{profile}, a [PadProfile.key] (usually a system id). */
    const val SKINS = "settings/skins/{profile}"
    fun skins(profileKey: String) = "settings/skins/$profileKey"

    /** Button mapping for one controller: settings/gamepad/{deviceKey} */
    const val GAMEPAD_MAPPING = "settings/gamepad/{deviceKey}"
    fun gamepadMapping(deviceKey: String) = "settings/gamepad/${android.net.Uri.encode(deviceKey)}"

    /** Theme picker */
    const val SETTINGS_THEME = "settings/theme"

    /** Virtual pad layout editor: layout/{profile}, where profile is a [PadProfile.key] (usually a system id). */
    const val LAYOUT_EDITOR = "layout/{profile}"
    fun layoutEditor(profileKey: String) = "layout/$profileKey"

    /** Box art picker for one game: boxart/{gameId} */
    const val BOXART = "boxart/{gameId}"
    fun boxArt(gameId: Long) = "boxart/$gameId"

    /** Game detail sheet route: game/{gameId} */
    const val GAME = "game/{gameId}"
    fun game(gameId: Long) = "game/$gameId"
}
