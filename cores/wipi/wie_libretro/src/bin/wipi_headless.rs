//! Headless runner for compatibility testing (idea from ParkJeongseop/WIPI-Emulator's headless.rs).
//!
//!   wipi_headless <game.zip|jar|jad> [--frames N] [--keys "120:OK,200:5,260:LSK"] [--dump DIR] [--every N]
//!                 [--option key=value ...] [--system DIR] [--wav OUT.wav] [--assets DIR] [--keys-file FILE]
//!                 [--cheat "600:start:4:1500,900:dec,960:write:99999,1200:lock:99999"] [--cheat-file FILE]
//!
//! Cheat steps (`FRAME:STEP`, comma-separated, or one per line in --cheat-file), run before that frame:
//!   search: `start:SIZE:VALUE`, `equal:VALUE`, `changed`, `unchanged`, `inc`, `dec`, `write:VALUE` (all
//!   candidates), `lock:VALUE` (first candidate, as a raw code);
//!   codes: `code:CODE` enables CODE as the next cheat (any syntax in cheat.rs, e.g. `code:once:P:0012A4C0>10:5000`),
//!   `off:N` disables cheat N, `status` prints where each enabled code points and the value there;
//!   research: `locate` (every search candidate, up to 16) or `locate:ADDR[:DEPTH[:MAXOFF]]` (hex address; Java
//!   depth default 4, pointer offset limit default 0x10000) prints restart-proof locators reaching the address;
//!   `probe:LOCATOR` / `probes:FILE` (one locator per line) print what locators resolve to now;
//!   `peek:ADDR:LEN` prints LEN bytes (hex) from ADDR as 32-bit words; `java` lists the application's Java
//!   classes, `java:Class` its static fields with values, `java:Class.field.field` the object or array there.
//!
//! Runs N frames (default 1800 = 30 s of game time) with scripted key taps (each held 4 frames), dumps
//! every N-th frame as PPM, and prints timing plus how much of the last frame is non-black.

use std::{fs, path::PathBuf, time::Instant};

use wie_backend::KeyCode;
use wipi_libretro::{FrameOutput, KeySet, LoadRequest, RawOptions, SearchOp, Session, cheat::Value};

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

/// 44.1 kHz stereo 16-bit PCM WAV.
fn write_wav(path: &PathBuf, samples: &[i16]) -> std::io::Result<()> {
    let bytes = (samples.len() * 2) as u32;
    let mut data = Vec::with_capacity(44 + bytes as usize);
    data.extend_from_slice(b"RIFF");
    data.extend_from_slice(&(36 + bytes).to_le_bytes());
    data.extend_from_slice(b"WAVEfmt ");
    data.extend_from_slice(&16u32.to_le_bytes());
    data.extend_from_slice(&1u16.to_le_bytes()); // PCM
    data.extend_from_slice(&2u16.to_le_bytes()); // stereo
    data.extend_from_slice(&44_100u32.to_le_bytes());
    data.extend_from_slice(&(44_100u32 * 4).to_le_bytes());
    data.extend_from_slice(&4u16.to_le_bytes());
    data.extend_from_slice(&16u16.to_le_bytes());
    data.extend_from_slice(b"data");
    data.extend_from_slice(&bytes.to_le_bytes());
    for s in samples {
        data.extend_from_slice(&s.to_le_bytes());
    }
    fs::write(path, data)
}

