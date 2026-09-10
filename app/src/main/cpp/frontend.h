#pragma once
#include "audio.h"
#include "core.h"
#include "video_gl.h"
#include <android/native_window.h>
#include <atomic>
#include <condition_variable>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

struct CoreOption {
    std::string key;
    std::string desc;
    std::string info;
    std::string category;
    std::vector<std::pair<std::string, std::string>> values; // value, label
    std::string defaultValue;
    std::string current;
    bool visible = true;
};

// Why the last loadCore()/loadGame() (or a later fatal event) failed. Mirrors EmulatorSession.ErrorKind.
enum class LoadError : int {
    None = 0,
    CoreMissing = 1,     // .so not present / not readable
    DlopenFailed = 2,    // dlopen() rejected the library (dlerror in the message)
    CoreInitFailed = 3,  // symbols missing / API version mismatch
    RomReadFailed = 4,   // ROM file unreadable
    RomLoadFailed = 5,   // retro_load_game returned false
    RomEncrypted = 6,    // retro_load_game failed and the core complained about encryption (3DS)
    GlesUnsupported = 7, // the core needs a newer GLES than the device context provides
    GlInitFailed = 8,    // EGL/GLES context could not be created at all
};

struct InputState {
    std::atomic<uint32_t> buttons{0};
    std::atomic<int16_t> analog[2][2]{{{0}, {0}}, {{0}, {0}}}; // [index][axis]
};

// Java-side event sink; implemented in jni.cpp.
struct FrontendListener {
    virtual ~FrontendListener() = default;
    virtual void onMessage(const std::string& msg, unsigned durationMs, int priority) = 0;
    virtual void onRumble(unsigned port, unsigned strength) = 0;
    virtual void onGeometryChanged(unsigned w, unsigned h, float aspect) = 0;
    virtual void onCoreShutdown() = 0;
    virtual void onFatal(const std::string& what, int errorCode) = 0;
    /** Called on the emu thread itself so the JNI layer can attach/detach it once. */
    virtual void onEmuThreadStarted() {}
    virtual void onEmuThreadStopping() {}
};

// Single libretro frontend instance. All core calls happen on the emu thread; public
// methods are thread-safe and either post a command or block until the emu thread answers.
class Frontend {
public:
    static Frontend& get();

    void setListener(FrontendListener* l) { listener_ = l; }

    // strictGlesVersion: fail with GlesUnsupported when the device context is older than what the core
    // requests in SET_HW_RENDER (cores whose shaders need it, e.g. Azahar); otherwise only warn and proceed.
    bool loadCore(const std::string& corePath, const std::string& systemDir, const std::string& saveDir,
                  const std::string& optionOverrides, bool strictGlesVersion, std::string* error);
    bool loadGame(const std::string& romPath, std::string* error);
    void unload();
    LoadError lastErrorCode() const { return lastError_; }
    // Last ~40 core log lines (INFO and up), core on-screen messages and frontend load diagnostics, newest last.
    std::string recentLog();
    // Appends a line to the ring buffer exposed by recentLog(); also used by the retro log callback.
    void noteLog(char level, const std::string& line);

    void setSurface(ANativeWindow* window);
    void setSurfaceSize(int w, int h);
    void setPaused(bool paused);
    bool isPaused() const { return paused_; }
    bool isRunning() const { return gameLoaded_; }

    void setInput(unsigned port, uint32_t buttons, int16_t lx, int16_t ly, int16_t rx, int16_t ry);
    void setPointer(int16_t x, int16_t y, bool pressed);
    void setFastForward(int speed); // 0 = off, 1..N = N×, -1 = unlimited
    void setVideoConfig(bool linear, int aspectMode);
    void setAudioMuted(bool muted);

    bool saveState(const std::string& path);
    bool loadState(const std::string& path);
    bool saveSram();
    void reset();
    bool screenshot(std::vector<uint32_t>& rgba, int& w, int& h);

    void setCheat(unsigned index, bool enabled, const std::string& code);
    void resetCheats();

    std::vector<CoreOption> options();
    void setOption(const std::string& key, const std::string& value);

    retro_system_info systemInfo() const { return sysInfo_; }
    retro_system_av_info avInfo() const { return avInfo_; }
    double fps() const { return measuredFps_; }
    std::string coreName() const { return sysInfo_.library_name ? sysInfo_.library_name : ""; }

