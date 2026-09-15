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
    /**
     * Optional, HW-render cores only: minimum GLES version the core's shaders really need (e.g. "3.2"). When set,
     * the frontend refuses to start on a lower device context with a clear error instead of calling the core's
     * context_reset (which would crash or black-screen). Leave empty for cores whose requested version is nominal.
     */
    val glesMinVersion: String = "",
    /**
     * "bundled" (default): the .so ships inside the APK. "download": the .so is fetched on demand from the
     * `cores` GitHub release into <filesDir>/cores/<id>/ (see cores/README.md, [CoreRegistry.libraryPath]).
     */
    val distribution: String = "bundled",
) {
    val isDownloadable: Boolean get() = distribution == DISTRIBUTION_DOWNLOAD

    companion object {
        const val DISTRIBUTION_BUNDLED = "bundled"
        const val DISTRIBUTION_DOWNLOAD = "download"
    }
}
