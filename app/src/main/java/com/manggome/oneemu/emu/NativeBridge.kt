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

    /** Installs a SIGSEGV/SIGBUS/... recorder that writes to [path] (see CrashMarker). */
    external fun installCrashHandler(path: String)
    /** Starts a fresh session log file (frontend + core log lines); see CrashMarker.sessionLogFile. */
    external fun openSessionLog(path: String)
    external fun sessionLogLine(line: String)

    // ---- lifecycle ----
    /**
     * [options] is "key=value\n" lines applied as core option overrides before retro_init.
     * [strictGlesVersion]: refuse to start (GLES_UNSUPPORTED) when the device context is older than the GLES version
     * the core requests; false only warns (for cores whose requested version is nominal).
     */
    /** hwApi: "vulkan" offers Vulkan to the core in GET_PREFERRED_HW_RENDER; anything else offers OpenGL ES 3. */
    fun loadCore(corePath: String, systemDir: String, saveDir: String, options: String, strictGlesVersion: Boolean = false, hwApi: String = "gles3", keepLoaded: Boolean = false, coreAssetsDir: String = systemDir): Boolean =
        loadCoreNative(corePath, systemDir, coreAssetsDir, saveDir, options, strictGlesVersion, hwApi, keepLoaded)
    private external fun loadCoreNative(corePath: String, systemDir: String, coreAssetsDir: String, saveDir: String, options: String, strictGlesVersion: Boolean, hwApi: String, keepLoaded: Boolean): Boolean
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
    /** retro_set_controller_port_device(port, device) on the emu thread. */
    external fun setControllerPortDevice(port: Int, device: Int)

    // ---- runtime config ----
    /** 0 = normal speed, N = N× fast forward, -1 = unlimited. */
    external fun setFastForward(speed: Int)

    /** Autofire: [mask] buttons are released for half of every [framesPerCycle]-frame cycle. */
    external fun setTurbo(mask: Int, framesPerCycle: Int)
    /** aspect: 0 core, 1 stretch, 2 integer, 3 square pixels. rotation: extra quarter turns (0..3) on top
     *  of what the core asked for, counter-clockwise. */
    external fun setVideoConfig(linearFilter: Boolean, aspect: Int, rotation: Int)
    /**
     * Rectangle the game image is aspect-fitted into, normalized 0..1 of the surface with a top-left origin.
     * 0,0,1,1 = whole surface. Applies to the next presented frame, also while paused (live editor preview).
     */
    external fun setViewport(x: Float, y: Float, w: Float, h: Float)
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
