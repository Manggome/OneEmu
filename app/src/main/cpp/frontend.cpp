#include "frontend.h"
#include "log.h"
#include <EGL/egl.h>
#include <algorithm>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sys/stat.h>
#include <time.h>

static int64_t nowNs() {
    timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static void sleepUntil(int64_t targetNs) {
    int64_t now = nowNs();
    int64_t remaining = targetNs - now;
    if (remaining > 2000000) {
        timespec ts{ (time_t)((remaining - 1000000) / 1000000000LL), (long)((remaining - 1000000) % 1000000000LL) };
        nanosleep(&ts, nullptr);
    }
    while (nowNs() < targetNs) { /* spin the last ms for accuracy */ }
}

static bool readFile(const std::string& path, std::vector<uint8_t>& out) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return false;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (n < 0) { fclose(f); return false; }
    out.resize((size_t)n);
    bool ok = n == 0 || fread(out.data(), 1, (size_t)n, f) == (size_t)n;
    fclose(f);
    return ok;
}

static bool writeFile(const std::string& path, const void* data, size_t n) {
    std::string tmp = path + ".tmp";
    FILE* f = fopen(tmp.c_str(), "wb");
    if (!f) return false;
    bool ok = fwrite(data, 1, n, f) == n;
    fclose(f);
    if (!ok) { remove(tmp.c_str()); return false; }
    return rename(tmp.c_str(), path.c_str()) == 0;
}

// ---------------------------------------------------------------- C trampolines
static bool cb_environment(unsigned cmd, void* data) { return Frontend::get().environment(cmd, data); }
static void cb_video(const void* d, unsigned w, unsigned h, size_t p) { Frontend::get().videoRefresh(d, w, h, p); }
static void cb_audio(int16_t l, int16_t r) { Frontend::get().audioSample(l, r); }
static size_t cb_audio_batch(const int16_t* d, size_t n) { return Frontend::get().audioSampleBatch(d, n); }
static void cb_input_poll() { Frontend::get().inputPoll(); }
static int16_t cb_input_state(unsigned p, unsigned d, unsigned i, unsigned id) { return Frontend::get().inputState(p, d, i, id); }

