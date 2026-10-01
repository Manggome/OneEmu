//! Hand-written subset of libretro.h (only what this core uses; no bindgen/clang needed at build time).
#![allow(non_camel_case_types, dead_code)]

use core::ffi::{c_char, c_int, c_uint, c_void};

pub const RETRO_API_VERSION: c_uint = 1;

pub const RETRO_ENVIRONMENT_EXPERIMENTAL: c_uint = 0x10000;
pub const RETRO_ENVIRONMENT_SET_MESSAGE: c_uint = 6;
pub const RETRO_ENVIRONMENT_SHUTDOWN: c_uint = 7;
pub const RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: c_uint = 9;
pub const RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: c_uint = 10;
pub const RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS: c_uint = 11;
pub const RETRO_ENVIRONMENT_GET_VARIABLE: c_uint = 15;
pub const RETRO_ENVIRONMENT_SET_VARIABLES: c_uint = 16;
pub const RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: c_uint = 17;
pub const RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE: c_uint = 23;
pub const RETRO_ENVIRONMENT_GET_LOG_INTERFACE: c_uint = 27;
pub const RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY: c_uint = 30;
pub const RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: c_uint = 31;
pub const RETRO_ENVIRONMENT_SET_GEOMETRY: c_uint = 37;
pub const RETRO_ENVIRONMENT_GET_INPUT_BITMASKS: c_uint = 51 | RETRO_ENVIRONMENT_EXPERIMENTAL;

pub const RETRO_PIXEL_FORMAT_XRGB8888: c_int = 1;

pub const RETRO_DEVICE_JOYPAD: c_uint = 1;
pub const RETRO_DEVICE_KEYBOARD: c_uint = 3;
pub const RETRO_DEVICE_ANALOG: c_uint = 5;

pub const RETRO_DEVICE_ID_JOYPAD_B: c_uint = 0;
pub const RETRO_DEVICE_ID_JOYPAD_Y: c_uint = 1;
pub const RETRO_DEVICE_ID_JOYPAD_SELECT: c_uint = 2;
pub const RETRO_DEVICE_ID_JOYPAD_START: c_uint = 3;
pub const RETRO_DEVICE_ID_JOYPAD_UP: c_uint = 4;
pub const RETRO_DEVICE_ID_JOYPAD_DOWN: c_uint = 5;
pub const RETRO_DEVICE_ID_JOYPAD_LEFT: c_uint = 6;
pub const RETRO_DEVICE_ID_JOYPAD_RIGHT: c_uint = 7;
pub const RETRO_DEVICE_ID_JOYPAD_A: c_uint = 8;
pub const RETRO_DEVICE_ID_JOYPAD_X: c_uint = 9;
pub const RETRO_DEVICE_ID_JOYPAD_L: c_uint = 10;
pub const RETRO_DEVICE_ID_JOYPAD_R: c_uint = 11;
pub const RETRO_DEVICE_ID_JOYPAD_L2: c_uint = 12;
pub const RETRO_DEVICE_ID_JOYPAD_R2: c_uint = 13;
pub const RETRO_DEVICE_ID_JOYPAD_L3: c_uint = 14;
pub const RETRO_DEVICE_ID_JOYPAD_R3: c_uint = 15;
pub const RETRO_DEVICE_ID_JOYPAD_MASK: c_uint = 256;

pub const RETRO_DEVICE_INDEX_ANALOG_LEFT: c_uint = 0;
pub const RETRO_DEVICE_INDEX_ANALOG_RIGHT: c_uint = 1;
pub const RETRO_DEVICE_ID_ANALOG_X: c_uint = 0;
pub const RETRO_DEVICE_ID_ANALOG_Y: c_uint = 1;

