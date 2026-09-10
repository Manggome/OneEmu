#pragma once
#include "libretro.h"
#include <string>

// Thin wrapper around a dlopen()'d libretro core.
class LibretroCore {
public:
    bool load(const std::string& path, std::string* error);
    void unload();
    bool loaded() const { return handle_ != nullptr; }

    void (*retro_init)() = nullptr;
    void (*retro_deinit)() = nullptr;
    unsigned (*retro_api_version)() = nullptr;
    void (*retro_get_system_info)(retro_system_info*) = nullptr;
    void (*retro_get_system_av_info)(retro_system_av_info*) = nullptr;
    void (*retro_set_environment)(retro_environment_t) = nullptr;
    void (*retro_set_video_refresh)(retro_video_refresh_t) = nullptr;
    void (*retro_set_audio_sample)(retro_audio_sample_t) = nullptr;
    void (*retro_set_audio_sample_batch)(retro_audio_sample_batch_t) = nullptr;
    void (*retro_set_input_poll)(retro_input_poll_t) = nullptr;
    void (*retro_set_input_state)(retro_input_state_t) = nullptr;
    void (*retro_set_controller_port_device)(unsigned, unsigned) = nullptr;
    void (*retro_reset)() = nullptr;
    void (*retro_run)() = nullptr;
    size_t (*retro_serialize_size)() = nullptr;
    bool (*retro_serialize)(void*, size_t) = nullptr;
    bool (*retro_unserialize)(const void*, size_t) = nullptr;
    void (*retro_cheat_reset)() = nullptr;
    void (*retro_cheat_set)(unsigned, bool, const char*) = nullptr;
    bool (*retro_load_game)(const retro_game_info*) = nullptr;
    bool (*retro_load_game_special)(unsigned, const retro_game_info*, size_t) = nullptr;
    void (*retro_unload_game)() = nullptr;
    unsigned (*retro_get_region)() = nullptr;
    void* (*retro_get_memory_data)(unsigned) = nullptr;
    size_t (*retro_get_memory_size)(unsigned) = nullptr;

private:
    void* handle_ = nullptr;
};
