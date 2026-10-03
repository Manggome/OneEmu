//! `wie_backend::Platform` for a libretro host: the screen is a frame buffer the session hands to
//! `retro_run`, audio commands are queued for the mixer, time is the [`VirtualClock`].

use std::{
    collections::BTreeMap,
    path::PathBuf,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
};

use wie_backend::{AudioCommand, AudioSink, DatabaseRepository, Filesystem, Font, Instant, Platform, Screen, canvas::Image};
use wie_util::{Result, WieError};

use crate::{
    clock::VirtualClock,
    storage::{SaveDatabaseRepository, SaveFilesystem},
};

pub const DEFAULT_WIDTH: u32 = 240;
pub const DEFAULT_HEIGHT: u32 = 320;
const MAX_DIMENSION: u32 = 1024;

pub struct ScreenState {
    pub width: u32,
    pub height: u32,
    /// Last painted frame, 0x00RRGGBB, `frame_width * frame_height`.
    pub frame: Vec<u32>,
    pub frame_width: u32,
    pub frame_height: u32,
    /// A paint happened since the session last took the frame.
    pub dirty: bool,
}

/// Game output kept (and logged) per session.
const STDOUT_LIMIT: usize = 1 << 20;

/// State shared between the platform (called from inside wie) and the session driving it.
pub struct Shared {
    pub clock: VirtualClock,
    pub screen: Mutex<ScreenState>,
    pub redraw_requested: AtomicBool,
    pub audio_commands: Mutex<Vec<AudioCommand>>,
    pub exit_requested: AtomicBool,
    /// Pending vibration, milliseconds (0 = none).
    pub vibrate_ms: AtomicU64,
    pub stdout: Mutex<Vec<u8>>,
}

impl Shared {
    pub fn new(epoch_base_ms: u64, width: u32, height: u32) -> Arc<Self> {
        Arc::new(Self {
            clock: VirtualClock::new(epoch_base_ms),
            screen: Mutex::new(ScreenState {
                width,
                height,
                frame: vec![0; (width * height) as usize],
                frame_width: width,
                frame_height: height,
                dirty: true,
            }),
            redraw_requested: AtomicBool::new(true),
            audio_commands: Mutex::new(Vec::new()),
            exit_requested: AtomicBool::new(false),
            vibrate_ms: AtomicU64::new(0),
            stdout: Mutex::new(Vec::new()),
        })
    }
}

pub struct FrameScreen {
    shared: Arc<Shared>,
}

impl Screen for FrameScreen {
    fn resize(&self, width: u32, height: u32) -> Result<()> {
        if width == 0 || height == 0 || width > MAX_DIMENSION || height > MAX_DIMENSION {
            return Err(WieError::FatalError(format!("Invalid display size: {width}x{height}")));
        }
        let mut screen = self.shared.screen.lock().map_err(|_| WieError::FatalError("screen lock poisoned".into()))?;
        screen.width = width;
        screen.height = height;
        tracing::info!("screen resized to {width}x{height}");
        Ok(())
    }

    fn request_redraw(&self) -> Result<()> {
        self.shared.redraw_requested.store(true, Ordering::Release);
        Ok(())
    }

    fn paint(&self, image: &dyn Image) {
        let (w, h) = (image.width(), image.height());
        if w == 0 || h == 0 || w > MAX_DIMENSION || h > MAX_DIMENSION {
            return;
        }
        let colors = image.colors();
        let Ok(mut screen) = self.shared.screen.lock() else {
            return;
        };
        screen.frame.clear();
        screen.frame.extend(colors.iter().map(|c| ((c.r as u32) << 16) | ((c.g as u32) << 8) | (c.b as u32)));
        screen.frame.resize((w * h) as usize, 0);
        screen.frame_width = w;
        screen.frame_height = h;
        screen.dirty = true;
    }

    fn width(&self) -> u32 {
        self.shared.screen.lock().map(|s| s.width).unwrap_or(DEFAULT_WIDTH)
    }

    fn height(&self) -> u32 {
        self.shared.screen.lock().map(|s| s.height).unwrap_or(DEFAULT_HEIGHT)
    }
}

pub struct QueueAudioSink {
    shared: Arc<Shared>,
}

impl AudioSink for QueueAudioSink {
    fn send(&self, command: AudioCommand) {
        if let Ok(mut q) = self.shared.audio_commands.lock() {
            q.push(command);
        }
    }
}

pub struct LibretroPlatform {
    shared: Arc<Shared>,
    screen: FrameScreen,
    font: Font,
    filesystem: SaveFilesystem,
    database: SaveDatabaseRepository,
    system_properties: BTreeMap<String, String>,
}

impl LibretroPlatform {
    pub fn new(shared: Arc<Shared>, save_dir: PathBuf, font: Font, system_properties: BTreeMap<String, String>) -> Self {
        Self {
            screen: FrameScreen { shared: shared.clone() },
            filesystem: SaveFilesystem::new(save_dir.clone()),
            database: SaveDatabaseRepository::new(save_dir),
            shared,
            font,
            system_properties,
        }
    }
}

impl Platform for LibretroPlatform {
    fn system_property(&self, name: &str) -> Option<String> {
        self.system_properties.get(name).cloned()
    }

    fn font(&self) -> &Font {
        &self.font
    }

    fn screen(&self) -> &dyn Screen {
        &self.screen
    }

    fn now(&self) -> Instant {
        Instant::from_epoch_millis(self.shared.clock.now_ms())
    }

    fn database_repository(&self) -> &dyn DatabaseRepository {
        &self.database
    }

    fn filesystem(&self) -> &dyn Filesystem {
        &self.filesystem
    }

    fn audio_sink(&self) -> Box<dyn AudioSink> {
        Box::new(QueueAudioSink { shared: self.shared.clone() })
    }

    fn write_stdout(&self, buf: &[u8]) {
        // Bounded: 놈ZERO's own heap check prints the same line forever once it trips (4 GB in five minutes),
        // which grew this buffer to 11 GB and flooded the log.
        if let Ok(mut out) = self.shared.stdout.lock() {
            if out.len() >= STDOUT_LIMIT {
                return;
            }
            let take = buf.len().min(STDOUT_LIMIT - out.len());
            out.extend_from_slice(&buf[..take]);
            if out.len() >= STDOUT_LIMIT {
                tracing::warn!(target: "wipi::stdout", "more than {STDOUT_LIMIT} bytes of output; dropping the rest");
            }
        }
        tracing::info!(target: "wipi::stdout", "{}", String::from_utf8_lossy(buf));
    }

    fn write_stderr(&self, buf: &[u8]) {
        tracing::warn!(target: "wipi::stderr", "{}", String::from_utf8_lossy(buf));
    }

    fn exit(&self) {
        self.shared.exit_requested.store(true, Ordering::Release);
    }

    fn vibrate(&self, duration_ms: u64, _intensity: u8) {
        self.shared.vibrate_ms.store(duration_ms.max(1), Ordering::Release);
    }
}
