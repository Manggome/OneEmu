//! libretro core for Korean feature-phone games (WIPI: KTF, LGT, SKT/SKVM, plus plain J2ME) built on
//! dlunch/wie. See `cores/wipi/core.json` in OneEmu for how the frontend uses it.

mod archive;
mod audio;
pub mod cheat;
mod clock;
mod ffi;
mod host;
mod input;
pub mod locate;
mod log;
mod options;
mod quirks;
mod session;
mod storage;
mod worker;

pub use crate::{
    archive::Carrier,
    cheat::SearchOp,
    input::{KeySet, PadProfile},
    options::RawOptions,
    session::{FrameOutput, LoadRequest, Session},
};

use std::{
    ffi::{CStr, CString, c_char, c_int, c_uint, c_void},
    path::PathBuf,
    ptr,
    sync::{Mutex, OnceLock},
};

use crate::{
    audio::{FRAMES_PER_RUN, SAMPLE_RATE},
    ffi::*,
    input::{KEYBOARD_MAP, PadState, map_pad},
    session::LoadInfo,
    worker::Worker,
};

const MAX_DIMENSION: u32 = 1024;

#[derive(Clone, Copy)]
struct Callbacks {
    environment: Option<retro_environment_t>,
    video: Option<retro_video_refresh_t>,
    audio_batch: Option<retro_audio_sample_batch_t>,
    input_poll: Option<retro_input_poll_t>,
    input_state: Option<retro_input_state_t>,
}

static CALLBACKS: Mutex<Callbacks> = Mutex::new(Callbacks {
    environment: None,
    video: None,
    audio_batch: None,
    input_poll: None,
    input_state: None,
});

static CORE: Mutex<Option<Core>> = Mutex::new(None);

struct Core {
    worker: Worker,
    request: LoadRequest,
    info: LoadInfo,
    /// Size last reported to the frontend (av_info / SET_GEOMETRY).
    geometry: (u32, u32),
    recycle: FrameOutput,
    bitmasks: bool,
    rumble: Option<retro_set_rumble_state_t>,
    rumble_frames: u32,
    failed: bool,
    shutdown_sent: bool,
    pad_profile: PadProfile,
    deadzone: f32,
}

fn callbacks() -> Callbacks {
    CALLBACKS.lock().map(|c| *c).unwrap_or(Callbacks {
        environment: None,
        video: None,
        audio_batch: None,
        input_poll: None,
        input_state: None,
    })
}

unsafe fn env(cmd: c_uint, data: *mut c_void) -> bool {
    match callbacks().environment {
        Some(f) => unsafe { f(cmd, data) },
        None => false,
    }
}

fn env_dir(cmd: c_uint) -> Option<PathBuf> {
    let mut p: *const c_char = ptr::null();
    let ok = unsafe { env(cmd, &mut p as *mut *const c_char as *mut c_void) };
    if !ok || p.is_null() {
        return None;
    }
    let s = unsafe { CStr::from_ptr(p) }.to_string_lossy().to_string();
    (!s.is_empty()).then(|| PathBuf::from(s))
}

fn show_message(text: &str, frames: u32) {
    let Ok(msg) = CString::new(text.replace('\0', " ")) else { return };
    let mut m = retro_message { msg: msg.as_ptr(), frames };
    unsafe { env(RETRO_ENVIRONMENT_SET_MESSAGE, &mut m as *mut retro_message as *mut c_void) };
}