static void cb_log(enum retro_log_level level, const char* fmt, ...) {
    char buf[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    // Cores can be extremely chatty at DEBUG level (mGBA logs every DMA); drop those entirely.
    if (level == RETRO_LOG_DEBUG) return;
    int prio = ANDROID_LOG_DEBUG;
    switch (level) {
        case RETRO_LOG_INFO: prio = ANDROID_LOG_INFO; break;
        case RETRO_LOG_WARN: prio = ANDROID_LOG_WARN; break;
        case RETRO_LOG_ERROR: prio = ANDROID_LOG_ERROR; break;
        default: break;
    }
    __android_log_write(prio, "libretro", buf);
}

static uintptr_t cb_hw_get_current_framebuffer() { return Frontend::get().hwFramebufferForCore(); }
static retro_proc_address_t cb_hw_get_proc_address(const char* sym) {
    return (retro_proc_address_t)eglGetProcAddress(sym);
}
static bool cb_set_rumble(unsigned port, enum retro_rumble_effect, uint16_t strength) {
    return Frontend::get().rumble(port, strength);
}
static retro_time_t cb_perf_get_time_usec() { return nowNs() / 1000; }
static retro_perf_tick_t cb_perf_get_counter() { return (retro_perf_tick_t)nowNs(); }
static uint64_t cb_perf_get_cpu_features() { return 0; }
static void cb_perf_noop(retro_perf_counter*) {}
static void cb_perf_log() {}

// ---------------------------------------------------------------- Frontend
Frontend& Frontend::get() {
    static Frontend inst;
    return inst;
}

void Frontend::post(Command c) {
    {
        std::lock_guard<std::mutex> lock(queueMutex_);
        queue_.push_back(std::move(c));
    }
    queueCv_.notify_all();
}

void Frontend::run(Command c) {
    if (std::this_thread::get_id() == thread_.get_id()) { c(); return; }
    if (!threadRunning_) { c(); return; }
    std::mutex m;
    std::condition_variable cv;
    bool done = false;
    post([&] {
        c();
        std::lock_guard<std::mutex> lock(m);
        done = true;
        cv.notify_all();
    });
    std::unique_lock<std::mutex> lock(m);
    cv.wait(lock, [&] { return done; });
}

void Frontend::startThread() {
    if (threadRunning_) return;
    threadRunning_ = true;
    thread_ = std::thread([this] { threadMain(); });
}

void Frontend::stopThread() {
    if (!threadRunning_) return;
    threadRunning_ = false;
    queueCv_.notify_all();
    if (thread_.joinable()) thread_.join();
}

void Frontend::threadMain() {
    LOGI("emu thread started");
    if (listener_) listener_->onEmuThreadStarted();
    while (threadRunning_) {
        std::vector<Command> cmds;
        {
            std::unique_lock<std::mutex> lock(queueMutex_);
            bool active = gameLoaded_ && !paused_ && !shutdownRequested_;
            if (!active) {
                queueCv_.wait(lock, [&] { return !queue_.empty() || !threadRunning_ || windowDirty_ || (gameLoaded_ && !paused_); });
            }
            cmds.swap(queue_);
        }
        for (auto& c : cmds) c();
        if (!threadRunning_) break;

        if (windowDirty_.exchange(false)) {
            ANativeWindow* w;
            { std::lock_guard<std::mutex> lock(windowMutex_); w = pendingWindow_; }
            if (video_.ready() || w) {
                if (!video_.ready() && w) ensureGlReady();
                else video_.setWindow(w);
            }
            if (w) video_.setSwapInterval(0);
        }

        if (gameLoaded_ && !paused_ && !shutdownRequested_) {
            runFrame();
        } else if (gameLoaded_ && paused_ && video_.ready()) {
            // keep the last frame on screen after a surface change
            video_.makeCurrent();
            video_.present(videoCfg_, avInfo_.geometry.aspect_ratio, frameIsHw_);
            video_.swap();
        }
        if (shutdownRequested_.exchange(false) && listener_) listener_->onCoreShutdown();
    }
    if (listener_) listener_->onEmuThreadStopping();
    LOGI("emu thread exiting");
}

void Frontend::ensureGlReady() {
    if (video_.ready()) return;
    ANativeWindow* w;
    { std::lock_guard<std::mutex> lock(windowMutex_); w = pendingWindow_; }
    if (!w) return;
    bool depth = hwRender_ && hwCb_.depth;
    bool stencil = hwRender_ && hwCb_.stencil;
    if (!video_.init(w, depth, stencil)) {
        if (listener_) listener_->onFatal("OpenGL ES 3 초기화 실패");
        return;
    }
    video_.setSwapInterval(0);
    contextResetIfNeeded();
}

void Frontend::contextResetIfNeeded() {
    if (!hwRender_ || hwContextReady_ || !video_.ready()) return;
    // Cores advertise huge max sizes (PPSSPP 5120x3584, Azahar 7200x9600) that would need
    // hundreds of MB of GPU memory. Start with a sane size and grow on demand (see videoRefresh).
    unsigned w = std::max(avInfo_.geometry.base_width * 2u, 1280u);
    unsigned h = std::max(avInfo_.geometry.base_height * 2u, 720u);
    if (avInfo_.geometry.max_width) w = std::min(w, avInfo_.geometry.max_width);
    if (avInfo_.geometry.max_height) h = std::min(h, avInfo_.geometry.max_height);
    hwFboW_ = w; hwFboH_ = h;
    if (!video_.createHwFramebuffer(w, h, hwCb_.depth, hwCb_.stencil)) return;
    videoCfg_.bottomLeftOrigin = hwCb_.bottom_left_origin;
    if (hwCb_.context_reset) hwCb_.context_reset();
    hwContextReady_ = true;
    LOGI("HW render context reset done");
}

uintptr_t Frontend::hwFramebufferForCore() { return video_.hwFramebuffer(); }

bool Frontend::rumble(unsigned port, unsigned strength) {
    if (listener_) listener_->onRumble(port, strength);
    return true;
}

// ---------------------------------------------------------------- load / unload
bool Frontend::loadCore(const std::string& corePath, const std::string& systemDir, const std::string& saveDir,
                        const std::string& optionOverrides, std::string* error) {
    unload();
    startThread();
    bool ok = false;
    run([&] {
        corePath_ = corePath; systemDir_ = systemDir; saveDir_ = saveDir;
        mkdir(systemDir_.c_str(), 0755);
        mkdir(saveDir_.c_str(), 0755);
        { std::lock_guard<std::mutex> lock(optionsMutex_); options_.clear(); optionOverrides_.clear(); }
        applyOptionOverrides(optionOverrides);
        hwRender_ = false; hwContextReady_ = false; hwCb_ = {};
        pixelFormat_ = RETRO_PIXEL_FORMAT_0RGB1555;
        supportsBitmasks_ = false;
        rotation_ = 0; videoCfg_.rotation = 0;
        if (!core_.load(corePath, error)) return;
        core_.retro_set_environment(cb_environment);
        core_.retro_set_video_refresh(cb_video);
        core_.retro_set_audio_sample(cb_audio);
        core_.retro_set_audio_sample_batch(cb_audio_batch);
        core_.retro_set_input_poll(cb_input_poll);
        core_.retro_set_input_state(cb_input_state);
        core_.retro_init();
        sysInfo_ = {};
        core_.retro_get_system_info(&sysInfo_);
        LOGI("core loaded: %s %s (ext: %s, fullpath=%d)", sysInfo_.library_name, sysInfo_.library_version,
             sysInfo_.valid_extensions ? sysInfo_.valid_extensions : "", sysInfo_.need_fullpath);
        ok = true;
    });
    return ok;
}

bool Frontend::loadGame(const std::string& romPath, std::string* error) {
    if (!core_.loaded()) { if (error) *error = "core not loaded"; return false; }
    bool ok = false;
    run([&] {
        romPath_ = romPath;
        size_t slash = romPath.find_last_of('/');
        std::string base = slash == std::string::npos ? romPath : romPath.substr(slash + 1);
        size_t dot = base.find_last_of('.');
        romBase_ = dot == std::string::npos ? base : base.substr(0, dot);

        retro_game_info info{};
        info.path = romPath_.c_str();
        info.meta = "";
        if (!sysInfo_.need_fullpath) {
            if (!readFile(romPath_, romData_)) { if (error) *error = "ROM 파일을 읽을 수 없습니다"; return; }
            info.data = romData_.data();
            info.size = romData_.size();
        }
        if (!core_.retro_load_game(&info)) {
            if (error) *error = "코어가 게임을 불러오지 못했습니다";
            romData_.clear();
            return;
        }
        romData_.clear();
        avInfo_ = {};
        core_.retro_get_system_av_info(&avInfo_);
        LOGI("av info: %ux%u max %ux%u aspect %.3f fps %.3f rate %.1f", avInfo_.geometry.base_width,
             avInfo_.geometry.base_height, avInfo_.geometry.max_width, avInfo_.geometry.max_height,
             avInfo_.geometry.aspect_ratio, avInfo_.timing.fps, avInfo_.timing.sample_rate);
        for (unsigned p = 0; p < 2; p++) core_.retro_set_controller_port_device(p, RETRO_DEVICE_JOYPAD);
        loadSram();
        audio_.start(avInfo_.timing.sample_rate);
        gameLoaded_ = true;
        paused_ = true;
        nextFrameNs_ = 0;
        lastSramSaveNs_ = nowNs();
        ensureGlReady();
        contextResetIfNeeded();
        if (listener_) listener_->onGeometryChanged(avInfo_.geometry.base_width, avInfo_.geometry.base_height,
                                                     avInfo_.geometry.aspect_ratio);
        ok = true;
    });
    return ok;
}

void Frontend::unload() {
    if (!threadRunning_) return;
    run([&] {
        if (gameLoaded_) {
            saveSramInternal();
            if (hwRender_ && hwContextReady_ && hwCb_.context_destroy) hwCb_.context_destroy();
            hwContextReady_ = false;
            core_.retro_unload_game();
            gameLoaded_ = false;
        }
        audio_.stop();
        if (core_.loaded()) {
            core_.retro_deinit();
            core_.unload();
        }
        video_.destroyHwFramebuffer();
        video_.destroy();
        { std::lock_guard<std::mutex> lock(optionsMutex_); options_.clear(); }
    });
    stopThread();
    paused_ = true;
}

// ---------------------------------------------------------------- surface / pause
void Frontend::setSurface(ANativeWindow* window) {
    ANativeWindow* old;
    {
        std::lock_guard<std::mutex> lock(windowMutex_);
        old = pendingWindow_;
        pendingWindow_ = window;
        if (window) ANativeWindow_acquire(window);
    }
    windowDirty_ = true;
    if (threadRunning_) {
        // Block until the emu thread has switched surfaces, so the caller can safely release the old one.
        run([] {});
    }
    if (old) ANativeWindow_release(old);
}

void Frontend::setSurfaceSize(int, int) { windowDirty_ = true; queueCv_.notify_all(); }

void Frontend::setPaused(bool paused) {
    if (paused && gameLoaded_ && threadRunning_) run([&] { saveSramInternal(); });
    paused_ = paused;
    audio_.setMuted(paused);
    nextFrameNs_ = 0;
    queueCv_.notify_all();
}

// ---------------------------------------------------------------- frame loop
void Frontend::runFrame() {
    if (!video_.ready()) {
        // No surface yet: don't burn CPU.
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
        return;
    }
    if (hwRender_ && !hwContextReady_) { contextResetIfNeeded(); if (!hwContextReady_) return; }
    video_.makeCurrent();

    if (videoCfgDirty_.exchange(false)) {
        videoCfg_.linearFilter = linearFilterPending_;
        videoCfg_.aspect = (AspectMode)aspectModePending_;
    }

    const double fps = avInfo_.timing.fps > 1.0 ? avInfo_.timing.fps : 60.0;
    const int64_t periodNs = (int64_t)(1e9 / fps);
    const int ff = fastForward_.load();
    int64_t now = nowNs();

    if (ff == 0) {
        if (nextFrameNs_ == 0 || now - nextFrameNs_ > periodNs * 4) nextFrameNs_ = now; // resync after stall
        sleepUntil(nextFrameNs_);
        nextFrameNs_ += periodNs;
    } else if (ff > 0) {
        int64_t p = periodNs / ff;
        if (nextFrameNs_ == 0 || now - nextFrameNs_ > p * 8) nextFrameNs_ = now;
        sleepUntil(nextFrameNs_);
        nextFrameNs_ += p;
    }
    audio_.setFastForward(ff != 0);

    if (frameTimeCb_.callback) {
        int64_t t = nowNs();
        retro_usec_t delta = lastFrameTimeNs_ ? (retro_usec_t)((t - lastFrameTimeNs_) / 1000) : frameTimeCb_.reference;
        lastFrameTimeNs_ = t;
        frameTimeCb_.callback(delta);
    }

    if (hwRender_) {
        if (hwFboNeedsGrow_) {
            hwFboNeedsGrow_ = false;
            unsigned maxW = avInfo_.geometry.max_width ? avInfo_.geometry.max_width : 8192u;
            unsigned maxH = avInfo_.geometry.max_height ? avInfo_.geometry.max_height : 8192u;
            unsigned w = std::min(hwFboGrowW_, maxW), h = std::min(hwFboGrowH_, maxH);
            if (video_.createHwFramebuffer(w, h, hwCb_.depth, hwCb_.stencil)) { hwFboW_ = w; hwFboH_ = h; }
            LOGI("HW framebuffer grown to %ux%u", hwFboW_, hwFboH_);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, video_.hwFramebuffer());
    }
    gotFrameThisRun_ = false;
    core_.retro_run();

    // Present at most 60ish frames/sec while fast forwarding to keep the GPU free.
    bool present = true;
    if (ff != 0) { ffFrameCounter_++; present = (ffFrameCounter_ % (ff > 0 ? ff : 8)) == 0; }
    if (present) {
        video_.present(videoCfg_, avInfo_.geometry.aspect_ratio, frameIsHw_);
        video_.swap();
    }

    fpsFrames_++;
    int64_t t = nowNs();
    if (fpsWindowStartNs_ == 0) fpsWindowStartNs_ = t;
    if (t - fpsWindowStartNs_ >= 1000000000LL) {
        measuredFps_ = fpsFrames_ * 1e9 / (double)(t - fpsWindowStartNs_);
        fpsFrames_ = 0; fpsWindowStartNs_ = t;
    }
    if (t - lastSramSaveNs_ > 15000000000LL) { saveSramInternal(); lastSramSaveNs_ = t; }
}

// ---------------------------------------------------------------- core callbacks
void Frontend::videoRefresh(const void* data, unsigned w, unsigned h, size_t pitch) {
    gotFrameThisRun_ = true;
    if (data == RETRO_HW_FRAME_BUFFER_VALID) {
        frameIsHw_ = true;
        if (w > hwFboW_ || h > hwFboH_) {
            // The core rendered at a higher internal resolution than our FBO; grow before the next frame.
            hwFboGrowW_ = std::max(w, hwFboW_); hwFboGrowH_ = std::max(h, hwFboH_);
            hwFboNeedsGrow_ = true;
            w = std::min(w, hwFboW_); h = std::min(h, hwFboH_);
        }
        video_.setHwFrameSize(w, h);
        return;
    }
    frameIsHw_ = false;
    video_.uploadSoftwareFrame(data, w, h, pitch, pixelFormat_);
}

void Frontend::audioSample(int16_t l, int16_t r) {
    int16_t f[2] = { l, r };
    audio_.write(f, 1);
}

size_t Frontend::audioSampleBatch(const int16_t* data, size_t frames) {
    audio_.write(data, frames);
    return frames;
}

int16_t Frontend::inputState(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port >= 4) return 0;
    const InputState& in = input_[port];
    switch (device & RETRO_DEVICE_MASK) {
        case RETRO_DEVICE_JOYPAD: {
            uint32_t b = in.buttons.load(std::memory_order_relaxed);
            if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)(b & 0xffff);
            return (b >> id) & 1 ? 1 : 0;
        }
        case RETRO_DEVICE_ANALOG:
            if (index == RETRO_DEVICE_INDEX_ANALOG_BUTTON) {
                uint32_t b = in.buttons.load(std::memory_order_relaxed);
                return (b >> id) & 1 ? 0x7fff : 0;
            }
            if (index < 2 && id < 2) return in.analog[index][id].load(std::memory_order_relaxed);
            return 0;
        case RETRO_DEVICE_POINTER:
            if (port != 0 || index != 0) return 0;
            switch (id) {
                case RETRO_DEVICE_ID_POINTER_X: return pointerX_.load();
                case RETRO_DEVICE_ID_POINTER_Y: return pointerY_.load();
                case RETRO_DEVICE_ID_POINTER_PRESSED: return pointerPressed_.load() ? 1 : 0;
                case RETRO_DEVICE_ID_POINTER_COUNT: return pointerPressed_.load() ? 1 : 0;
                default: return 0;
            }
        default:
            return 0;
    }
}

