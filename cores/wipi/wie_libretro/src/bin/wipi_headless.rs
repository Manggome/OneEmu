//! Headless runner for compatibility testing (idea from ParkJeongseop/WIPI-Emulator's headless.rs).
//!
//!   wipi_headless <game.zip|jar|jad> [--frames N] [--keys "120:OK,200:5,260:LSK"] [--dump DIR] [--every N]
//!                 [--option key=value ...] [--system DIR]
//!                 [--cheat "600:start:4:1500,900:dec,960:write:99999,1200:lock:99999"]
//!
//! Runs N frames (default 1800 = 30 s of game time) with scripted key taps (each held 4 frames), dumps
//! every N-th frame as PPM, and prints timing plus how much of the last frame is non-black.

use std::{fs, path::PathBuf, time::Instant};

use wie_backend::KeyCode;
use wipi_libretro::{FrameOutput, KeySet, LoadRequest, RawOptions, SearchOp, Session};

fn parse_key(name: &str) -> Option<KeyCode> {
    Some(match name {
        "OK" => KeyCode::OK,
        "UP" => KeyCode::UP,
        "DOWN" => KeyCode::DOWN,
        "LEFT" => KeyCode::LEFT,
        "RIGHT" => KeyCode::RIGHT,
        "LSK" => KeyCode::LEFT_SOFT_KEY,
        "RSK" => KeyCode::RIGHT_SOFT_KEY,
        "CLR" => KeyCode::CLEAR,
        "CALL" => KeyCode::CALL,
        "END" => KeyCode::HANGUP,
        "0" => KeyCode::NUM0,
        "1" => KeyCode::NUM1,
        "2" => KeyCode::NUM2,
        "3" => KeyCode::NUM3,
        "4" => KeyCode::NUM4,
        "5" => KeyCode::NUM5,
        "6" => KeyCode::NUM6,
        "7" => KeyCode::NUM7,
        "8" => KeyCode::NUM8,
        "9" => KeyCode::NUM9,
        "*" => KeyCode::STAR,
        "#" => KeyCode::HASH,
        _ => return None,
    })
}

fn write_ppm(path: &PathBuf, pixels: &[u32], w: u32, h: u32) -> std::io::Result<()> {
    let mut data = format!("P6\n{w} {h}\n255\n").into_bytes();
    for p in pixels.iter().take((w * h) as usize) {
        data.extend_from_slice(&[(p >> 16) as u8, (p >> 8) as u8, *p as u8]);
    }
    fs::write(path, data)
}

/// `start:SIZE:VALUE`, `equal:VALUE`, `changed`, `unchanged`, `inc`, `dec`, `write:VALUE` (all candidates),
/// `lock:VALUE` (first candidate, as a cheat code).
fn run_cheat_step(session: &mut Session, step: &str) {
    let parts: Vec<&str> = step.split(':').collect();
    let num = |i: usize| parts.get(i).and_then(|v| v.parse::<u32>().ok()).unwrap_or(0);
    let (op, size) = match parts[0] {
        "start" => (Some(SearchOp::Start(num(2))), num(1) as u8),
        "equal" => (Some(SearchOp::Equal(num(1))), 0),
        "changed" => (Some(SearchOp::Changed), 0),
        "unchanged" => (Some(SearchOp::Unchanged), 0),
        "inc" => (Some(SearchOp::Increased), 0),
        "dec" => (Some(SearchOp::Decreased), 0),
        _ => (None, 0),
    };
    if let Some(op) = op {
        let left = session.cheat_search(op, size);
        let (results, width) = session.cheat_results(5);
        println!("cheat {step}: {left:?} left, first {results:x?} ({width} bytes)");
        return;
    }
    let (results, width) = session.cheat_results(10_000);
    match parts[0] {
        "write" => {
            let n = results.iter().filter(|(a, _)| session.cheat_write(*a, width, num(1))).count();
            println!("cheat {step}: wrote {n} addresses");
        }
        "lock" => {
            if let Some((a, _)) = results.first() {
                let code = format!("{a:08X}:{:0w$X}", num(1), w = width as usize * 2);
                session.set_cheat(0, true, &code);
                println!("cheat {step}: locked with code {code}");
            }
        }
        _ => println!("cheat {step}: unknown step"),
    }
}