    // ----- callbacks from the core (public because the C trampolines need them) -----
    bool environment(unsigned cmd, void* data);
    void videoRefresh(const void* data, unsigned w, unsigned h, size_t pitch);
    void audioSample(int16_t l, int16_t r);
    size_t audioSampleBatch(const int16_t* data, size_t frames);
    void inputPoll() {}
    uintptr_t hwFramebufferForCore();
    bool rumble(unsigned port, unsigned strength);
    int16_t inputState(unsigned port, unsigned device, unsigned index, unsigned id);

private:
    Frontend() = default;

    using Command = std::function<void()>;
    void post(Command c);
    void run(Command c); // blocking
    void threadMain();
    void startThread();
    void stopThread();

    void runFrame();
    void ensureGlReady();
    void contextResetIfNeeded();
    std::string sramPath() const;
    bool saveSramInternal();
    void loadSram();
    void applyOptionOverrides(const std::string& overrides);
    void logMemoryInfo();
    void fail(LoadError code, const std::string& what, std::string* error);
    void setOptionsFromV2(const retro_core_options_v2* v2);
    void setOptionsFromV1(const retro_core_option_definition* defs);
    void setOptionsFromVariables(const retro_variable* vars);
    void setOptionsFromV1Intl(const retro_core_options_intl* intl);
    void setOptionsFromV2Intl(const retro_core_options_v2_intl* intl);

    // state
    LibretroCore core_;
    FrontendListener* listener_ = nullptr;
    std::string corePath_, systemDir_, saveDir_, romPath_, romBase_;
    std::vector<uint8_t> romData_;
    retro_system_info sysInfo_{};
    retro_system_av_info avInfo_{};
    std::atomic<bool> gameLoaded_{false};
    std::atomic<bool> paused_{true};
    std::atomic<bool> shutdownRequested_{false};
    LoadError lastError_ = LoadError::None;
    std::mutex logMutex_;
    std::vector<std::string> logRing_;
    size_t logNext_ = 0;
    std::string lastCoreMessage_;

    // thread + command queue
    std::thread thread_;
    std::atomic<bool> threadRunning_{false};
    std::mutex queueMutex_;
    std::condition_variable queueCv_;
    std::vector<Command> queue_;

    // video
    VideoGL video_;
    VideoConfig videoCfg_;
    ANativeWindow* pendingWindow_ = nullptr;
    std::atomic<bool> windowDirty_{false};
    std::mutex windowMutex_;
    int pixelFormat_ = RETRO_PIXEL_FORMAT_0RGB1555;
    bool hwRender_ = false;
    retro_hw_render_callback hwCb_{};
    bool hwContextReady_ = false;
    bool hwUnsupported_ = false; // context version check failed; don't retry context_reset every frame
    bool strictGlesVersion_ = false;
    bool frameIsHw_ = false;
    unsigned hwFboW_ = 0, hwFboH_ = 0;
    unsigned hwFboGrowW_ = 0, hwFboGrowH_ = 0;
    bool hwFboNeedsGrow_ = false;
    bool gotFrameThisRun_ = false;
    std::atomic<int> fastForward_{0};
    unsigned ffFrameCounter_ = 0;
    std::atomic<bool> videoCfgDirty_{false};
    bool linearFilterPending_ = false;
    int aspectModePending_ = 0;

    // audio
    AudioOutput audio_;
    std::vector<int16_t> audioBuf_;

    // input
    InputState input_[4];
    std::atomic<int16_t> pointerX_{0}, pointerY_{0};
    std::atomic<bool> pointerPressed_{false};
    bool supportsBitmasks_ = false;

    // options
    std::mutex optionsMutex_;
    std::vector<CoreOption> options_;
    std::map<std::string, std::string> optionOverrides_;
    std::atomic<bool> optionsDirty_{false};

    // timing
    double measuredFps_ = 0;
    int64_t nextFrameNs_ = 0;
    int64_t fpsWindowStartNs_ = 0;
    unsigned fpsFrames_ = 0;
    int64_t lastSramSaveNs_ = 0;
    std::vector<uint8_t> lastSram_;

    // rumble/frame time
    retro_frame_time_callback frameTimeCb_{};
    int64_t lastFrameTimeNs_ = 0;
    retro_disk_control_ext_callback diskCb_{};
    unsigned rotation_ = 0;
};
