package com.manggome.oneemu.emu

import android.view.Surface

/**
 * JNI surface of the C++ libretro frontend. All methods are safe to call from any thread;
 * the native side marshals them onto its own emulation thread.
 */
object NativeBridge {
    init {
        System.loadLibrary("oneemu")
    }

    /** Callbacks the native side invokes; set by [EmulatorSession]. */
    @Volatile var listener: Listener? = null

    interface Listener {
        fun onCoreMessage(message: String, durationMs: Int, priority: Int)
        fun onRumble(port: Int, strength: Int)
        fun onGeometryChanged(width: Int, height: Int, aspect: Float)
        fun onCoreShutdown()
        /** [errorCode] is a [Frontend LoadError] value; see EmulatorSession.ErrorKind.fromCode. */
        fun onFatal(what: String, errorCode: Int)
    }

    // ---- called from C++ ----
    @JvmStatic fun onCoreMessage(message: String, durationMs: Int, priority: Int) = listener?.onCoreMessage(message, durationMs, priority) ?: Unit
    @JvmStatic fun onRumble(port: Int, strength: Int) = listener?.onRumble(port, strength) ?: Unit
    @JvmStatic fun onGeometryChanged(width: Int, height: Int, aspect: Float) = listener?.onGeometryChanged(width, height, aspect) ?: Unit
    @JvmStatic fun onCoreShutdown() = listener?.onCoreShutdown() ?: Unit
    @JvmStatic fun onFatal(what: String, errorCode: Int) = listener?.onFatal(what, errorCode) ?: Unit

    // ---- lifecycle ----
    /**
     * [options] is "key=value\n" lines applied as core option overrides before retro_init.
     * [strictGlesVersion]: refuse to start (GLES_UNSUPPORTED) when the device context is older than the GLES version
     * the core requests; false only warns (for cores whose requested version is nominal).
     */
    fun loadCore(corePath: String, systemDir: String, saveDir: String, options: String, strictGlesVersion: Boolean = false): Boolean =
        loadCoreNative(corePath, systemDir, saveDir, options, strictGlesVersion)
    private external fun loadCoreNative(corePath: String, systemDir: String, saveDir: String, options: String, strictGlesVersion: Boolean): Boolean
    external fun loadGame(romPath: String): Boolean
    external fun lastError(): String
    /** Numeric reason of the last load failure (0 = none); see EmulatorSession.ErrorKind.fromCode. */
    external fun lastErrorCode(): Int
    /** Last ~40 core log lines (INFO+), core on-screen messages ([M]) and frontend load diagnostics. */
    external fun getRecentCoreLog(): String
    external fun unload()
    external fun setSurface(surface: Surface?)
    external fun setSurfaceSize(width: Int, height: Int)
    external fun setPaused(paused: Boolean)
    external fun isRunning(): Boolean

    // ---- input ----
    external fun setInput(port: Int, buttons: Int, lx: Int, ly: Int, rx: Int, ry: Int)
    /** x/y in libretro pointer space: -0x7fff..0x7fff across the core's framebuffer. */
    external fun setPointer(x: Int, y: Int, pressed: Boolean)

    // ---- runtime config ----
    /** 0 = normal speed, N = N× fast forward, -1 = unlimited. */
    external fun setFastForward(speed: Int)
    /** aspect: 0 core, 1 stretch, 2 integer, 3 square pixels. */
    external fun setVideoConfig(linearFilter: Boolean, aspect: Int)
    external fun setAudioMuted(muted: Boolean)

    // ---- state ----
    external fun saveState(path: String): Boolean
    external fun loadState(path: String): Boolean
    external fun saveSram(): Boolean
    external fun reset()
    /** Returns [w, h, argb...] or null. */
    external fun screenshot(): IntArray?
    external fun setCheat(index: Int, enabled: Boolean, code: String)
    external fun resetCheats()

    // ---- info ----
    external fun getOptions(): String
    external fun setOption(key: String, value: String)
    /** [baseW, baseH, maxW, maxH, aspect*10000, fps*1000, sampleRate] */
    external fun getAvInfo(): IntArray
    external fun getFps(): Double
    external fun getCoreName(): String
}