/// CPU time of the calling thread in milliseconds (PERF measurement; not robust to other processes otherwise).
#[cfg(windows)]
fn thread_cpu_ms() -> f64 {
    #[repr(C)]
    #[derive(Default)]
    struct FileTime(u32, u32);
    unsafe extern "system" {
        fn GetCurrentThread() -> isize;
        fn GetThreadTimes(thread: isize, creation: *mut FileTime, exit: *mut FileTime, kernel: *mut FileTime, user: *mut FileTime) -> i32;
    }
    let (mut c, mut e, mut k, mut u) = (FileTime::default(), FileTime::default(), FileTime::default(), FileTime::default());
    unsafe { GetThreadTimes(GetCurrentThread(), &mut c, &mut e, &mut k, &mut u) };
    let t = |f: &FileTime| ((f.1 as u64) << 32 | f.0 as u64) as f64 / 10_000.0;
    t(&k) + t(&u)
}
#[cfg(not(windows))]
fn thread_cpu_ms() -> f64 {
    0.0
}

fn main() -> anyhow::Result<()> {
    // Same 16 MiB stack the libretro core's worker gets; wie's runtime recurses deeply.
    std::thread::Builder::new()
        .name("wie-headless".into())
        .stack_size(16 << 20)
        .spawn(run)?
        .join()
        .map_err(|_| anyhow::anyhow!("emulator thread panicked"))?
}