// retro_key values (libretro.h enum retro_key)
pub const RETROK_BACKSPACE: c_uint = 8;
pub const RETROK_RETURN: c_uint = 13;
pub const RETROK_SPACE: c_uint = 32;
pub const RETROK_HASH: c_uint = 35;
pub const RETROK_ASTERISK: c_uint = 42;
pub const RETROK_0: c_uint = 48;
pub const RETROK_KP0: c_uint = 256;
pub const RETROK_KP_DIVIDE: c_uint = 267;
pub const RETROK_KP_MULTIPLY: c_uint = 268;
pub const RETROK_KP_ENTER: c_uint = 271;
pub const RETROK_UP: c_uint = 273;
pub const RETROK_DOWN: c_uint = 274;
pub const RETROK_RIGHT: c_uint = 275;
pub const RETROK_LEFT: c_uint = 276;
pub const RETROK_PAGEUP: c_uint = 280;
pub const RETROK_PAGEDOWN: c_uint = 281;
pub const RETROK_F1: c_uint = 282;
pub const RETROK_F2: c_uint = 283;
pub const RETROK_F3: c_uint = 284;
pub const RETROK_F4: c_uint = 285;

pub const RETRO_LOG_DEBUG: c_int = 0;
pub const RETRO_LOG_INFO: c_int = 1;
pub const RETRO_LOG_WARN: c_int = 2;
pub const RETRO_LOG_ERROR: c_int = 3;

pub const RETRO_MEMORY_SAVE_RAM: c_uint = 0;

pub const RETRO_RUMBLE_STRONG: c_int = 0;
pub const RETRO_RUMBLE_WEAK: c_int = 1;

pub const RETRO_REGION_NTSC: c_uint = 0;

pub type retro_environment_t = unsafe extern "C" fn(cmd: c_uint, data: *mut c_void) -> bool;
pub type retro_video_refresh_t = unsafe extern "C" fn(data: *const c_void, width: c_uint, height: c_uint, pitch: usize);
pub type retro_audio_sample_t = unsafe extern "C" fn(left: i16, right: i16);
pub type retro_audio_sample_batch_t = unsafe extern "C" fn(data: *const i16, frames: usize) -> usize;
pub type retro_input_poll_t = unsafe extern "C" fn();
pub type retro_input_state_t = unsafe extern "C" fn(port: c_uint, device: c_uint, index: c_uint, id: c_uint) -> i16;
pub type retro_log_printf_t = unsafe extern "C" fn(level: c_int, fmt: *const c_char, ...);
pub type retro_set_rumble_state_t = unsafe extern "C" fn(port: c_uint, effect: c_int, strength: u16) -> bool;

#[repr(C)]
pub struct retro_system_info {
    pub library_name: *const c_char,
    pub library_version: *const c_char,
    pub valid_extensions: *const c_char,
    pub need_fullpath: bool,
    pub block_extract: bool,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct retro_game_geometry {
    pub base_width: c_uint,
    pub base_height: c_uint,
    pub max_width: c_uint,
    pub max_height: c_uint,
    pub aspect_ratio: f32,
}

#[repr(C)]
pub struct retro_system_timing {
    pub fps: f64,
    pub sample_rate: f64,
}

#[repr(C)]
pub struct retro_system_av_info {
    pub geometry: retro_game_geometry,
    pub timing: retro_system_timing,
}

#[repr(C)]
pub struct retro_game_info {
    pub path: *const c_char,
    pub data: *const c_void,
    pub size: usize,
    pub meta: *const c_char,
}

#[repr(C)]
pub struct retro_variable {
    pub key: *const c_char,
    pub value: *const c_char,
}

#[repr(C)]
pub struct retro_message {
    pub msg: *const c_char,
    pub frames: c_uint,
}

#[repr(C)]
pub struct retro_input_descriptor {
    pub port: c_uint,
    pub device: c_uint,
    pub index: c_uint,
    pub id: c_uint,
    pub description: *const c_char,
}

#[repr(C)]
pub struct retro_log_callback {
    pub log: Option<retro_log_printf_t>,
}

#[repr(C)]
pub struct retro_rumble_interface {
    pub set_rumble_state: Option<retro_set_rumble_state_t>,
}