fn guard<T>(what: &str, default: T, f: impl FnOnce() -> T) -> T {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)) {
        Ok(v) => v,
        Err(panic) => {
            tracing::error!("{what}: {}", session::panic_message(&panic));
            default
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Options
// ---------------------------------------------------------------------------------------------

fn variable_cstrings() -> &'static Vec<(CString, CString)> {
    static VARS: OnceLock<Vec<(CString, CString)>> = OnceLock::new();
    VARS.get_or_init(|| {
        options::VARIABLES
            .iter()
            .map(|(k, v)| (CString::new(*k).unwrap_or_default(), CString::new(*v).unwrap_or_default()))
            .collect()
    })
}

fn declare_variables() {
    let vars = variable_cstrings();
    let mut list: Vec<retro_variable> = vars.iter().map(|(k, v)| retro_variable { key: k.as_ptr(), value: v.as_ptr() }).collect();
    list.push(retro_variable { key: ptr::null(), value: ptr::null() });
    unsafe { env(RETRO_ENVIRONMENT_SET_VARIABLES, list.as_mut_ptr() as *mut c_void) };
}

fn read_variables() -> RawOptions {
    let mut values = Vec::new();
    for (key, _) in variable_cstrings() {
        let mut var = retro_variable { key: key.as_ptr(), value: ptr::null() };
        let ok = unsafe { env(RETRO_ENVIRONMENT_GET_VARIABLE, &mut var as *mut retro_variable as *mut c_void) };
        if ok && !var.value.is_null() {
            let value = unsafe { CStr::from_ptr(var.value) }.to_string_lossy().to_string();
            values.push((key.to_string_lossy().to_string(), value));
        }
    }
    RawOptions { values }
}

fn declare_input_descriptors() {
    let pad: &[(c_uint, &str)] = &[
        (RETRO_DEVICE_ID_JOYPAD_UP, "위"),
        (RETRO_DEVICE_ID_JOYPAD_DOWN, "아래"),
        (RETRO_DEVICE_ID_JOYPAD_LEFT, "왼쪽"),
        (RETRO_DEVICE_ID_JOYPAD_RIGHT, "오른쪽"),
        (RETRO_DEVICE_ID_JOYPAD_A, "확인 (OK)"),
        (RETRO_DEVICE_ID_JOYPAD_B, "취소 (CLR)"),
        (RETRO_DEVICE_ID_JOYPAD_X, "5"),
        (RETRO_DEVICE_ID_JOYPAD_Y, "0"),
        (RETRO_DEVICE_ID_JOYPAD_L, "왼쪽 소프트키"),
        (RETRO_DEVICE_ID_JOYPAD_R, "오른쪽 소프트키"),
        (RETRO_DEVICE_ID_JOYPAD_L2, "*"),
        (RETRO_DEVICE_ID_JOYPAD_R2, "#"),
        (RETRO_DEVICE_ID_JOYPAD_START, "왼쪽 소프트키"),
        (RETRO_DEVICE_ID_JOYPAD_SELECT, "오른쪽 소프트키"),
        (RETRO_DEVICE_ID_JOYPAD_L3, "0"),
        (RETRO_DEVICE_ID_JOYPAD_R3, "5"),
    ];
    let names: Vec<CString> = pad.iter().map(|(_, n)| CString::new(*n).unwrap_or_default()).collect();
    let analog_left = CString::new("방향 (왼쪽 스틱)").unwrap_or_default();
    let analog_right = CString::new("숫자 1~9 (오른쪽 스틱)").unwrap_or_default();
    let mut list: Vec<retro_input_descriptor> = pad
        .iter()
        .zip(&names)
        .map(|((id, _), name)| retro_input_descriptor {
            port: 0,
            device: RETRO_DEVICE_JOYPAD,
            index: 0,
            id: *id,
            description: name.as_ptr(),
        })
        .collect();
    for (index, name) in [(RETRO_DEVICE_INDEX_ANALOG_LEFT, &analog_left), (RETRO_DEVICE_INDEX_ANALOG_RIGHT, &analog_right)] {
        for id in [RETRO_DEVICE_ID_ANALOG_X, RETRO_DEVICE_ID_ANALOG_Y] {
            list.push(retro_input_descriptor {
                port: 0,
                device: RETRO_DEVICE_ANALOG,
                index,
                id,
                description: name.as_ptr(),
            });
        }
    }
    list.push(retro_input_descriptor {
        port: 0,
        device: 0,
        index: 0,
        id: 0,
        description: ptr::null(),
    });
    unsafe { env(RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS, list.as_mut_ptr() as *mut c_void) };
}

// ---------------------------------------------------------------------------------------------
// libretro API
// ---------------------------------------------------------------------------------------------

#[unsafe(no_mangle)]
pub extern "C" fn retro_api_version() -> c_uint {
    RETRO_API_VERSION
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_set_environment(cb: Option<retro_environment_t>) {
    if let Ok(mut c) = CALLBACKS.lock() {
        c.environment = cb;
    }
    guard("retro_set_environment", (), || {
        log::init();
        declare_variables();
    });
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_set_video_refresh(cb: Option<retro_video_refresh_t>) {
    if let Ok(mut c) = CALLBACKS.lock() {
        c.video = cb;
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_set_audio_sample(_cb: Option<retro_audio_sample_t>) {}

#[unsafe(no_mangle)]
pub extern "C" fn retro_set_audio_sample_batch(cb: Option<retro_audio_sample_batch_t>) {
    if let Ok(mut c) = CALLBACKS.lock() {
        c.audio_batch = cb;
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_set_input_poll(cb: Option<retro_input_poll_t>) {
    if let Ok(mut c) = CALLBACKS.lock() {
        c.input_poll = cb;
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_set_input_state(cb: Option<retro_input_state_t>) {
    if let Ok(mut c) = CALLBACKS.lock() {
        c.input_state = cb;
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_init() {
    guard("retro_init", (), log::init);
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_deinit() {
    if let Ok(mut core) = CORE.lock() {
        core.take();
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn retro_get_system_info(info: *mut retro_system_info) {
    if info.is_null() {
        return;
    }
    static NAME: &CStr = c"WIPI (wie)";
    static VERSION: &CStr = c"0.1.7-oneemu";
    static EXTENSIONS: &CStr = c"zip|jar|jad";
    unsafe {
        *info = retro_system_info {
            library_name: NAME.as_ptr(),
            library_version: VERSION.as_ptr(),
            valid_extensions: EXTENSIONS.as_ptr(),
            need_fullpath: true,
            block_extract: true,
        };
    }
}

fn geometry(w: u32, h: u32) -> retro_game_geometry {
    retro_game_geometry {
        base_width: w,
        base_height: h,
        max_width: MAX_DIMENSION,
        max_height: MAX_DIMENSION,
        aspect_ratio: w as f32 / h.max(1) as f32,
    }
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn retro_get_system_av_info(info: *mut retro_system_av_info) {
    if info.is_null() {
        return;
    }
    let (w, h) = CORE.lock().ok().and_then(|c| c.as_ref().map(|c| c.geometry)).unwrap_or((host::DEFAULT_WIDTH, host::DEFAULT_HEIGHT));
    unsafe {
        *info = retro_system_av_info {
            geometry: geometry(w, h),
            timing: retro_system_timing {
                fps: 60.0,
                sample_rate: SAMPLE_RATE as f64,
            },
        };
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_set_controller_port_device(_port: c_uint, _device: c_uint) {}

#[unsafe(no_mangle)]
pub extern "C" fn retro_reset() {
    guard("retro_reset", (), || {
        let Ok(mut guard) = CORE.lock() else { return };
        let Some(old) = guard.take() else { return };
        let request = old.request.clone();
        let (bitmasks, rumble) = (old.bitmasks, old.rumble);
        drop(old); // joins the old worker first
        match Worker::start(request.clone()) {
            Ok((worker, info)) => {
                *guard = Some(Core {
                    worker,
                    request,
                    geometry: (info.width, info.height),
                    pad_profile: info.pad_profile,
                    deadzone: info.deadzone,
                    info,
                    recycle: FrameOutput::default(),
                    bitmasks,
                    rumble,
                    rumble_frames: 0,
                    failed: false,
                    shutdown_sent: false,
                });
            }
            Err(e) => {
                tracing::error!("reset failed: {e:#}");
                show_message(&format!("다시 시작 실패: {e}"), 600);
            }
        }
    });
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn retro_load_game(game: *const retro_game_info) -> bool {
    guard("retro_load_game", false, || unsafe { load_game(game) })
}

unsafe fn load_game(game: *const retro_game_info) -> bool {
    if game.is_null() || unsafe { (*game).path.is_null() } {
        show_message("WIPI 코어는 파일 경로가 필요합니다", 300);
        return false;
    }
    let path = PathBuf::from(unsafe { CStr::from_ptr((*game).path) }.to_string_lossy().to_string());

    let mut format = RETRO_PIXEL_FORMAT_XRGB8888;
    if !unsafe { env(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, &mut format as *mut _ as *mut c_void) } {
        tracing::error!("frontend refused XRGB8888");
        return false;
    }

    let system_dir = env_dir(RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY).unwrap_or_else(|| path.parent().map(|p| p.to_path_buf()).unwrap_or_default());
    let save_dir = env_dir(RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY).unwrap_or_else(|| system_dir.join("wipi").join("saves"));
    let assets_dir = env_dir(RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY);
    let bitmasks = unsafe { env(RETRO_ENVIRONMENT_GET_INPUT_BITMASKS, ptr::null_mut()) };

    let mut rumble_iface = retro_rumble_interface { set_rumble_state: None };
    let rumble = if unsafe { env(RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE, &mut rumble_iface as *mut _ as *mut c_void) } {
        rumble_iface.set_rumble_state
    } else {
        None
    };

    declare_input_descriptors();

    let request = LoadRequest {
        path: path.clone(),
        system_dir,
        save_dir,
        assets_dir,
        options: read_variables(),
    };
    tracing::info!("loading {}", path.display());

    match Worker::start(request.clone()) {
        Ok((worker, info)) => {
            tracing::info!("{} game \"{}\" at {}x{}", info.carrier.name(), info.title, info.width, info.height);
            let mut note = format!("{} · {}", info.carrier.name(), info.title);
            if !info.has_soundfont {
                note.push_str(" (사운드폰트 없음: 배경음악이 나오지 않습니다)");
            }
            show_message(&note, 180);
            if let Ok(mut core) = CORE.lock() {
                *core = Some(Core {
                    worker,
                    request,
                    geometry: (info.width, info.height),
                    pad_profile: info.pad_profile,
                    deadzone: info.deadzone,
                    info,
                    recycle: FrameOutput::default(),
                    bitmasks,
                    rumble,
                    rumble_frames: 0,
                    failed: false,
                    shutdown_sent: false,
                });
            }
            true
        }
        Err(e) => {
            tracing::error!("load failed: {e:#}");
            show_message(&format!("게임을 불러오지 못했습니다: {e}"), 600);
            false
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_load_game_special(_type: c_uint, _info: *const retro_game_info, _num: usize) -> bool {
    false
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_unload_game() {
    if let Ok(mut core) = CORE.lock() {
        core.take();
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_get_region() -> c_uint {
    RETRO_REGION_NTSC
}

fn poll_keys(cb: &Callbacks, core: &Core) -> KeySet {
    let (Some(poll), Some(state)) = (cb.input_poll, cb.input_state) else {
        return KeySet::default();
    };
    unsafe { poll() };
    let st = |device: c_uint, index: c_uint, id: c_uint| unsafe { state(0, device, index, id) };

    let buttons = if core.bitmasks {
        st(RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK) as u16
    } else {
        (0..16).fold(0u16, |acc, id| if st(RETRO_DEVICE_JOYPAD, 0, id) != 0 { acc | (1 << id) } else { acc })
    };
    let pad = PadState {
        buttons,
        left: (
            st(RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_LEFT, RETRO_DEVICE_ID_ANALOG_X),
            st(RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_LEFT, RETRO_DEVICE_ID_ANALOG_Y),
        ),
        right: (
            st(RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_RIGHT, RETRO_DEVICE_ID_ANALOG_X),
            st(RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_RIGHT, RETRO_DEVICE_ID_ANALOG_Y),
        ),
    };
    let mut keys = map_pad(&pad, core.pad_profile, core.deadzone);
    for (retrok, key) in KEYBOARD_MAP {
        if st(RETRO_DEVICE_KEYBOARD, 0, *retrok) != 0 {
            keys.press(*key);
        }
    }
    keys
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_run() {
    guard("retro_run", (), run);
}

fn run() {
    let cb = callbacks();
    let Ok(mut guard) = CORE.lock() else { return };
    let Some(core) = guard.as_mut() else {
        return;
    };

    let mut updated = false;
    if unsafe { env(RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE, &mut updated as *mut bool as *mut c_void) } && updated {
        core.request.options = read_variables();
        core.worker.set_options(core.request.options.clone());
    }

    let keys = if core.failed { KeySet::default() } else { poll_keys(&cb, core) };
    let recycle = std::mem::take(&mut core.recycle);
    let out = core.worker.run_frame(keys, recycle).unwrap_or_else(|| FrameOutput {
        error: Some("worker thread stopped".into()),
        ..Default::default()
    });

    if let Some(e) = &out.error
        && !core.failed
    {
        core.failed = true;
        show_message(&format!("게임 실행 중 오류로 멈췄습니다: {e}"), 900);
    }

    // Video
    if let Some(video) = cb.video {
        match &out.video {
            Some((pixels, w, h)) if *w > 0 && *h > 0 && pixels.len() >= (*w * *h) as usize => {
                if (*w, *h) != core.geometry {
                    core.geometry = (*w, *h);
                    let mut g = geometry(*w, *h);
                    unsafe { env(RETRO_ENVIRONMENT_SET_GEOMETRY, &mut g as *mut _ as *mut c_void) };
                }
                unsafe { video(pixels.as_ptr() as *const c_void, *w, *h, (*w as usize) * 4) };
            }
            _ => unsafe { video(ptr::null(), core.geometry.0, core.geometry.1, 0) },
        }
    }

    // Audio
    if let Some(batch) = cb.audio_batch {
        let frames = out.audio.len() / 2;
        let mut done = 0usize;
        let mut guard_loops = 0;
        while done < frames && guard_loops < 8 {
            let n = unsafe { batch(out.audio.as_ptr().add(done * 2), frames - done) };
            if n == 0 {
                break;
            }
            done += n;
            guard_loops += 1;
        }
    }
    debug_assert!(out.audio.is_empty() || out.audio.len() == FRAMES_PER_RUN * 2);

    // Rumble
    if let Some(rumble) = core.rumble {
        if out.vibrate_ms > 0 {
            core.rumble_frames = ((out.vibrate_ms * 60).div_ceil(1000)).clamp(1, 120) as u32;
            unsafe { rumble(0, RETRO_RUMBLE_STRONG, 0xffff) };
        } else if core.rumble_frames > 0 {
            core.rumble_frames -= 1;
            if core.rumble_frames == 0 {
                unsafe { rumble(0, RETRO_RUMBLE_STRONG, 0) };
            }
        }
    }

    if out.exit && !core.shutdown_sent {
        core.shutdown_sent = true;
        tracing::info!("game requested exit");
        unsafe { env(RETRO_ENVIRONMENT_SHUTDOWN, ptr::null_mut()) };
    }

    if let Some((profile, deadzone)) = out.input {
        core.pad_profile = profile;
        core.deadzone = deadzone;
    }
    core.recycle = out;
}

// Save states: wie keeps the JVM/ARM task state in pinned Rust futures, which can't be serialised.
// In-game saves go straight to files under the save directory instead (see storage.rs).

#[unsafe(no_mangle)]
pub extern "C" fn retro_serialize_size() -> usize {
    0
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_serialize(_data: *mut c_void, _size: usize) -> bool {
    false
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_unserialize(_data: *const c_void, _size: usize) -> bool {
    false
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_cheat_reset() {
    with_worker(|w| w.call(|s| s.reset_cheats()));
}

/// Codes are raw `AAAAAAAA:VALUE` memory locks, `P:` pointer chains and `J:` Java field paths, kept fixed every
/// frame or written once with a `once:` prefix (see cheat.rs).
#[unsafe(no_mangle)]
pub extern "C" fn retro_cheat_set(index: c_uint, enabled: bool, code: *const c_char) {
    let code = if code.is_null() { String::new() } else { unsafe { CStr::from_ptr(code) }.to_string_lossy().into_owned() };
    with_worker(move |w| w.call(move |s| s.set_cheat(index, enabled, &code)));
}

fn with_worker<R>(f: impl FnOnce(&Worker) -> Option<R>) -> Option<R> {
    let guard = CORE.lock().ok()?;
    f(&guard.as_ref()?.worker)
}

// OneEmu extension (looked up with dlsym; other frontends just don't call it): step-by-step memory search
// for the cheat finder. op: 0 = new search for `value`, 1 = now equals `value`, 2 = changed, 3 = unchanged,
// 4 = increased, 5 = decreased. size: value width in bytes (1, 2, 4). Returns the candidates left, or -1
// when the game has no searchable memory.

#[unsafe(no_mangle)]
pub extern "C" fn oneemu_memsearch(op: c_int, size: c_int, value: u32) -> i64 {
    let Some(op) = cheat::SearchOp::from_code(op, value) else {
        return -1;
    };
    with_worker(move |w| w.call(move |s| s.cheat_search(op, size as u8)))
        .flatten()
        .map_or(-1, |n| n as i64)
}

/// Fills up to `max` (address, current value) pairs and returns how many; `size` gets the value width.
#[unsafe(no_mangle)]
pub extern "C" fn oneemu_memsearch_results(addresses: *mut u32, values: *mut u32, max: c_int, size: *mut c_int) -> c_int {
    if addresses.is_null() || values.is_null() || max <= 0 {
        return 0;
    }
    let Some((results, width)) = with_worker(move |w| w.call(move |s| s.cheat_results(max as usize))) else {
        return 0;
    };
    for (i, (a, v)) in results.iter().enumerate() {
        unsafe {
            *addresses.add(i) = *a;
            *values.add(i) = *v;
        }
    }
    if !size.is_null() {
        unsafe { *size = c_int::from(width) };
    }
    results.len() as c_int
}

#[unsafe(no_mangle)]
pub extern "C" fn oneemu_memwrite(address: u32, size: c_int, value: u32) -> bool {
    with_worker(move |w| w.call(move |s| s.cheat_write(address, size as u8, value))).unwrap_or(false)
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_get_memory_data(_id: c_uint) -> *mut c_void {
    ptr::null_mut()
}

#[unsafe(no_mangle)]
pub extern "C" fn retro_get_memory_size(_id: c_uint) -> usize {
    0
}
