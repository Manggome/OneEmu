package com.manggome.oneemu.emu.input

import com.manggome.oneemu.emu.pad.PadProfile
import com.manggome.oneemu.library.RomInfo.DiscPlatform

/**
 * The controller Dolphin emulates on port 0 for a Wii disc.
 *
 * Dolphin's libretro port picks the device from `retro_set_controller_port_device`, and its default for a
 * Wii title is a bare Wii Remote. Games built around the nunchuk then stop on "눈차크를 연결하세요" before the
 * title screen, which is why this exists: OneEmu hands a Wii disc the Wii Remote + Nunchuk unless the user
 * picks something else. GameCube discs are untouched - Dolphin ignores these ids for them.
 *
 * The numbers are the core's own device ids (`Source/Core/DolphinLibretro/Input.cpp`), which are
 * `RETRO_DEVICE_SUBCLASS`-style values: `(n << 8) | RETRO_DEVICE_JOYPAD`.
 */
enum class WiiController(val key: String, val device: Int, val label: String, val description: String) {
    /** Wii Remote + Nunchuk on a Wii disc, the core's own default on a GameCube disc. */
    AUTO("auto", 0, "자동", "Wii 디스크는 위모컨+눈차크, 게임큐브 디스크는 게임큐브 패드"),
    NUNCHUK("nunchuk", (3 shl 8) or 1, "위모컨 + 눈차크", "대부분의 Wii 게임. 눈차크 연결 안내가 뜨지 않습니다"),
    WIIMOTE("wiimote", 1, "위모컨 단독", "눈차크를 쓰지 않는 게임"),
    CLASSIC("classic", (4 shl 8) or 1, "클래식 컨트롤러", "클래식 컨트롤러를 요구하는 게임 (버추얼 콘솔 등)"),
    GAMECUBE("gc", (6 shl 8) or 1, "게임큐브 패드", "게임큐브 패드를 지원하는 Wii 게임 (스매시브라더스 X 등)");

    /** Which on-screen pad this controller wants: only the Wii Remote family relabels the buttons. */
    fun padProfile(platform: DiscPlatform): PadProfile = when {
        this == AUTO -> if (platform == DiscPlatform.WII) PadProfile.WIIMOTE else PadProfile(com.manggome.oneemu.model.SystemId.GC)
        this == NUNCHUK || this == WIIMOTE -> PadProfile.WIIMOTE
        else -> PadProfile(com.manggome.oneemu.model.SystemId.GC)
    }

    /** The libretro device id to send, or 0 to leave the core's own default in place. */
    fun deviceFor(platform: DiscPlatform): Int = when {
        this != AUTO -> device
        platform == DiscPlatform.WII -> NUNCHUK.device
        else -> 0
    }

    companion object {
        fun fromKey(key: String?): WiiController = entries.firstOrNull { it.key == key } ?: AUTO
    }
}
