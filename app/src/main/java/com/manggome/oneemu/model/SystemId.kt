package com.manggome.oneemu.model

import androidx.compose.ui.graphics.Color

/**
 * Emulated systems the app knows about. Order here is the display order of library sections.
 * [extensions] are only hints for the scanner; the authoritative list per core lives in core.json.
 */
enum class SystemId(
    val id: String,
    val displayName: String,
    val shortName: String,
    val color: Color,
    val extensions: Set<String>,
    val hasTouchScreen: Boolean = false,
    val hasAnalog: Boolean = false,
) {
    NES("nes", "닌텐도 패미컴", "NES", Color(0xFFE05A5A), setOf("nes", "fds", "unf", "unif")),
    GB("gb", "게임보이", "GB", Color(0xFF9AA5B1), setOf("gb", "sgb")),
    GBC("gbc", "게임보이 컬러", "GBC", Color(0xFF8E6CF0), setOf("gbc")),
    GBA("gba", "게임보이 어드밴스", "GBA", Color(0xFF5B6CF0), setOf("gba", "agb")),
    NDS("nds", "닌텐도 DS", "NDS", Color(0xFF7FD1C8), setOf("nds", "dsi", "ids"), hasTouchScreen = true),
    N3DS("3ds", "닌텐도 3DS", "3DS", Color(0xFFE8A04A), setOf("3ds", "3dsx", "cci", "cxi", "app", "cia"), hasTouchScreen = true, hasAnalog = true),
    // Sega 8/16-bit, all three on one core. ".bin" is deliberately absent here too: a Mega Drive dump under
    // that name is rare next to how often it is a PS1 track, and .md/.gen/.smd cover the usual ones.
    MD("md", "메가드라이브", "MD", Color(0xFF3B82C4), setOf("md", "gen", "smd", "mdx", "68k")),
    SMS("sms", "마스터 시스템", "SMS", Color(0xFF2E8B57), setOf("sms", "sg")),
    GG("gg", "게임 기어", "GG", Color(0xFF7A5FD3), setOf("gg")),
    // .bin is deliberately absent: it is reached through its .cue (RomScanner skips companion .bin files).
    PSX("psx", "플레이스테이션", "PS1", Color(0xFF9CA3AF), setOf("cue", "img", "pbp", "chd", "iso", "m3u", "mdf", "toc"), hasAnalog = true),
    PSP("psp", "플레이스테이션 포터블", "PSP", Color(0xFF3F8EE0), setOf("iso", "cso", "pbp", "chd", "elf", "prx"), hasAnalog = true),
    PS2("ps2", "플레이스테이션 2", "PS2", Color(0xFF2F5BB8), setOf("iso", "chd", "cso", "isz", "cue", "elf"), hasAnalog = true),
    GC("gc", "닌텐도 게임큐브 / Wii", "GC", Color(0xFF6C4FD6), setOf("iso", "gcm", "gcz", "rvz", "wbfs", "wia", "ciso", "tgc", "dol"), hasAnalog = true),
    ARCADE("arcade", "아케이드 (MAME)", "MAME", Color(0xFFF0C24B), setOf("zip")),

    /** Not an emulated console: an open-source remake that reads the original PC game's data files. */
    JAZZ2("jazz2", "재즈 잭래빗 2", "JJ2", Color(0xFF4CAF50), setOf("j2a"));

    companion object {
        fun fromId(id: String?): SystemId? = entries.firstOrNull { it.id == id }
        val ordered: List<SystemId> get() = entries.toList()
    }
}
