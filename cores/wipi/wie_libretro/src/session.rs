//! One running game: the wie emulator plus the host state it talks to. Lives on the worker thread.

use std::{
    path::PathBuf,
    sync::{Arc, atomic::Ordering},
    time::{Duration, Instant as StdInstant, SystemTime, UNIX_EPOCH},
};

use wie_backend::{Emulator, Event, Font, Options};

use crate::{
    archive::{self, Carrier},
    audio::Mixer,
    clock::parse_date_ms,
    host::{DEFAULT_HEIGHT, DEFAULT_WIDTH, LibretroPlatform, Shared},
    input::{KeyEvent, KeySet, KeyTracker, PadProfile},
    options::{Config, RawOptions},
    quirks::{self, Quirk},
};

const FONT: &[u8] = include_bytes!("../../assets/neodgm.ttf");

/// A tick that returns this fast means every task is asleep (or waiting for input).
const IDLE_TICK_US: u64 = 300;
/// Virtual time to skip when idle, so sleeping tasks wake without burning real time.
const IDLE_STEP_US: u64 = 1_000;

#[derive(Clone)]
pub struct LoadRequest {
    pub path: PathBuf,
    pub system_dir: PathBuf,
    pub save_dir: PathBuf,
    pub assets_dir: Option<PathBuf>,
    pub options: RawOptions,
}

pub struct LoadInfo {
    pub width: u32,
    pub height: u32,
    pub title: String,
    pub carrier: Carrier,
    pub has_soundfont: bool,
    pub quirk_name: Option<String>,
    pub pad_profile: PadProfile,
    pub deadzone: f32,
}

#[derive(Default)]
pub struct FrameOutput {
    /// Some when a new frame was painted: (pixels 0x00RRGGBB, width, height).
    pub video: Option<(Vec<u32>, u32, u32)>,
    /// Current logical screen size (for geometry changes).
    pub screen_size: (u32, u32),
    pub audio: Vec<i16>,
    pub exit: bool,
    pub vibrate_ms: u64,
    pub error: Option<String>,
    /// Input mapping in effect (it can change with the core options).
    pub input: Option<(PadProfile, f32)>,
}

pub struct Session {
    emulator: Box<dyn Emulator>,
    shared: Arc<Shared>,
    mixer: Mixer,
    keys: KeyTracker,
    config: Config,
    quirk: Option<Quirk>,
    frames: u64,
    /// Game time (µs) the current frame should reach.
    target_us: u64,
    key_events: Vec<KeyEvent>,
    failed: Option<String>,
}

