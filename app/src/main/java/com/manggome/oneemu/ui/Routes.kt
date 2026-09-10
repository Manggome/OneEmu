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
    const val SETTINGS_LAYOUTS = "settings/layouts"
    const val SETTINGS_CORES = "settings/cores"
    const val SETTINGS_MISC = "settings/misc"
    const val SETTINGS_ABOUT = "settings/about"
    const val FOLDERS = "folders"

    /** Core option editor: settings/core/{coreId} */
    const val CORE_OPTIONS = "settings/core/{coreId}"
    fun coreOptions(coreId: String) = "settings/core/$coreId"

    /** Virtual pad layout editor: layout/{systemId} */
    const val LAYOUT_EDITOR = "layout/{systemId}"
    fun layoutEditor(systemId: String) = "layout/$systemId"

    /** Game detail sheet route: game/{gameId} */
    const val GAME = "game/{gameId}"
    fun game(gameId: Long) = "game/$gameId"
}
