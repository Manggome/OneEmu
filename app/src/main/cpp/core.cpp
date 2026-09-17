#include "core.h"
#include "log.h"
#include <dlfcn.h>

#define LOAD_SYM(name) \
    do { \
        *(void**)(&name) = dlsym(handle_, #name); \
        if (!name) { if (error) *error = std::string("missing symbol ") + #name; unload(); return false; } \
    } while (0)

bool LibretroCore::load(const std::string& path, std::string* error) {
    unload();
    handle_ = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!handle_) {
        const char* why = dlerror();
        if (error) *error = std::string("dlopen failed: ") + (why ? why : "unknown reason");
        LOGE("dlopen(%s) failed: %s", path.c_str(), why ? why : "");
        return false;
    }
    LOAD_SYM(retro_init);
    LOAD_SYM(retro_deinit);
    LOAD_SYM(retro_api_version);
    LOAD_SYM(retro_get_system_info);
    LOAD_SYM(retro_get_system_av_info);
    LOAD_SYM(retro_set_environment);
    LOAD_SYM(retro_set_video_refresh);
    LOAD_SYM(retro_set_audio_sample);
    LOAD_SYM(retro_set_audio_sample_batch);
    LOAD_SYM(retro_set_input_poll);
    LOAD_SYM(retro_set_input_state);
    LOAD_SYM(retro_set_controller_port_device);
    LOAD_SYM(retro_reset);
    LOAD_SYM(retro_run);
    LOAD_SYM(retro_serialize_size);
    LOAD_SYM(retro_serialize);
    LOAD_SYM(retro_unserialize);
    LOAD_SYM(retro_cheat_reset);
    LOAD_SYM(retro_cheat_set);
    LOAD_SYM(retro_load_game);
    LOAD_SYM(retro_load_game_special);
    LOAD_SYM(retro_unload_game);
    LOAD_SYM(retro_get_region);
    LOAD_SYM(retro_get_memory_data);
    LOAD_SYM(retro_get_memory_size);
    if (retro_api_version() != RETRO_API_VERSION) {
        if (error) *error = "unsupported libretro API version";
        unload();
        return false;
    }
    handOverJavaVM();
    return true;
}

void* LibretroCore::javaVm_ = nullptr;

// System.loadLibrary would call JNI_OnLoad; dlopen does not. Cores built from Android app code (Play!) still
// expect a JavaVM: without it CPS2VM::EmuThread calls AttachCurrentThread on a null VM and crashes.
void LibretroCore::handOverJavaVM() {
    if (!javaVm_ || !handle_) return;
    // Never call a core's JNI_OnLoad: it belongs to that project's own Android app and registers natives for
    // Java classes that do not exist in this APK. Dolphin's aborts the process there (SIGABRT in libart).
    // Only the narrow, side-effect-free setters of cores that need the VM are called.
    using SetVmFn = void (*)(void*);
    // Framework::CJavaVM::SetJavaVM(JavaVM*) — Play! (libplay_libretro.so); its emu thread attaches through it.
    if (auto setVm = (SetVmFn)dlsym(handle_, "_ZN9Framework7CJavaVM9SetJavaVMEP7_JavaVM")) {
        setVm(javaVm_);
        LOGI("core Framework::CJavaVM::SetJavaVM called");
    }
}

void LibretroCore::unload(bool keepLibrary) {
    if (handle_) {
        if (!keepLibrary) dlclose(handle_);
        handle_ = nullptr;
    }
}
