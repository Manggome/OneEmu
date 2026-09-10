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
        if (error) *error = dlerror() ? dlerror() : "dlopen failed";
        LOGE("dlopen(%s) failed: %s", path.c_str(), error ? error->c_str() : "");
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
    return true;
}

void LibretroCore::unload() {
    if (handle_) {
        dlclose(handle_);
        handle_ = nullptr;
    }
}