impl Session {
    pub fn load(req: LoadRequest) -> anyhow::Result<(Self, LoadInfo)> {
        let wipi_dir = req.system_dir.join("wipi");

        // Carrier override has to be known before opening; quirks need the opened game. Resolve twice.
        let pre = Config::resolve(&req.options, None);
        let game = archive::open(&req.path, pre.carrier)?;
        let quirk = quirks::find(Some(&wipi_dir.join("quirks.toml")), game.aid.as_deref(), game.id.as_deref(), game.title.as_deref());
        let config = Config::resolve(&req.options, quirk.as_ref());
        if let Some(q) = &quirk {
            tracing::info!("quirks: {}", q.name.as_deref().or(q.title.as_deref()).unwrap_or("(unnamed)"));
        }

        let epoch_base_ms = config.fixed_date.as_deref().and_then(parse_date_ms).unwrap_or_else(|| {
            SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
        });

        let shared = Shared::new(epoch_base_ms, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        let font = Font::try_from_static(FONT).map_err(|e| anyhow::anyhow!("font: {e}"))?;
        let platform = Box::new(LibretroPlatform::new(shared.clone(), req.save_dir.clone(), font));

        let title = game.title.clone().unwrap_or_else(|| "WIPI".into());
        let carrier = game.carrier;
        let options = Options {
            enable_gdbserver: false,
            aot: None,
            profile: None,
        };
        let emulator = archive::create_emulator(game, platform, options)?;

        let mut soundfonts = vec![wipi_dir.join("soundfont.sf2")];
        if let Some(dir) = &req.assets_dir {
            soundfonts.push(dir.join("GeneralUser-GS.sf2"));
            soundfonts.push(dir.join("wipi").join("GeneralUser-GS.sf2"));
        }
        soundfonts.push(wipi_dir.join("GeneralUser-GS.sf2"));
        let mixer = Mixer::new(&soundfonts, config.audio);

        let (width, height) = {
            let s = shared.screen.lock().map_err(|_| anyhow::anyhow!("screen lock"))?;
            (s.width, s.height)
        };

        let info = LoadInfo {
            width,
            height,
            title,
            carrier,
            has_soundfont: mixer.has_soundfont(),
            quirk_name: quirk.as_ref().and_then(|q| q.name.clone().or(q.title.clone())),
            pad_profile: config.pad_profile,
            deadzone: config.deadzone,
        };

        let session = Self {
            emulator,
            shared,
            mixer,
            keys: KeyTracker::new(config.repeat_frames()),
            config,
            quirk,
            frames: 0,
            target_us: 0,
            key_events: Vec::new(),
            failed: None,
        };
        Ok((session, info))
    }

    pub fn config(&self) -> &Config {
        &self.config
    }

    pub fn update_options(&mut self, raw: &RawOptions) {
        let config = Config::resolve(raw, self.quirk.as_ref());
        self.mixer.set_settings(config.audio);
        self.keys.repeat_frames = config.repeat_frames();
        self.config = config;
    }

    /// Runs 1/60 s of game time. `recycle` gives back buffers from the previous frame for reuse.
    pub fn run_frame(&mut self, keys: KeySet, mut recycle: FrameOutput) -> FrameOutput {
        let mut out = FrameOutput {
            audio: std::mem::take(&mut recycle.audio),
            ..Default::default()
        };

        if self.failed.is_none() {
            if let Err(e) = self.step(keys) {
                tracing::error!("emulation stopped: {e}");
                self.failed = Some(e);
                self.mixer.stop_all();
                out.error = self.failed.clone();
            }
        }

        // Audio: commands queued during the ticks start in this run.
        let commands = self.shared.audio_commands.lock().map(|mut q| std::mem::take(&mut *q)).unwrap_or_default();
        for c in commands {
            self.mixer.command(c);
        }
        self.mixer.render(&mut out.audio);

        if let Ok(mut screen) = self.shared.screen.lock() {
            out.screen_size = (screen.width, screen.height);
            if screen.dirty {
                screen.dirty = false;
                let mut buf = recycle.video.take().map(|(b, _, _)| b).unwrap_or_default();
                buf.clear();
                buf.extend_from_slice(&screen.frame);
                out.video = Some((buf, screen.frame_width, screen.frame_height));
            }
        }

        out.input = Some((self.config.pad_profile, self.config.deadzone));
        out.exit = self.shared.exit_requested.swap(false, Ordering::AcqRel);
        out.vibrate_ms = self.shared.vibrate_ms.swap(0, Ordering::AcqRel);
        out
    }

    fn step(&mut self, keys: KeySet) -> Result<(), String> {
        self.frames += 1;

        self.key_events.clear();
        self.keys.update(keys, &mut self.key_events);
        for ev in self.key_events.drain(..) {
            self.emulator.handle_event(match ev {
                KeyEvent::Down(k) => Event::Keydown(k),
                KeyEvent::Repeat(k) => Event::Keyrepeat(k),
                KeyEvent::Up(k) => Event::Keyup(k),
            });
        }

        // Game-time target for the end of this frame; speed scales how much game time a frame is worth.
        let frame_us = 1_000_000 * self.config.speed_percent as u64 / (60 * 100);
        self.target_us += frame_us;
        let target_us = self.target_us;
        let clock = &self.shared.clock;
        // If the game fell more than a frame behind (too slow for this CPU), drop the backlog instead of
        // letting timers burst: the game slows down like it would on an overloaded phone.
        clock.catch_up_to(target_us.saturating_sub(2 * frame_us));

        let deadline = StdInstant::now() + Duration::from_millis(self.config.cpu_budget_ms as u64);
        while clock.vt_us() < target_us {
            if self.shared.redraw_requested.swap(false, Ordering::AcqRel) {
                self.emulator.handle_event(Event::Redraw);
            }

            clock.begin_tick();
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| self.emulator.tick()));
            let spent = clock.end_tick();
            match result {
                Ok(Ok(())) => {}
                Ok(Err(e)) => return Err(format!("{e}")),
                Err(panic) => return Err(panic_message(&panic)),
            }

            if spent < IDLE_TICK_US {
                clock.advance(IDLE_STEP_US);
            }
            if self.shared.exit_requested.load(Ordering::Acquire) || StdInstant::now() >= deadline {
                break;
            }
        }

        if self.shared.redraw_requested.swap(false, Ordering::AcqRel) {
            self.emulator.handle_event(Event::Redraw);
        }
        Ok(())
    }

    /// Everything the game wrote to stdout (tests / headless runs).
    pub fn stdout(&self) -> Vec<u8> {
        self.shared.stdout.lock().map(|x| x.clone()).unwrap_or_default()
    }

    pub fn exit_requested(&self) -> bool {
        self.shared.exit_requested.load(Ordering::Acquire)
    }
}

pub fn panic_message(panic: &Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = panic.downcast_ref::<&str>() {
        format!("panic: {s}")
    } else if let Some(s) = panic.downcast_ref::<String>() {
        format!("panic: {s}")
    } else {
        "panic".into()
    }
}
