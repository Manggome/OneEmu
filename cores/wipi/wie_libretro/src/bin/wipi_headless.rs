//! Headless runner for compatibility testing (idea from ParkJeongseop/WIPI-Emulator's headless.rs).
//!
//!   wipi_headless <game.zip|jar|jad> [--frames N] [--keys "120:OK,200:5,260:LSK"] [--dump DIR] [--every N]
//!                 [--option key=value ...] [--system DIR]
//!
//! Runs N frames (default 1800 = 30 s of game time) with scripted key taps (each held 4 frames), dumps
//! every N-th frame as PPM, and prints timing plus how much of the last frame is non-black.

use std::{fs, path::PathBuf, time::Instant};

use wie_backend::KeyCode;
use wipi_libretro::{FrameOutput, KeySet, LoadRequest, RawOptions, Session};

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
    let mut keys: Vec<(u64, KeyCode)> = Vec::new();
    let mut dump: Option<PathBuf> = None;
    let mut every = 60u64;
    let mut options = Vec::new();
    let mut system = std::env::temp_dir().join("wipi_headless");

    while let Some(a) = args.next() {
        match a.as_str() {
            "--frames" => frames = args.next().and_then(|v| v.parse().ok()).unwrap_or(frames),
            "--every" => every = args.next().and_then(|v| v.parse().ok()).unwrap_or(every).max(1),
            "--dump" => dump = args.next().map(PathBuf::from),
            "--system" => system = args.next().map(PathBuf::from).unwrap_or(system),
            "--option" => {
                if let Some((k, v)) = args.next().as_deref().and_then(|kv| kv.split_once('=')) {
                    options.push((k.to_string(), v.to_string()));
                }
            }
            "--keys" => {
                for item in args.next().unwrap_or_default().split(',').filter(|s| !s.is_empty()) {
                    let (f, k) = item.split_once(':').ok_or_else(|| anyhow::anyhow!("bad key item {item}"))?;
                    let key = parse_key(k.trim()).ok_or_else(|| anyhow::anyhow!("unknown key {k}"))?;
                    keys.push((f.trim().parse()?, key));
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
    let mut audio_peak = 0i32;
    for frame in 0..frames {
        let mut set = KeySet::default();
        for (at, key) in &keys {
            if frame >= *at && frame < at + 4 {
                set.press(*key);
            }
        }
        let t = Instant::now();
        out = session.run_frame(set, out);
        worst_ms = worst_ms.max(t.elapsed().as_secs_f64() * 1000.0);
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
            last = Some(v.clone());
        }
        if let (Some(d), Some((px, w, h))) = (&dump, &last)
            && frame % every == 0
        {
            write_ppm(&d.join(format!("frame_{frame:06}.ppm")), px, *w, *h)?;
        }
        if out.exit {
            println!("game exited at frame {frame}");
            break;
        }
    }
    let total = started.elapsed().as_secs_f64();
    println!("frames: {ran}, avg {:.2} ms/frame, worst {:.2} ms", total * 1000.0 / ran.max(1) as f64, worst_ms);
    println!("audio: sound in {audible_frames} of {ran} frames, peak {audio_peak}");

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