fn run() -> anyhow::Result<()> {
    // wie's own warnings (unimplemented APIs etc.) to stderr; WIPI_LOG=info|debug for more.
    let level = match std::env::var("WIPI_LOG").as_deref() {
        Ok("debug") => tracing::Level::DEBUG,
        Ok("info") => tracing::Level::INFO,
        _ => tracing::Level::WARN,
    };
    let _ = tracing_subscriber::fmt().with_writer(std::io::stderr).without_time().with_max_level(level).try_init();

    let mut args = std::env::args().skip(1);
    let mut game = None;
    let mut frames = 1800u64;
    let mut keys: Vec<(u64, KeyCode, u64)> = Vec::new();
    let mut dump: Option<PathBuf> = None;
    let mut every = 60u64;
    let mut timeline = false;
    let mut options = Vec::new();
    // (frame, step): memory-search steps, to try the cheat finder headless.
    let mut cheats: Vec<(u64, String)> = Vec::new();
    let mut system = std::env::temp_dir().join("wipi_headless");

    while let Some(a) = args.next() {
        match a.as_str() {
            "--timeline" => timeline = true,
            "--frames" => frames = args.next().and_then(|v| v.parse().ok()).unwrap_or(frames),
            "--every" => every = args.next().and_then(|v| v.parse().ok()).unwrap_or(every).max(1),
            "--dump" => dump = args.next().map(PathBuf::from),
            "--system" => system = args.next().map(PathBuf::from).unwrap_or(system),
            "--option" => {
                if let Some((k, v)) = args.next().as_deref().and_then(|kv| kv.split_once('=')) {
                    options.push((k.to_string(), v.to_string()));
                }
            }
            "--cheat" => {
                for item in args.next().unwrap_or_default().split(',').filter(|s| !s.is_empty()) {
                    let (f, step) = item.split_once(':').ok_or_else(|| anyhow::anyhow!("bad cheat item {item}"))?;
                    cheats.push((f.trim().parse()?, step.trim().to_string()));
                }
            }
            "--keys" => {
                for item in args.next().unwrap_or_default().split(',').filter(|s| !s.is_empty()) {
                    let (f, k) = item.split_once(':').ok_or_else(|| anyhow::anyhow!("bad key item {item}"))?;
                    // "KEY~N" holds the key for N frames instead of the default 4-frame tap.
                    let (k, hold) = match k.split_once('~') {
                        Some((k, n)) => (k, n.trim().parse()?),
                        None => (k, 4u64),
                    };
                    let key = parse_key(k.trim()).ok_or_else(|| anyhow::anyhow!("unknown key {k}"))?;
                    keys.push((f.trim().parse()?, key, hold));
                }
            }
            _ => game = Some(PathBuf::from(a)),
        }
    }
    let game = game.ok_or_else(|| anyhow::anyhow!("usage: wipi_headless <game> [--frames N] [--keys ...] [--dump DIR]"))?;
    if let Some(d) = &dump {
        fs::create_dir_all(d)?;
    }

    let (mut session, info) = Session::load(LoadRequest {
        path: game.clone(),
        system_dir: system.join("system"),
        save_dir: system.join("saves"),
        assets_dir: None,
        options: RawOptions { values: options },
    })?;
    println!(
        "loaded: {} [{}] {}x{} soundfont={} quirks={:?}",
        info.title,
        info.carrier.name(),
        info.width,
        info.height,
        info.has_soundfont,
        info.quirk_name
    );

    let mut out = FrameOutput::default();
    let mut last: Option<(Vec<u32>, u32, u32)> = None;
    let started = Instant::now();
    let mut worst_ms = 0f64;
    let mut ran = 0;
    let mut audible_frames = 0u64;
    let mut presented = 0u64;
    // --timeline: per second of game time, new pictures and host CPU spent (spots slow stretches)
    let (mut sec_presented, mut sec_ms) = (0u64, 0f64);
    let mut sec_tcpu = thread_cpu_ms();
    let mut audio_peak = 0i32;
    for frame in 0..frames {
        let mut set = KeySet::default();
        for (at, key, hold) in &keys {
            if frame >= *at && frame < at + hold {
                set.press(*key);
            }
        }
        for (_, step) in cheats.iter().filter(|(at, _)| *at == frame) {
            run_cheat_step(&mut session, step);
        }
        let t = Instant::now();
        out = session.run_frame(set, out);
        let ms = t.elapsed().as_secs_f64() * 1000.0;
        worst_ms = worst_ms.max(ms);
        sec_ms += ms;
        ran += 1;
        let peak = out.audio.iter().map(|s| i32::from(*s).abs()).max().unwrap_or(0);
        if peak > 64 {
            audible_frames += 1;
        }
        audio_peak = audio_peak.max(peak);
        if let Some(e) = &out.error {
            println!("error at frame {frame}: {e}");
            break;
        }
        if let Some(v) = &out.video {
            presented += 1;
            sec_presented += 1;
            last = Some(v.clone());
        }
        if let (Some(d), Some((px, w, h))) = (&dump, &last)
            && frame % every == 0
        {
            write_ppm(&d.join(format!("frame_{frame:06}.ppm")), px, *w, *h)?;
        }
        if timeline && (frame + 1) % 60 == 0 {
            let tcpu = thread_cpu_ms();
            println!("t {:>4}s: {sec_presented:>2} fps, cpu {sec_ms:>6.1} ms, tcpu {:>6.1} ms", (frame + 1) / 60, tcpu - sec_tcpu);
            (sec_presented, sec_ms, sec_tcpu) = (0, 0.0, tcpu);
        }
        if out.exit {
            println!("game exited at frame {frame}");
            break;
        }
    }
    let total = started.elapsed().as_secs_f64();
    println!("frames: {ran}, avg {:.2} ms/frame, worst {:.2} ms", total * 1000.0 / ran.max(1) as f64, worst_ms);
    println!("audio: sound in {audible_frames} of {ran} frames, peak {audio_peak}");
    // Frames in which the game presented a new picture: the game's own frame rate under this CPU budget.
    println!("presented: {presented} of {ran} frames ({:.1} fps)", presented as f64 * 60.0 / ran.max(1) as f64);

    if let Some((px, w, h)) = &last {
        let lit = px.iter().filter(|p| **p & 0xffffff != 0).count();
        println!("last frame {w}x{h}: {:.1}% non-black", lit as f64 * 100.0 / px.len().max(1) as f64);
        if let Some(d) = &dump {
            write_ppm(&d.join("last.ppm"), px, *w, *h)?;
        }
    } else {
        println!("no frame was painted");
    }
    let stdout = session.stdout();
    if !stdout.is_empty() {
        println!("stdout: {}", String::from_utf8_lossy(&stdout));
    }
    Ok(())
}
