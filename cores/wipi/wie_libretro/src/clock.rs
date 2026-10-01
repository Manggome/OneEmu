//! Virtual clock handed to wie as `Platform::now()`.
//!
//! wie's executor runs every task whose wake-up time has passed and stops a tick 8 ms after it started,
//! both measured with `now()`. A wall clock would keep ticking while the frontend is paused or the
//! app is in the background (timers burst on resume) and would ignore fast-forward. So game time here
//! only moves while `retro_run` drives the emulator:
//!
//! * inside a tick, time advances with the real time the tick has spent (so the 8 ms cut-off fires),
//! * between ticks the session nudges time forward when every task is asleep (`advance`),
//! * the session caps game time at the frame's target, so one libretro frame = 1/60 s of game time.

use std::{
    sync::{
        Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::Instant as StdInstant,
};

pub struct VirtualClock {
    /// Epoch milliseconds that virtual time 0 maps to (wall clock at load, or a fixed date).
    epoch_base_ms: u64,
    /// Virtual microseconds elapsed since load, excluding the tick currently running.
    vt_us: AtomicU64,
    /// Real time the current tick started, while a tick is running.
    tick_start: Mutex<Option<StdInstant>>,
}

impl VirtualClock {
    pub fn new(epoch_base_ms: u64) -> Self {
        Self {
            epoch_base_ms,
            vt_us: AtomicU64::new(0),
            tick_start: Mutex::new(None),
        }
    }

    /// Epoch milliseconds, as wie wants them.
    pub fn now_ms(&self) -> u64 {
        self.epoch_base_ms + self.now_us() / 1000
    }

    /// Virtual microseconds since load, including the running tick.
    pub fn now_us(&self) -> u64 {
        let base = self.vt_us.load(Ordering::Acquire);
        let running = self.tick_start.lock().ok().and_then(|x| *x).map(|start| start.elapsed().as_micros() as u64);
        base + running.unwrap_or(0)
    }

    /// Virtual microseconds since load, excluding a running tick.
    pub fn vt_us(&self) -> u64 {
        self.vt_us.load(Ordering::Acquire)
    }

    pub fn begin_tick(&self) {
        if let Ok(mut start) = self.tick_start.lock() {
            *start = Some(StdInstant::now());
        }
    }

    /// Ends the running tick and folds the real time it took into virtual time. Returns that time (µs).
    pub fn end_tick(&self) -> u64 {
        let elapsed = self
            .tick_start
            .lock()
            .ok()
            .and_then(|mut x| x.take())
            .map(|start| start.elapsed().as_micros() as u64)
            .unwrap_or(0);
        self.vt_us.fetch_add(elapsed, Ordering::AcqRel);
        elapsed
    }

    pub fn advance(&self, us: u64) {
        self.vt_us.fetch_add(us, Ordering::AcqRel);
    }

    /// Moves virtual time forward to `us` if it is behind (never backwards).
    pub fn catch_up_to(&self, us: u64) {
        self.vt_us.fetch_max(us, Ordering::AcqRel);
    }
}

/// Parses `YYYY-MM-DD` into epoch milliseconds (UTC midnight + 12h so every timezone shows that date).
pub fn parse_date_ms(value: &str) -> Option<u64> {
    let mut parts = value.trim().split('-');
    let y: i64 = parts.next()?.parse().ok()?;
    let m: i64 = parts.next()?.parse().ok()?;
    let d: i64 = parts.next()?.parse().ok()?;
    if parts.next().is_some() || !(1970..=2099).contains(&y) || !(1..=12).contains(&m) || !(1..=31).contains(&d) {
        return None;
    }
    // days_from_civil (Howard Hinnant)
    let y2 = if m <= 2 { y - 1 } else { y };
    let era = y2.div_euclid(400);
    let yoe = y2 - era * 400;
    let mp = (m + 9) % 12;
    let doy = (153 * mp + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146097 + doe - 719468;
    Some((days as u64) * 86_400_000 + 12 * 3_600_000)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn date_parsing() {
        assert_eq!(parse_date_ms("1970-01-01"), Some(12 * 3_600_000));
        // 2010-05-01 = 14730 days after the epoch
        assert_eq!(parse_date_ms("2010-05-01"), Some(14730 * 86_400_000 + 12 * 3_600_000));
        assert_eq!(parse_date_ms("2010-13-01"), None);
        assert_eq!(parse_date_ms("garbage"), None);
    }

    #[test]
    fn clock_is_monotonic_and_only_moves_when_driven() {
        let clock = VirtualClock::new(1_000_000);
        assert_eq!(clock.now_ms(), 1_000_000);
        clock.advance(2_500);
        assert_eq!(clock.now_ms(), 1_000_002);
        clock.catch_up_to(1_000); // behind: ignored
        assert_eq!(clock.vt_us(), 2_500);
        clock.catch_up_to(10_000);
        assert_eq!(clock.vt_us(), 10_000);
        clock.begin_tick();
        let during = clock.now_us();
        assert!(during >= 10_000);
        let spent = clock.end_tick();
        assert_eq!(clock.vt_us(), 10_000 + spent);
    }
}