void Frontend::setInput(unsigned port, uint32_t buttons, int16_t lx, int16_t ly, int16_t rx, int16_t ry) {
    if (port >= 4) return;
    input_[port].buttons.store(buttons, std::memory_order_relaxed);
    input_[port].analog[0][0].store(lx, std::memory_order_relaxed);
    input_[port].analog[0][1].store(ly, std::memory_order_relaxed);
    input_[port].analog[1][0].store(rx, std::memory_order_relaxed);
    input_[port].analog[1][1].store(ry, std::memory_order_relaxed);
}

void Frontend::setPointer(int16_t x, int16_t y, bool pressed) {
    pointerX_ = x; pointerY_ = y; pointerPressed_ = pressed;
}

void Frontend::setFastForward(int speed) { fastForward_ = speed; nextFrameNs_ = 0; }

void Frontend::setVideoConfig(bool linear, int aspectMode) {
    linearFilterPending_ = linear; aspectModePending_ = aspectMode; videoCfgDirty_ = true;
}

void Frontend::setAudioMuted(bool muted) { audio_.setMuted(muted || paused_); }

// ---------------------------------------------------------------- environment
bool Frontend::environment(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_ROTATION:
            rotation_ = *(const unsigned*)data & 3;
            videoCfg_.rotation = rotation_;
            return true;
        case RETRO_ENVIRONMENT_GET_OVERSCAN: *(bool*)data = false; return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE: *(bool*)data = true; return true;
        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            auto* m = (const retro_message*)data;
            if (listener_ && m && m->msg) listener_->onMessage(m->msg, m->frames * 1000 / 60, 1);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_MESSAGE_EXT: {
            auto* m = (const retro_message_ext*)data;
            if (m && m->msg) {
                if (m->target == RETRO_MESSAGE_TARGET_LOG) { LOGI("[core] %s", m->msg); return true; }
                if (listener_) listener_->onMessage(m->msg, m->duration, (int)m->priority);
            }
            return true;
        }
        case RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION: *(unsigned*)data = 1; return true;
        case RETRO_ENVIRONMENT_SHUTDOWN: shutdownRequested_ = true; return true;
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL: return true;
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: *(const char**)data = systemDir_.c_str(); return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: *(const char**)data = saveDir_.c_str(); return true;
        case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY: *(const char**)data = systemDir_.c_str(); return true;
        case RETRO_ENVIRONMENT_GET_LIBRETRO_PATH: *(const char**)data = corePath_.c_str(); return true;
        case RETRO_ENVIRONMENT_GET_USERNAME: *(const char**)data = "OneEmu"; return true;
        case RETRO_ENVIRONMENT_GET_LANGUAGE: *(unsigned*)data = RETRO_LANGUAGE_KOREAN; return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            int fmt = *(const int*)data;
            if (fmt != RETRO_PIXEL_FORMAT_0RGB1555 && fmt != RETRO_PIXEL_FORMAT_XRGB8888 && fmt != RETRO_PIXEL_FORMAT_RGB565) return false;
            pixelFormat_ = fmt;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS: return true;
        case RETRO_ENVIRONMENT_SET_KEYBOARD_CALLBACK: return false;
        case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE: {
            auto* d = (const retro_disk_control_callback*)data;
            diskCb_ = {};
            diskCb_.set_eject_state = d->set_eject_state; diskCb_.get_eject_state = d->get_eject_state;
            diskCb_.get_image_index = d->get_image_index; diskCb_.set_image_index = d->set_image_index;
            diskCb_.get_num_images = d->get_num_images; diskCb_.replace_image_index = d->replace_image_index;
            diskCb_.add_image_index = d->add_image_index;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION: *(unsigned*)data = 1; return true;
        case RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE: diskCb_ = *(const retro_disk_control_ext_callback*)data; return true;
        case RETRO_ENVIRONMENT_SET_HW_RENDER: {
            auto* hw = (retro_hw_render_callback*)data;
            bool gles = hw->context_type == RETRO_HW_CONTEXT_OPENGLES3 || hw->context_type == RETRO_HW_CONTEXT_OPENGLES_VERSION ||
                        hw->context_type == RETRO_HW_CONTEXT_OPENGLES2;
            if (!gles) {
                LOGW("core requested unsupported HW context type %d", hw->context_type);
                return false;
            }
            hw->get_current_framebuffer = cb_hw_get_current_framebuffer;
            hw->get_proc_address = cb_hw_get_proc_address;
            hwCb_ = *hw;
            hwRender_ = true;
            hwContextReady_ = false;
            LOGI("core requested HW render: type %d v%u.%u depth=%d stencil=%d bottomLeft=%d", hw->context_type,
                 hw->version_major, hw->version_minor, hw->depth, hw->stencil, hw->bottom_left_origin);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER: *(unsigned*)data = RETRO_HW_CONTEXT_OPENGLES3; return true;
        case RETRO_ENVIRONMENT_SET_HW_SHARED_CONTEXT: return true;
        case RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE: return false;
        case RETRO_ENVIRONMENT_GET_CURRENT_SOFTWARE_FRAMEBUFFER: return false; // asked every frame by some cores
        case RETRO_ENVIRONMENT_SET_AUDIO_BUFFER_STATUS_CALLBACK: return false;
        case RETRO_ENVIRONMENT_GET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_SUPPORT: return false;
        case RETRO_ENVIRONMENT_SET_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE: return false;
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto* var = (retro_variable*)data;
            var->value = nullptr;
            std::lock_guard<std::mutex> lock(optionsMutex_);
            for (auto& o : options_) {
                if (o.key == var->key) { var->value = o.current.c_str(); return true; }
            }
            auto it = optionOverrides_.find(var->key);
            if (it != optionOverrides_.end()) { var->value = it->second.c_str(); return true; }
            return false;
        }
        case RETRO_ENVIRONMENT_SET_VARIABLES: setOptionsFromVariables((const retro_variable*)data); return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: *(bool*)data = optionsDirty_.exchange(false); return true;
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME: return true;
        case RETRO_ENVIRONMENT_SET_FRAME_TIME_CALLBACK: frameTimeCb_ = *(const retro_frame_time_callback*)data; lastFrameTimeNs_ = 0; return true;
        case RETRO_ENVIRONMENT_SET_AUDIO_CALLBACK: return false;
        case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE: ((retro_rumble_interface*)data)->set_rumble_state = cb_set_rumble; return true;
        case RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES:
            *(uint64_t*)data = (1 << RETRO_DEVICE_JOYPAD) | (1 << RETRO_DEVICE_ANALOG) | (1 << RETRO_DEVICE_POINTER);
            return true;
        case RETRO_ENVIRONMENT_GET_SENSOR_INTERFACE: return false;
        case RETRO_ENVIRONMENT_GET_CAMERA_INTERFACE: return false;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: ((retro_log_callback*)data)->log = cb_log; return true;
        case RETRO_ENVIRONMENT_GET_PERF_INTERFACE: {
            auto* p = (retro_perf_callback*)data;
            p->get_time_usec = cb_perf_get_time_usec;
            p->get_cpu_features = cb_perf_get_cpu_features;
            p->get_perf_counter = cb_perf_get_counter;
            p->perf_register = cb_perf_noop; p->perf_start = cb_perf_noop; p->perf_stop = cb_perf_noop;
            p->perf_log = cb_perf_log;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_LOCATION_INTERFACE: return false;
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            avInfo_ = *(const retro_system_av_info*)data;
            audio_.start(avInfo_.timing.sample_rate);
            if (hwRender_ && hwContextReady_) {
                video_.createHwFramebuffer(hwFboW_, hwFboH_, hwCb_.depth, hwCb_.stencil);
            }
            if (listener_) listener_->onGeometryChanged(avInfo_.geometry.base_width, avInfo_.geometry.base_height, avInfo_.geometry.aspect_ratio);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_GEOMETRY: {
            avInfo_.geometry = *(const retro_game_geometry*)data;
            if (listener_) listener_->onGeometryChanged(avInfo_.geometry.base_width, avInfo_.geometry.base_height, avInfo_.geometry.aspect_ratio);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_PROC_ADDRESS_CALLBACK: return true;
        case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO: return true;
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO: return true;
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS: return true;
        case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS: return true;
        case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS: return true;
        case RETRO_ENVIRONMENT_GET_VFS_INTERFACE: return false;
        case RETRO_ENVIRONMENT_GET_LED_INTERFACE: return false;
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE: *(int*)data = 3; return true;
        case RETRO_ENVIRONMENT_GET_MIDI_INTERFACE: return false;
        case RETRO_ENVIRONMENT_GET_FASTFORWARDING: *(bool*)data = fastForward_ != 0; return true;
        case RETRO_ENVIRONMENT_GET_TARGET_REFRESH_RATE: *(float*)data = 60.0f; return true;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS: supportsBitmasks_ = true; return true;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION: *(unsigned*)data = 2; return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS: setOptionsFromV1((const retro_core_option_definition*)data); return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL: setOptionsFromV1Intl((const retro_core_options_intl*)data); return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2: setOptionsFromV2((const retro_core_options_v2*)data); return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL: setOptionsFromV2Intl((const retro_core_options_v2_intl*)data); return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY: {
            auto* d = (const retro_core_option_display*)data;
            std::lock_guard<std::mutex> lock(optionsMutex_);
            for (auto& o : options_) if (o.key == d->key) o.visible = d->visible;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK: return false;
        case RETRO_ENVIRONMENT_SET_VARIABLE: {
            auto* v = (const retro_variable*)data;
            if (v && v->key && v->value) setOption(v->key, v->value);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_THROTTLE_STATE: {
            auto* t = (retro_throttle_state*)data;
            int ff = fastForward_.load();
            t->mode = ff != 0 ? RETRO_THROTTLE_FAST_FORWARD : RETRO_THROTTLE_NONE;
            t->rate = ff > 0 ? (float)(avInfo_.timing.fps * ff) : (float)avInfo_.timing.fps;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SAVESTATE_CONTEXT: *(int*)data = RETRO_SAVESTATE_CONTEXT_NORMAL; return true;
        case RETRO_ENVIRONMENT_GET_JIT_CAPABLE: *(bool*)data = true; return true;
        case RETRO_ENVIRONMENT_GET_MICROPHONE_INTERFACE: return false;
        case RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY: return true;
        case RETRO_ENVIRONMENT_GET_DEVICE_POWER: return false;
        case RETRO_ENVIRONMENT_SET_NETPACKET_INTERFACE: return false;
        case RETRO_ENVIRONMENT_GET_PLAYLIST_DIRECTORY: return false;
        case RETRO_ENVIRONMENT_GET_FILE_BROWSER_START_DIRECTORY: return false;
        case RETRO_ENVIRONMENT_GET_INPUT_MAX_USERS: *(unsigned*)data = 4; return true;
        default:
            LOGD("unhandled environment cmd %u", cmd & ~RETRO_ENVIRONMENT_EXPERIMENTAL);
            return false;
    }
}

// ---------------------------------------------------------------- options
void Frontend::applyOptionOverrides(const std::string& overrides) {
    // "key=value\n" lines
    std::lock_guard<std::mutex> lock(optionsMutex_);
    size_t pos = 0;
    while (pos < overrides.size()) {
        size_t nl = overrides.find('\n', pos);
        if (nl == std::string::npos) nl = overrides.size();
        std::string line = overrides.substr(pos, nl - pos);
        pos = nl + 1;
        size_t eq = line.find('=');
        if (eq == std::string::npos || eq == 0) continue;
        optionOverrides_[line.substr(0, eq)] = line.substr(eq + 1);
    }
}

static void finishOption(CoreOption& o, const std::map<std::string, std::string>& overrides) {
    o.current = o.defaultValue;
    auto it = overrides.find(o.key);
    if (it != overrides.end()) {
        for (auto& v : o.values) if (v.first == it->second) { o.current = it->second; break; }
    }
    if (o.current.empty() && !o.values.empty()) o.current = o.values.front().first;
}

void Frontend::setOptionsFromVariables(const retro_variable* vars) {
    std::lock_guard<std::mutex> lock(optionsMutex_);
    options_.clear();
    for (; vars && vars->key; vars++) {
        CoreOption o;
        o.key = vars->key;
        std::string v = vars->value ? vars->value : "";
        size_t semi = v.find(';');
        o.desc = semi == std::string::npos ? v : v.substr(0, semi);
        std::string list = semi == std::string::npos ? "" : v.substr(semi + 1);
        while (!list.empty() && list.front() == ' ') list.erase(0, 1);
        size_t p = 0;
        while (p <= list.size() && !list.empty()) {
            size_t bar = list.find('|', p);
            if (bar == std::string::npos) bar = list.size();
            std::string val = list.substr(p, bar - p);
            if (!val.empty()) o.values.emplace_back(val, val);
            if (bar == list.size()) break;
            p = bar + 1;
        }
        if (!o.values.empty()) o.defaultValue = o.values.front().first;
        finishOption(o, optionOverrides_);
        options_.push_back(std::move(o));
    }
}

void Frontend::setOptionsFromV1(const retro_core_option_definition* defs) {
    std::lock_guard<std::mutex> lock(optionsMutex_);
    options_.clear();
    for (; defs && defs->key; defs++) {
        CoreOption o;
        o.key = defs->key;
        o.desc = defs->desc ? defs->desc : "";
        o.info = defs->info ? defs->info : "";
        for (unsigned i = 0; i < RETRO_NUM_CORE_OPTION_VALUES_MAX && defs->values[i].value; i++)
            o.values.emplace_back(defs->values[i].value, defs->values[i].label ? defs->values[i].label : defs->values[i].value);
        o.defaultValue = defs->default_value ? defs->default_value : "";
        finishOption(o, optionOverrides_);
        options_.push_back(std::move(o));
    }
}

void Frontend::setOptionsFromV1Intl(const retro_core_options_intl* intl) {
    setOptionsFromV1(intl->local ? intl->local : intl->us);
}

void Frontend::setOptionsFromV2(const retro_core_options_v2* v2) {
    std::lock_guard<std::mutex> lock(optionsMutex_);
    options_.clear();
    std::map<std::string, std::string> cats;
    for (auto* c = v2->categories; c && c->key; c++) cats[c->key] = c->desc ? c->desc : c->key;
    for (auto* d = v2->definitions; d && d->key; d++) {
        CoreOption o;
        o.key = d->key;
        o.desc = d->desc ? d->desc : "";
        if (d->desc_categorized && d->category_key) o.desc = d->desc_categorized;
        o.info = d->info ? d->info : "";
        if (d->category_key) { auto it = cats.find(d->category_key); o.category = it != cats.end() ? it->second : d->category_key; }
        for (unsigned i = 0; i < RETRO_NUM_CORE_OPTION_VALUES_MAX && d->values[i].value; i++)
            o.values.emplace_back(d->values[i].value, d->values[i].label ? d->values[i].label : d->values[i].value);
        o.defaultValue = d->default_value ? d->default_value : "";
        finishOption(o, optionOverrides_);
        options_.push_back(std::move(o));
    }
}

void Frontend::setOptionsFromV2Intl(const retro_core_options_v2_intl* intl) {
    setOptionsFromV2(intl->local ? intl->local : intl->us);
}

std::vector<CoreOption> Frontend::options() {
    std::lock_guard<std::mutex> lock(optionsMutex_);
    return options_;
}

void Frontend::setOption(const std::string& key, const std::string& value) {
    std::lock_guard<std::mutex> lock(optionsMutex_);
    optionOverrides_[key] = value;
    for (auto& o : options_) {
        if (o.key == key) { o.current = value; optionsDirty_ = true; return; }
    }
}

// ---------------------------------------------------------------- state / sram / misc
bool Frontend::saveState(const std::string& path) {
    bool ok = false;
    run([&] {
        if (!gameLoaded_) return;
        size_t n = core_.retro_serialize_size();
        if (n == 0) return;
        std::vector<uint8_t> buf(n);
        if (!core_.retro_serialize(buf.data(), n)) return;
        ok = writeFile(path, buf.data(), n);
    });
    return ok;
}

bool Frontend::loadState(const std::string& path) {
    bool ok = false;
    run([&] {
        if (!gameLoaded_) return;
        std::vector<uint8_t> buf;
        if (!readFile(path, buf)) return;
        ok = core_.retro_unserialize(buf.data(), buf.size());
    });
    return ok;
}

std::string Frontend::sramPath() const { return saveDir_ + "/" + romBase_ + ".srm"; }

void Frontend::loadSram() {
    size_t n = core_.retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    void* p = core_.retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!n || !p) return;
    std::vector<uint8_t> buf;
    if (readFile(sramPath(), buf) && !buf.empty()) {
        memcpy(p, buf.data(), std::min(n, buf.size()));
        LOGI("SRAM loaded (%zu bytes)", buf.size());
    }
    lastSram_.assign((uint8_t*)p, (uint8_t*)p + n);
}

bool Frontend::saveSramInternal() {
    if (!gameLoaded_) return false;
    size_t n = core_.retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    void* p = core_.retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!n || !p) return false;
    if (lastSram_.size() == n && memcmp(lastSram_.data(), p, n) == 0) return true; // unchanged
    if (!writeFile(sramPath(), p, n)) return false;
    lastSram_.assign((uint8_t*)p, (uint8_t*)p + n);
    LOGI("SRAM saved (%zu bytes)", n);
    return true;
}

bool Frontend::saveSram() {
    bool ok = false;
    run([&] { ok = saveSramInternal(); });
    return ok;
}

void Frontend::reset() {
    run([&] { if (gameLoaded_) core_.retro_reset(); });
}

bool Frontend::screenshot(std::vector<uint32_t>& rgba, int& w, int& h) {
    bool ok = false;
    run([&] {
        if (!video_.ready()) return;
        video_.makeCurrent();
        video_.present(videoCfg_, avInfo_.geometry.aspect_ratio, frameIsHw_);
        ok = video_.readback(rgba, w, h);
    });
    return ok;
}

void Frontend::setCheat(unsigned index, bool enabled, const std::string& code) {
    run([&] { if (core_.loaded()) core_.retro_cheat_set(index, enabled, code.c_str()); });
}

void Frontend::resetCheats() {
    run([&] { if (core_.loaded()) core_.retro_cheat_reset(); });
}
