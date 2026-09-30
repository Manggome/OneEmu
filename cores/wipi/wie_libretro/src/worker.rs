//! Runs the [`Session`] on its own thread with a 16 MiB stack.
//!
//! wie's async JVM/ARM runtime recurses deeply; the frontend's emulation thread (a plain std::thread,
//! ~1 MiB on Android) overflows on real games. The worker runs in lockstep with `retro_run`: one
//! request, one frame back, so all libretro callbacks stay on the frontend's thread.

use std::{
    sync::mpsc::{Receiver, Sender, channel},
    thread::JoinHandle,
};

use crate::{
    input::KeySet,
    options::RawOptions,
    session::{FrameOutput, LoadInfo, LoadRequest, Session, panic_message},
};

const STACK_SIZE: usize = 16 << 20;

enum Request {
    Frame { keys: KeySet, recycle: FrameOutput },
    Options(RawOptions),
    Quit,
}

pub struct Worker {
    tx: Sender<Request>,
    rx: Receiver<FrameOutput>,
    thread: Option<JoinHandle<()>>,
}

impl Worker {
    /// Starts the thread and loads the game on it. Returns once loading finished.
    pub fn start(req: LoadRequest) -> anyhow::Result<(Self, LoadInfo)> {
        let (req_tx, req_rx) = channel::<Request>();
        let (out_tx, out_rx) = channel::<FrameOutput>();
        let (load_tx, load_rx) = channel::<Result<LoadInfo, String>>();

        let thread = std::thread::Builder::new()
            .name("wie-worker".into())
            .stack_size(STACK_SIZE)
            .spawn(move || {
                let loaded = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| Session::load(req)));
                let mut session = match loaded {
                    Ok(Ok((session, info))) => {
                        let _ = load_tx.send(Ok(info));
                        session
                    }
                    Ok(Err(e)) => {
                        let _ = load_tx.send(Err(format!("{e:#}")));
                        return;
                    }
                    Err(panic) => {
                        let _ = load_tx.send(Err(panic_message(&panic)));
                        return;
                    }
                };
                drop(load_tx);

                while let Ok(request) = req_rx.recv() {
                    match request {
                        Request::Frame { keys, recycle } => {
                            let out = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| session.run_frame(keys, recycle))) {
                                Ok(out) => out,
                                Err(panic) => FrameOutput {
                                    error: Some(panic_message(&panic)),
                                    ..Default::default()
                                },
                            };
                            if out_tx.send(out).is_err() {
                                break;
                            }
                        }
                        Request::Options(raw) => session.update_options(&raw),
                        Request::Quit => break,
                    }
                }
            })?;

        match load_rx.recv() {
            Ok(Ok(info)) => Ok((
                Self {
                    tx: req_tx,
                    rx: out_rx,
                    thread: Some(thread),
                },
                info,
            )),
            Ok(Err(e)) => {
                let _ = thread.join();
                Err(anyhow::anyhow!(e))
            }
            Err(_) => {
                let _ = thread.join();
                Err(anyhow::anyhow!("worker thread died while loading"))
            }
        }
    }

    pub fn run_frame(&self, keys: KeySet, recycle: FrameOutput) -> Option<FrameOutput> {
        self.tx.send(Request::Frame { keys, recycle }).ok()?;
        self.rx.recv().ok()
    }

    pub fn set_options(&self, raw: RawOptions) {
        let _ = self.tx.send(Request::Options(raw));
    }
}

impl Drop for Worker {
    fn drop(&mut self) {
        let _ = self.tx.send(Request::Quit);
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
    }
}
