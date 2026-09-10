package com.manggome.oneemu.core

import kotlinx.serialization.Serializable

@Serializable
data class BiosEntry(
    val system: String = "",
    val file: String,
    val required: Boolean = false,
    val description: String = "",
)

/** Mirrors cores/<id>/core.json. */
@Serializable
data class CoreInfo(
    val id: String,
    val displayName: String,
    val libFile: String,
    val systems: List<String>,
    val extensions: List<String>,
    val needFullPath: Boolean = false,
    val hwRender: String = "none",
    val bios: List<BiosEntry> = emptyList(),
    val sourceRepo: String = "",
    val sourceCommit: String = "",
    val license: String = "",
    val defaultOptions: Map<String, String> = emptyMap(),
    val notes: String = "",
    /** Optional: subfolder under coreassets/<id>/ that must be installed into <systemDir>/<assetsInstallDir>. */
    val assetsInstallDir: String = "",
)
