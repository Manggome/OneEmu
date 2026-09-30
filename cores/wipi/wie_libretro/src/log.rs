//! Routes `tracing` output (ours and wie's) to the frontend's libretro log, or stderr without one.

use std::{
    ffi::{CString, c_int, c_void},
    io,
    sync::{
        Once,
        atomic::{AtomicUsize, Ordering},
    },
};

use tracing::{Level, Metadata};
use tracing_subscriber::fmt::MakeWriter;

use crate::ffi::*;

/// The frontend's retro_log_printf_t, stored as usize (0 = none).
static LOG_FN: AtomicUsize = AtomicUsize::new(0);
static INIT: Once = Once::new();

/// Fetches the log interface (every call: the frontend may hand a new one) and installs the
/// subscriber once.
pub fn init() {
    let mut cb = retro_log_callback { log: None };
    let ok = unsafe { crate::env(RETRO_ENVIRONMENT_GET_LOG_INTERFACE, &mut cb as *mut retro_log_callback as *mut c_void) };
    if ok && let Some(f) = cb.log {
        LOG_FN.store(f as usize, Ordering::Release);
    }

    INIT.call_once(|| {
        let _ = tracing_subscriber::fmt()
            .with_writer(MakeRetroLog)
            .without_time()
            .with_target(false)
            .with_level(false)
            .with_max_level(Level::INFO)
            .try_init();
    });
}

fn emit(level: c_int, text: &str) {
    let text = text.trim_end();
    if text.is_empty() {
        return;
    }
    let f = LOG_FN.load(Ordering::Acquire);
    if f == 0 {
        eprintln!("[wipi] {text}");
        return;
    }
    let log: retro_log_printf_t = unsafe { std::mem::transmute::<usize, retro_log_printf_t>(f) };
    if let Ok(msg) = CString::new(format!("[wipi] {text}\n").replace('\0', " ")) {
        unsafe { log(level, c"%s".as_ptr(), msg.as_ptr()) };
    }
}

pub struct RetroLogWriter {
    level: c_int,
    buf: Vec<u8>,
}

impl io::Write for RetroLogWriter {
    fn write(&mut self, data: &[u8]) -> io::Result<usize> {
        self.buf.extend_from_slice(data);
        Ok(data.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

impl Drop for RetroLogWriter {
    fn drop(&mut self) {
        emit(self.level, &String::from_utf8_lossy(&self.buf));
    }
}

pub struct MakeRetroLog;

impl<'a> MakeWriter<'a> for MakeRetroLog {
    type Writer = RetroLogWriter;

    fn make_writer(&'a self) -> Self::Writer {
        RetroLogWriter { level: RETRO_LOG_INFO, buf: Vec::new() }
    }

    fn make_writer_for(&'a self, meta: &Metadata<'_>) -> Self::Writer {
        let level = match *meta.level() {
            Level::ERROR => RETRO_LOG_ERROR,
            Level::WARN => RETRO_LOG_WARN,
            Level::INFO => RETRO_LOG_INFO,
            _ => RETRO_LOG_DEBUG,
        };
        RetroLogWriter { level, buf: Vec::new() }
    }
}