/// `start:SIZE:VALUE`, `equal:VALUE`, `changed`, `unchanged`, `inc`, `dec`, `write:VALUE` (all candidates),
/// `lock:VALUE` (first candidate, as a cheat code).
fn run_cheat_step(session: &mut Session, step: &str, next_index: &mut u32) {
    if let Some(code) = step.strip_prefix("code:") {
        session.set_cheat(*next_index, true, code);
        println!("cheat {step}: enabled as #{next_index}");
        *next_index += 1;
        return;
    }
    if let Some(n) = step.strip_prefix("off:") {
        session.set_cheat(n.trim().parse().unwrap_or(u32::MAX), false, "");
        println!("cheat {step}: disabled");
        return;
    }
    if step == "status" {
        for s in session.cheat_status() {
            let at = s.at.map(|at| format!("{:08X} ({} bytes)", at.address, at.size)).unwrap_or_else(|| "unresolved".into());
            println!("cheat status #{}: {} once={} done={} -> {at} now {}", s.index, s.code.locator, s.code.once, s.done, show(s.now));
        }
        return;
    }
    if step == "locate" || step.starts_with("locate:") {
        let args: Vec<&str> = step.split(':').skip(1).collect();
        let depth = args.get(1).and_then(|x| x.parse().ok()).unwrap_or(4);
        let max_offset = args.get(2).and_then(|x| u32::from_str_radix(x.trim_start_matches("0x"), 16).ok()).unwrap_or(0x10000);
        let targets: Vec<u32> = match args.first().and_then(|x| u32::from_str_radix(x.trim_start_matches("0x"), 16).ok()) {
            Some(address) => vec![address],
            None => session.cheat_results(16).0.into_iter().map(|(a, _)| a).collect(),
        };
        for target in targets {
            let found = session.cheat_locate(target, depth, max_offset);
            println!("cheat locate {target:08X}: {} locators", found.len());
            for f in found.iter().take(60) {
                println!("  {}  [{} bytes] {}", f.locator, f.size, f.note);
            }
        }
        return;
    }
    if step == "java" || step.starts_with("java:") {
        for line in session.cheat_java(step.strip_prefix("java:").unwrap_or("")) {
            println!("cheat java {line}");
        }
        return;
    }
    if let Some(rest) = step.strip_prefix("peek:") {
        let (address, len) = rest.split_once(':').unwrap_or((rest, "40"));
        let hex = |x: &str| u32::from_str_radix(x.trim_start_matches("0x"), 16).unwrap_or(0);
        let (address, len) = (hex(address) & !3, hex(len));
        for row in (0..len).step_by(32) {
            let words: Vec<String> = (row..(row + 32).min(len))
                .step_by(4)
                .map(|o| match session.cheat_probe(&format!("P:{:08X}:0", address + o)) {
                    Some((_, _, Value::Int(v))) => format!("{v:08X}"),
                    _ => "--------".into(),
                })
                .collect();
            println!("cheat peek {:08X}: {}", address + row, words.join(" "));
        }
        return;
    }
    if let Some(rest) = step.strip_prefix("probe:").or_else(|| step.strip_prefix("probes:")) {
        let locators: Vec<String> = if step.starts_with("probes:") {
            fs::read_to_string(rest).unwrap_or_default().lines().map(|l| l.split_whitespace().next().unwrap_or("").to_string()).filter(|l| !l.is_empty()).collect()
        } else {
            vec![rest.to_string()]
        };
        for locator in locators {
            match session.cheat_probe(&format!("{locator}:0")) {
                Some((address, size, value)) => println!("cheat probe {locator} -> {address:08X} ({size} bytes) = {}", show(Some(value))),
                None => println!("cheat probe {locator} -> unresolved"),
            }
        }
        return;
    }
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
                session.set_cheat(*next_index, true, &code);
                println!("cheat {step}: locked with code {code} as #{next_index}");
                *next_index += 1;
            }
        }
        _ => println!("cheat {step}: unknown step"),
    }
}

fn show(value: Option<Value>) -> String {
    match value {
        Some(Value::Int(v)) => v.to_string(),
        Some(Value::Float(v)) => v.to_string(),
        None => "-".into(),
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
    let mut assets: Option<PathBuf> = None;
    let mut options = Vec::new();
    // (frame, step): memory-search steps, to try the cheat finder headless.
    let mut cheats: Vec<(u64, String)> = Vec::new();
    let mut system = std::env::temp_dir().join("wipi_headless");
    // --wav PATH: the whole audio stream (44.1 kHz stereo s16) for listen-free analysis.
    let mut wav: Option<PathBuf> = None;

    while let Some(a) = args.next() {
        match a.as_str() {
            "--timeline" => timeline = true,
            "--frames" => frames = args.next().and_then(|v| v.parse().ok()).unwrap_or(frames),
            "--every" => every = args.next().and_then(|v| v.parse().ok()).unwrap_or(every).max(1),
            "--dump" => dump = args.next().map(PathBuf::from),
            "--wav" => wav = args.next().map(PathBuf::from),
            "--system" => system = args.next().map(PathBuf::from).unwrap_or(system),
            "--option" => {
                if let Some((k, v)) = args.next().as_deref().and_then(|kv| kv.split_once('=')) {
                    options.push((k.to_string(), v.to_string()));
                }
            }
            "--cheat" | "--cheat-file" => {
                let list = match a.as_str() {
                    "--cheat" => args.next().unwrap_or_default().replace(',', "
"),
                    _ => fs::read_to_string(args.next().unwrap_or_default())?,
                };
                for item in list.lines().map(str::trim).filter(|s| !s.is_empty() && !s.starts_with('#')) {
                    let (f, step) = item.split_once(':').ok_or_else(|| anyhow::anyhow!("bad cheat item {item}"))?;
                    cheats.push((f.trim().parse()?, step.trim().to_string()));
                }
            }
            "--assets" => assets = args.next().map(PathBuf::from),
            "--keys" | "--keys-file" => {
                let list = match a.as_str() {
                    "--keys" => args.next().unwrap_or_default(),
                    _ => fs::read_to_string(args.next().unwrap_or_default())?,
                };
                for item in list.split(|c: char| c == ',' || c.is_whitespace()).filter(|s| !s.is_empty()) {
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
        assets_dir: assets,
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

    let mut next_cheat = 0u32;
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
    let mut pcm: Vec<i16> = Vec::new();
    for frame in 0..frames {
        let mut set = KeySet::default();
        for (at, key, hold) in &keys {
            if frame >= *at && frame < at + hold {
                set.press(*key);
            }
        }
        for (_, step) in cheats.iter().filter(|(at, _)| *at == frame) {
            run_cheat_step(&mut session, step, &mut next_cheat);
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
        if wav.is_some() {
            pcm.extend_from_slice(&out.audio);
        }
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
    if let Some(path) = &wav {
        write_wav(path, &pcm)?;
    }
    let stdout = session.stdout();
    if !stdout.is_empty() {
        println!("stdout: {}", String::from_utf8_lossy(&stdout));
    }
    Ok(())
}
